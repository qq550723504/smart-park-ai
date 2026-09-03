package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JdbcDeviceAnalyticsReader implements DeviceAnalyticsReader {
    private static final String FILTERS = "WHERE snapshot_at >= :from AND snapshot_at < :to "
            + "AND (:buildingId IS NULL OR building_id = :buildingId) "
            + "AND (:deviceType IS NULL OR device_type = :deviceType)";
    private final ReadOnlyQueryExecutor executor;

    public JdbcDeviceAnalyticsReader(ReadOnlyQueryExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Snapshot read(OperationsAnomalyQuery query) {
        try {
            TabularResult summary = execute("SELECT COUNT(*) FILTER (WHERE status = 'OFFLINE') AS offline_device_count, MAX(snapshot_at) AS as_of FROM analytics.v_device_snapshot " + FILTERS, query);
            List<AlertAnalyticsReader.Breakdown> deviceTypes = breakdown("device_type", query);
            List<BuildingSummary> buildings = buildings(query);
            List<Object> row = summary.rows().isEmpty() ? List.of() : summary.rows().get(0);
            return new Snapshot(JdbcAnomalyReaderSupport.longValue(summary, row, "offline_device_count"), deviceTypes,
                    buildings, JdbcAnomalyReaderSupport.instant(summary, row, "as_of"), true,
                    summary.truncated() ? "RESULT_TRUNCATED" : null);
        } catch (Exception exception) {
            return Snapshot.unavailable(JdbcAnomalyReaderSupport.failureCode(exception));
        }
    }

    @Override
    public List<DeviceReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        if (buildingId == null || buildingId.isBlank()) return List.of();
        try {
            OperationsAnomalyQuery scoped = new OperationsAnomalyQuery(query.from(), query.to(), buildingId,
                    query.riskLevel(), query.category(), query.status(), query.deviceType());
            TabularResult result = execute("SELECT device_id, building_id, device_type, status, snapshot_at, open_alert_count FROM analytics.v_device_snapshot " + FILTERS + " AND status <> 'ONLINE' ORDER BY snapshot_at DESC, device_id ASC LIMIT 20", scoped);
            List<DeviceReference> references = new ArrayList<>();
            for (List<Object> row : result.rows()) {
                String status = JdbcAnomalyReaderSupport.text(result, row, "status");
                references.add(new DeviceReference(JdbcAnomalyReaderSupport.text(result, row, "device_id"),
                        JdbcAnomalyReaderSupport.text(result, row, "building_id"),
                        JdbcAnomalyReaderSupport.text(result, row, "device_type"), status,
                        JdbcAnomalyReaderSupport.instant(result, row, "snapshot_at"),
                        JdbcAnomalyReaderSupport.longValue(result, row, "open_alert_count"),
                        "REDACTED: 设备状态 · " + (status == null ? "未知" : status), null));
            }
            return List.copyOf(references);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<AlertAnalyticsReader.Breakdown> breakdown(String dimension, OperationsAnomalyQuery query) throws Exception {
        TabularResult result = execute("SELECT " + dimension + " AS key, COUNT(*) AS count FROM analytics.v_device_snapshot " + FILTERS + " AND status = 'OFFLINE' GROUP BY " + dimension + " ORDER BY count DESC, key ASC LIMIT 50", query);
        return result.rows().stream().map(row -> new AlertAnalyticsReader.Breakdown(JdbcAnomalyReaderSupport.text(result, row, "key"), JdbcAnomalyReaderSupport.longValue(result, row, "count"))).toList();
    }

    private List<BuildingSummary> buildings(OperationsAnomalyQuery query) throws Exception {
        TabularResult result = execute("SELECT building_id, COUNT(*) AS offline_device_count FROM analytics.v_device_snapshot " + FILTERS + " AND status = 'OFFLINE' GROUP BY building_id ORDER BY offline_device_count DESC, building_id ASC LIMIT 50", query);
        return result.rows().stream().map(row -> new BuildingSummary(JdbcAnomalyReaderSupport.text(result, row, "building_id"), JdbcAnomalyReaderSupport.longValue(result, row, "offline_device_count"))).sorted(Comparator.comparingLong(BuildingSummary::offlineDeviceCount).reversed().thenComparing(BuildingSummary::buildingId)).toList();
    }

    private TabularResult execute(String sql, OperationsAnomalyQuery query) throws Exception {
        return JdbcAnomalyReaderSupport.execute(executor, sql, query);
    }
}
