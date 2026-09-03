package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Types;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
        definition.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        definition.setTimeout((int) Math.max(1, limits.statementTimeout().toSeconds()));
        this.readOnlyTransaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource), definition);
    }

    @FunctionalInterface
    public interface SnapshotWork<T> {
        T call() throws Exception;
    }

    /** Runs several read-only statements against one repeatable-read snapshot. */
    public <T> T executeInConsistentSnapshot(SnapshotWork<T> work) throws Exception {
        try {
            return readOnlyTransaction.execute(status -> {
                try {
                    return work.call();
                } catch (Exception exception) {
                    throw new SnapshotWorkFailure(exception);
                }
            });
        } catch (SnapshotWorkFailure failure) {
            throw failure.cause;
        }
    }

    public TabularResult execute(ValidatedSql validated, Map<String, Object> parameters) throws UnsafeSqlException {
        long start = System.currentTimeMillis();
        int hardCap = Math.min(limits.maxRows(), validated.maxRows());
        // Rewrite before touching the database so an unrewritable LIMIT shape
        // (e.g. a trailing OFFSET the parser silently dropped) is rejected
        // cleanly instead of failing as a broken-SQL syntax error.
        String boundedProbe = withOneExtraProbeRow(validated.sql(), hardCap);
        try {
            TabularResult result = readOnlyTransaction.execute(status ->
                    runQuery(boundedProbe, parameters, hardCap, start));
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

    private TabularResult runQuery(String boundedProbe, Map<String, Object> parameters,
                                    int hardCap, long start) {
        NamedParameterJdbcTemplate template = new NamedParameterJdbcTemplate(dataSource);
        template.getJdbcTemplate().setQueryTimeout(Math.max(1, (int) limits.statementTimeout().toSeconds()));

        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean[] truncated = {false};

        return template.query(boundedProbe, parameterSource(parameters), rs -> {
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

    private static MapSqlParameterSource parameterSource(Map<String, Object> parameters) {
        MapSqlParameterSource source = new MapSqlParameterSource();
        if (parameters == null) return source;
        parameters.forEach((name, value) -> {
            if (value instanceof Instant instant) {
                source.addValue(name, Timestamp.from(instant), Types.TIMESTAMP);
            } else if (value instanceof OffsetDateTime offsetDateTime) {
                source.addValue(name, offsetDateTime, Types.TIMESTAMP_WITH_TIMEZONE);
            } else if (value == null) {
                source.addValue(name, null, temporalParameter(name) ? Types.TIMESTAMP : Types.VARCHAR);
            } else {
                source.addValue(name, value);
            }
        });
        return source;
    }

    private static boolean temporalParameter(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        return normalized.equals("from") || normalized.equals("to")
                || normalized.endsWith("from") || normalized.endsWith("to")
                || normalized.contains("time") || normalized.contains("date");
    }

    /**
     * Raises a trailing LIMIT by one row (capped at hardCap+1) for the
     * truncation probe. jsqlparser silently drops a trailing OFFSET from its
     * AST, so such shapes slip past the guard; they cannot be rewritten safely
     * and are rejected fail-closed instead of appending a duplicate LIMIT.
     */
    private static String withOneExtraProbeRow(String sql, int hardCap) throws UnsafeSqlException {
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
        if (java.util.regex.Pattern.compile("(?i)\\blimit\\s+\\d+\\s+offset\\s+\\d+$")
                .matcher(base).find()) {
            throw new UnsafeSqlException("SQL_POLICY_REJECTED",
                    "不支持的 LIMIT/OFFSET 形态，已拒绝执行（OFFSET）");
        }
        // The guard's contract is a plain trailing LIMIT; anything else that is
        // not an OFFSET shape keeps the previous append fallback.
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

    private static final class SnapshotWorkFailure extends RuntimeException {
        private final Exception cause;

        private SnapshotWorkFailure(Exception cause) {
            super(cause);
            this.cause = cause;
        }
    }

    public record QueryLimits(Duration statementTimeout, int maxRows, long maxResultBytes, double maxPlanCost) {}
}
