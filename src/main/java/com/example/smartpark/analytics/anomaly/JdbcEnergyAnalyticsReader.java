package com.example.smartpark.analytics.anomaly;

import com.example.smartpark.analytics.model.TabularResult;
import com.example.smartpark.analytics.sql.ReadOnlyQueryExecutor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class JdbcEnergyAnalyticsReader implements EnergyAnalyticsReader {
    private static final String FILTERS = "WHERE hour_ts >= :from AND hour_ts < :to AND (:buildingId IS NULL OR building_id = :buildingId)";
    private final ReadOnlyQueryExecutor executor;

    public JdbcEnergyAnalyticsReader(ReadOnlyQueryExecutor executor) {
        this.executor = java.util.Objects.requireNonNull(executor, "executor");
    }

    @Override
    public Snapshot read(OperationsAnomalyQuery query) {
        try {
            TabularResult result = execute("SELECT building_id, SUM(kwh) AS kwh, SUM(baseline_kwh) AS baseline_kwh, MAX(hour_ts) AS measured_at FROM analytics.v_energy_hourly " + FILTERS + " GROUP BY building_id ORDER BY building_id ASC LIMIT 50", query);
            List<BuildingSummary> buildings = result.rows().stream().map(row -> summary(result, row)).sorted(Comparator.comparing(BuildingSummary::buildingId)).toList();
            return new Snapshot(buildings, true, result.truncated() ? "RESULT_TRUNCATED" : null);
        } catch (Exception exception) {
            return Snapshot.unavailable(JdbcAnomalyReaderSupport.failureCode(exception));
        }
    }

    @Override
    public List<EnergyReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        if (buildingId == null || buildingId.isBlank()) return List.of();
        try {
            OperationsAnomalyQuery scoped = new OperationsAnomalyQuery(query.from(), query.to(), buildingId,
                    query.riskLevel(), query.category(), query.status(), query.deviceType());
            TabularResult result = execute("SELECT building_id, meter_id, kwh, baseline_kwh, hour_ts AS measured_at FROM analytics.v_energy_hourly " + FILTERS + " ORDER BY hour_ts DESC, meter_id ASC LIMIT 10", scoped);
            List<EnergyReference> references = new ArrayList<>();
            for (List<Object> row : result.rows()) {
                Double kwh = JdbcAnomalyReaderSupport.decimal(result, row, "kwh");
                Double baseline = JdbcAnomalyReaderSupport.decimal(result, row, "baseline_kwh");
                references.add(new EnergyReference(JdbcAnomalyReaderSupport.text(result, row, "building_id"),
                        JdbcAnomalyReaderSupport.text(result, row, "meter_id"), deviation(kwh, baseline), kwh, baseline,
                        JdbcAnomalyReaderSupport.instant(result, row, "measured_at"), "REDACTED: 能耗读数摘要", null));
            }
            return List.copyOf(references);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private BuildingSummary summary(TabularResult result, List<Object> row) {
        Double kwh = JdbcAnomalyReaderSupport.decimal(result, row, "kwh");
        Double baseline = JdbcAnomalyReaderSupport.decimal(result, row, "baseline_kwh");
        return new BuildingSummary(JdbcAnomalyReaderSupport.text(result, row, "building_id"), deviation(kwh, baseline), kwh, baseline,
                JdbcAnomalyReaderSupport.instant(result, row, "measured_at"));
    }

    private static Double deviation(Double kwh, Double baseline) {
        if (kwh == null || baseline == null || baseline == 0.0) return null;
        return Math.round((kwh - baseline) * 10000.0 / baseline) / 100.0;
    }

    private TabularResult execute(String sql, OperationsAnomalyQuery query) throws Exception {
        return JdbcAnomalyReaderSupport.execute(executor, sql, query);
    }
}
