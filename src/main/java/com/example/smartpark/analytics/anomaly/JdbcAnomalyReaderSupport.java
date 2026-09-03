package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.analytics.sql.UnsafeSqlException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class JdbcAnomalyReaderSupport {
    private JdbcAnomalyReaderSupport() {}

    static TabularResult execute(ReadOnlyQueryExecutor executor, String sql,
                                 OperationsAnomalyQuery query) throws UnsafeSqlException {
        return executor.execute(new ValidatedSql(sql,
                        List.of("from", "to", "buildingId", "riskLevel", "category", "status", "deviceType"), 500),
                parameters(query));
    }

    static Map<String, Object> parameters(OperationsAnomalyQuery query) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("from", query.from());
        parameters.put("to", query.to());
        parameters.put("buildingId", query.buildingId());
        parameters.put("riskLevel", query.riskLevel());
        parameters.put("category", query.category());
        parameters.put("status", query.status());
        parameters.put("deviceType", query.deviceType());
        return parameters;
    }

    static Object value(TabularResult result, List<Object> row, String column) {
        int index = result.columnNames().indexOf(column);
        return index < 0 || index >= row.size() ? null : row.get(index);
    }

    static String text(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        return value == null ? null : value.toString();
    }

    static long longValue(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        return value instanceof Number number ? number.longValue() : value == null ? 0L : Long.parseLong(value.toString());
    }

    static Double decimal(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value == null) return null;
        return value instanceof BigDecimal decimal ? decimal.doubleValue() : ((Number) value).doubleValue();
    }

    static Instant instant(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.time.OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        return value == null ? null : Instant.parse(value.toString());
    }

    static String failureCode(Exception exception) {
        return exception instanceof UnsafeSqlException unsafe ? unsafe.errorCode() : "QUERY_FAILED";
    }
}
