package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The last application-side boundary before the database itself. Accepts only
 * {@link ValidatedSql}, runs inside a read-only transaction with a statement
 * timeout, and caps rows and result bytes independently of the AST guard.
 */
public class ReadOnlyQueryExecutor {

    private final DataSource dataSource;
    private final QueryLimits limits;
    private final TransactionTemplate readOnlyTransaction;

    public ReadOnlyQueryExecutor(DataSource dataSource, QueryLimits limits) {
        this.dataSource = dataSource;
        this.limits = limits;
        DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
        definition.setReadOnly(true);
        definition.setTimeout((int) Math.max(1, limits.statementTimeout().toSeconds()));
        this.readOnlyTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource), definition);
    }

    public TabularResult execute(ValidatedSql validated, Map<String, Object> parameters) throws UnsafeSqlException {
        long start = System.currentTimeMillis();
        try {
            TabularResult result = readOnlyTransaction.execute(status ->
                    runQuery(validated, parameters, start));
            return result == null
                    ? new TabularResult(List.of(), List.of(), false, System.currentTimeMillis() - start)
                    : result;
        } catch (PolicyViolation violation) {
            throw new UnsafeSqlException(violation.errorCode, violation.getMessage());
        } catch (org.springframework.dao.DataAccessException exception) {
            String cause = exception.getMostSpecificCause().getMessage() == null
                    ? "" : exception.getMostSpecificCause().getMessage().toLowerCase();
            if (cause.contains("read-only")) {
                throw new UnsafeSqlException("QUERY_READ_ONLY_DENIED", "数据库拒绝了该操作：连接为只读，禁止写入");
            }
            if (cause.contains("canceling statement due to") || cause.contains("statement timeout")) {
                throw new UnsafeSqlException("QUERY_TIMEOUT", "查询超时，已取消");
            }
            if (cause.contains("permission denied")) {
                throw new UnsafeSqlException("QUERY_READ_ONLY_DENIED", "数据库拒绝了该操作：权限不足");
            }
            throw new UnsafeSqlException("QUERY_FAILED", "查询执行失败，已终止本次分析");
        }
    }

    private TabularResult runQuery(ValidatedSql validated, Map<String, Object> parameters, long start) {
        NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(Math.max(1, (int) limits.statementTimeout().toSeconds()));

        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean[] truncated = {false};

        return template.query(validated.sql(), new MapSqlParameterSource(parameters), rs -> {
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                columns.add(rs.getMetaData().getColumnLabel(c));
            }
            int hardCap = Math.min(limits.maxRows(), validated.maxRows());
            long bytes = 0;
            while (rs.next()) {
                if (rows.size() >= hardCap) {
                    truncated[0] = true;
                    break;
                }
                List<Object> row = new ArrayList<>(columns.size());
                for (int c = 1; c <= columns.size(); c++) {
                    Object value = rs.getObject(c);
                    row.add(value);
                    bytes += value == null ? 4 : value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                }
                rows.add(row);
                if (bytes > limits.maxResultBytes()) {
                    throw new PolicyViolation("QUERY_RESULT_TOO_LARGE", "查询结果超过字节上限，已拒绝返回");
                }
            }
            return new TabularResult(columns, rows, truncated[0], System.currentTimeMillis() - start);
        });
    }

    /** Runtime wrapper so checked rejections can cross the transaction callback. */
    private static final class PolicyViolation extends RuntimeException {
        private final String errorCode;

        PolicyViolation(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }

    public record QueryLimits(Duration statementTimeout, int maxRows, long maxResultBytes, double maxPlanCost) {}
}
