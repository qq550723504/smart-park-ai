package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import com.example.smartpark.workflow.WorkflowSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class JdbcAlertAnalyticsReader implements AlertAnalyticsReader {
    private static final String FILTERS = "WHERE occurred_at >= :from AND occurred_at < :to "
            + "AND (:buildingId IS NULL OR building_id = :buildingId) "
            + "AND (:riskLevel IS NULL OR risk_level = :riskLevel) "
            + "AND (:category IS NULL OR category = :category) "
            + "AND (:status IS NULL OR status = :status)";
    private final ReadOnlyQueryExecutor executor;
    private final Optional<WorkflowExecutionStore> workflowStore;

    public JdbcAlertAnalyticsReader(ReadOnlyQueryExecutor executor) {
        this(executor, null);
    }

    public JdbcAlertAnalyticsReader(ReadOnlyQueryExecutor executor, WorkflowExecutionStore workflowStore) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
        this.workflowStore = Optional.ofNullable(workflowStore);
    }

    @Override
    public Snapshot read(OperationsAnomalyQuery query) {
        try {
            TabularResult summary = execute("SELECT COUNT(*) AS alert_count, COUNT(*) FILTER (WHERE risk_level = 'HIGH') AS high_risk_alert_count FROM analytics.v_alert_fact " + FILTERS, query);
            List<Breakdown> riskLevels = breakdown("risk_level", query);
            List<Breakdown> categories = breakdown("category", query);
            List<Breakdown> statuses = breakdown("status", query);
            List<BuildingSummary> buildings = buildings(query);
            List<Object> row = summary.rows().isEmpty() ? List.of() : summary.rows().get(0);
            return new Snapshot(JdbcAnomalyReaderSupport.longValue(summary, row, "alert_count"),
                    JdbcAnomalyReaderSupport.longValue(summary, row, "high_risk_alert_count"),
                    riskLevels, categories, statuses, buildings, true,
                    summary.truncated() ? "RESULT_TRUNCATED" : null);
        } catch (Exception exception) {
            return Snapshot.unavailable(JdbcAnomalyReaderSupport.failureCode(exception));
        }
    }

    @Override
    public EvidenceResult<AlertReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        if (buildingId == null || buildingId.isBlank()) return EvidenceResult.available(List.of());
        try {
            OperationsAnomalyQuery scoped = new OperationsAnomalyQuery(query.from(), query.to(), buildingId,
                    query.riskLevel(), query.category(), query.status(), query.deviceType());
            TabularResult result = execute("SELECT alert_id, building_id, device_id, category, risk_level, status, occurred_at FROM analytics.v_alert_fact " + FILTERS + " ORDER BY occurred_at DESC, alert_id ASC LIMIT 10", scoped);
            List<AlertReference> references = new ArrayList<>();
            for (List<Object> row : result.rows()) {
                String category = JdbcAnomalyReaderSupport.text(result, row, "category");
                String status = JdbcAnomalyReaderSupport.text(result, row, "status");
                String alertId = JdbcAnomalyReaderSupport.text(result, row, "alert_id");
                references.add(new AlertReference(alertId,
                        JdbcAnomalyReaderSupport.text(result, row, "building_id"),
                        JdbcAnomalyReaderSupport.text(result, row, "device_id"), category,
                        JdbcAnomalyReaderSupport.text(result, row, "risk_level"), status,
                        JdbcAnomalyReaderSupport.instant(result, row, "occurred_at"),
                        "REDACTED: " + (category == null ? "告警" : category) + " · " + (status == null ? "未知状态" : status),
                        executionRunId(alertId)));
            }
            return result.truncated()
                    ? EvidenceResult.partial(references, "RESULT_TRUNCATED")
                    : EvidenceResult.available(references);
        } catch (Exception exception) {
            return EvidenceResult.unavailable(JdbcAnomalyReaderSupport.failureCode(exception));
        }
    }

    private String executionRunId(String alertId) {
        try {
            return workflowStore.flatMap(store -> store.findByAlertId(alertId))
                    .map(WorkflowSnapshot::workflowId)
                    .orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<Breakdown> breakdown(String dimension, OperationsAnomalyQuery query) throws Exception {
        TabularResult result = execute("SELECT " + dimension + " AS key, COUNT(*) AS count FROM analytics.v_alert_fact " + FILTERS + " GROUP BY " + dimension + " ORDER BY count DESC, key ASC LIMIT 50", query);
        return result.rows().stream().map(row -> new Breakdown(JdbcAnomalyReaderSupport.text(result, row, "key"), JdbcAnomalyReaderSupport.longValue(result, row, "count"))).toList();
    }

    private List<BuildingSummary> buildings(OperationsAnomalyQuery query) throws Exception {
        TabularResult result = execute("SELECT building_id, COUNT(*) AS alert_count, COUNT(*) FILTER (WHERE risk_level = 'HIGH') AS high_risk_alert_count FROM analytics.v_alert_fact " + FILTERS + " GROUP BY building_id ORDER BY alert_count DESC, building_id ASC LIMIT 50", query);
        return result.rows().stream().map(row -> new BuildingSummary(JdbcAnomalyReaderSupport.text(result, row, "building_id"), JdbcAnomalyReaderSupport.longValue(result, row, "alert_count"), JdbcAnomalyReaderSupport.longValue(result, row, "high_risk_alert_count"))).sorted(Comparator.comparingLong(BuildingSummary::alertCount).reversed().thenComparing(BuildingSummary::buildingId)).toList();
    }

    private TabularResult execute(String sql, OperationsAnomalyQuery query) throws Exception {
        return JdbcAnomalyReaderSupport.execute(executor, sql, query);
    }
}
