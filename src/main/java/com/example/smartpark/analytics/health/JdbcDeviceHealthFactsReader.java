package com.example.smartpark.analytics.health;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.model.ValidatedSql;
import com.example.smartpark.analytics.sql.QueryCostGuard;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.analytics.sql.SqlAstGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcDeviceHealthFactsReader implements DeviceHealthFactsReader {
    private static final Logger LOGGER = LoggerFactory.getLogger(JdbcDeviceHealthFactsReader.class);
    private final QueryCostGuard costGuard;
    private final ReadOnlyQueryExecutor executor;

    public JdbcDeviceHealthFactsReader(QueryCostGuard costGuard, ReadOnlyQueryExecutor executor) {
        this.costGuard = Objects.requireNonNull(costGuard, "costGuard");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Facts read(String deviceId) {
        try {
            return executor.executeInConsistentSnapshot(() -> {
                Map<String, Object> parameters = Map.of("deviceId", deviceId);
                TabularResult devices = execute("SELECT device_id, building_id, device_type, status, snapshot_at "
                        + "FROM analytics.v_device_snapshot WHERE device_id = :deviceId LIMIT 1", parameters);
                DeviceFact device = devices.rows().isEmpty() ? null : mapDevice(devices, devices.rows().get(0));
                TabularResult alerts = execute("SELECT alert_id, building_id, device_id, category, risk_level, status, occurred_at "
                        + "FROM analytics.v_alert_fact WHERE device_id = :deviceId "
                        + "AND (status <> 'RESOLVED' OR status IS NULL) "
                        + "ORDER BY occurred_at DESC, alert_id ASC LIMIT 20", parameters);
                List<AlertFact> active = alerts.rows().stream().map(row -> new AlertFact(
                        text(alerts, row, "alert_id"), text(alerts, row, "building_id"),
                        text(alerts, row, "device_id"), text(alerts, row, "category"),
                        text(alerts, row, "risk_level"), text(alerts, row, "status"),
                        instant(alerts, row, "occurred_at"))).toList();
                return Facts.available(device, active, devices.truncated() || alerts.truncated());
            });
        } catch (Exception exception) {
            LOGGER.warn("Device health facts unavailable: exceptionType={}", exception.getClass().getName());
            return Facts.unavailable("DEVICE_HEALTH_FACTS_UNAVAILABLE");
        }
    }

    @Override
    public String findDeviceIdByAlertId(String alertId) {
        try {
            Map<String, Object> parameters = Map.of("alertId", alertId);
            TabularResult result = execute("SELECT device_id FROM analytics.v_alert_fact "
                    + "WHERE alert_id = :alertId LIMIT 1", parameters);
            return result.rows().isEmpty() ? null : text(result, result.rows().get(0), "device_id");
        } catch (Exception exception) {
            LOGGER.warn("Alert device scope unavailable: exceptionType={}", exception.getClass().getName());
            return null;
        }
    }

    private TabularResult execute(String sql, Map<String, Object> parameters) throws Exception {
        ValidatedSql validated = SqlAstGuard.validate(sql);
        costGuard.estimatedCost(validated.sql(), parameters);
        return executor.execute(validated, parameters);
    }
    private static DeviceFact mapDevice(TabularResult result, List<Object> row) {
        return new DeviceFact(text(result, row, "device_id"), text(result, row, "building_id"),
                text(result, row, "device_type"), text(result, row, "status"), instant(result, row, "snapshot_at"));
    }
    private static Object value(TabularResult result, List<Object> row, String column) {
        int index = result.columnNames().indexOf(column);
        return index < 0 || index >= row.size() ? null : row.get(index);
    }
    private static String text(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        return value == null ? null : value.toString();
    }
    private static Instant instant(TabularResult result, List<Object> row, String column) {
        Object value = value(result, row, column);
        if (value instanceof Instant instant) return instant;
        if (value instanceof OffsetDateTime offsetDateTime) return offsetDateTime.toInstant();
        if (value instanceof Timestamp timestamp) return timestamp.toInstant();
        return value == null ? null : Instant.parse(value.toString());
    }
}
