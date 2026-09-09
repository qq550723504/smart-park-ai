package com.example.smartpark.analytics.telemetry;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.analytics.sql.SqlAstGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed-shape, bounded telemetry adapter using the same SQL gates as energy time series. */
public final class JdbcDeviceTelemetryReader implements DeviceTelemetryReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcDeviceTelemetryReader.class);
    static final int MAX_DEVICES = 10;
    static final int MAX_ROWS = 500;

    private final QueryCostGuard costGuard;
    private final ReadOnlyQueryExecutor executor;

    public JdbcDeviceTelemetryReader(QueryCostGuard costGuard, ReadOnlyQueryExecutor executor) {
        this.costGuard = Objects.requireNonNull(costGuard, "costGuard");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Snapshot read(Request request, TelemetryCatalog.Definition definition) {
        try {
            return executor.executeInConsistentSnapshot(() -> {
                Map<String, Object> deviceParameters = Map.of("deviceIds", request.deviceIds());
                TabularResult deviceResult = execute(
                        "SELECT device_id, building_id, device_type, status, snapshot_at "
                                + "FROM analytics.v_device_snapshot WHERE device_id IN (:deviceIds) "
                                + "ORDER BY device_id ASC LIMIT " + MAX_DEVICES,
                        deviceParameters);
                List<DeviceDescriptor> devices = deviceResult.rows().stream().map(row -> new DeviceDescriptor(
                        text(deviceResult, row, "device_id"), text(deviceResult, row, "building_id"),
                        text(deviceResult, row, "device_type"), text(deviceResult, row, "status"),
                        instant(deviceResult, row, "snapshot_at"))).toList();

                String sql = "SELECT device_id, building_id, device_type, hour_ts AS bucket_ts, value, quality, observed_at "
                        + "FROM " + definition.sourceView()
                        + " WHERE telemetry_type = :telemetryType AND unit = :unit "
                        + "AND hour_ts >= :from AND hour_ts < :to "
                        + "AND device_id IN (:deviceIds) ORDER BY device_id ASC, hour_ts ASC LIMIT " + MAX_ROWS;
                Map<String, Object> parameters = new LinkedHashMap<>();
                parameters.put("telemetryType", definition.telemetryType());
                parameters.put("unit", definition.unit());
                parameters.put("from", request.from().atOffset(ZoneOffset.UTC));
                parameters.put("to", request.to().atOffset(ZoneOffset.UTC));
                parameters.put("deviceIds", request.deviceIds());
                TabularResult telemetryResult = execute(sql, parameters);
                List<Row> rows = telemetryResult.rows().stream().map(row -> new Row(
                        text(telemetryResult, row, "device_id"), text(telemetryResult, row, "building_id"),
                        text(telemetryResult, row, "device_type"), instant(telemetryResult, row, "bucket_ts"),
                        decimal(telemetryResult, row, "value"), text(telemetryResult, row, "quality"),
                        instant(telemetryResult, row, "observed_at"))).toList();
                return Snapshot.available(devices, rows, deviceResult.truncated() || telemetryResult.truncated());
            });
        } catch (Exception exception) {
            LOGGER.warn("Device telemetry query unavailable: exceptionType={}", exception.getClass().getName());
            return Snapshot.unavailable("TELEMETRY_QUERY_UNAVAILABLE");
        }
    }

    private TabularResult execute(String sql, Map<String, Object> parameters) throws Exception {
        ValidatedSql validated = SqlAstGuard.validate(sql);
        costGuard.estimatedCost(validated.sql(), parameters);
        return executor.execute(validated, parameters);
    }

    private static Object value(TabularResult result, List<Object> row, String column) {
        int index = result.columnNames().indexOf(column);
        return index < 0 || index >= row.size() ? null : row.get(index);
    }
    private static String text(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        return value == null ? null : value.toString();
    }
    private static BigDecimal decimal(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof Number number) return new BigDecimal(number.toString());
        return value == null ? null : new BigDecimal(value.toString());
    }
    private static Instant instant(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return value == null ? null : Instant.parse(value.toString());
    }
}
