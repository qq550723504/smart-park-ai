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

        int hardCap = Math.min(limits.maxRows(), validated.maxRows());
        // The SQL's own LIMIT hides whether further matches exist: once the
        // database stops at LIMIT N there is no extra row to observe. Raise
        // the declared bound by exactly one row so truncation becomes
        // detectable while still returning at most hardCap rows.
        String boundedProbe = withOneExtraProbeRow(validated.sql(), hardCap);

        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean[] truncated = {false};

        return template.query(boundedProbe, new MapSqlParameterSource(parameters), rs -> {
            for (int c = 1; c <= rs.getMetaData().getColumnCount(); c++) {
                columns.add(rs.getMetaData().getColumnLabel(c));
            }
            long bytes = 0;
            while (rs.next()) {
                if (rows.size() == hardCap) {
                    // One extra row exists beyond the SQL LIMIT/hard cap: the
                    // result is a clipped window of the full match set and must
                    // be reported as truncated rather than complete.
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

    /** Raises a trailing LIMIT by one row (capped at hardCap+1) for the truncation probe. */
    private static String withOneExtraProbeRow(String sql, int hardCap) {
        String base = sql.strip();
        if (base.endsWith(";")) {
            base = base.substring(0, base.length() - 1).strip();
        }
        var matcher = java.util.regex.Pattern.compile("(?i)\\blimit\\s+(\\d+)\\s*$").matcher(base);
        if (matcher.find()) {
            long declared = Long.parseLong(matcher.group(1));
            long probeLimit = Math.min(declared, hardCap) + 1;
            return base.substring(0, matcher.start()) + "LIMIT " + probeLimit;
        }
        // The AST guard mandates a trailing LIMIT; keep a defensive fallback.
        return base + " LIMIT " + (hardCap + 1);
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
