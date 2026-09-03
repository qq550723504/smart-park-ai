package com.example.smartpark.analytics.anomaly;

import java.time.Instant;
import java.util.List;

/** Read-only alert facts for operations aggregation; separate from AlertPort. */
public interface AlertAnalyticsReader {
    Snapshot read(OperationsAnomalyQuery query);

    default List<AlertReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        return List.of();
    }

    record Snapshot(
            long alertCount,
            long highRiskAlertCount,
            List<Breakdown> riskLevels,
            List<Breakdown> categories,
            List<Breakdown> statuses,
            List<BuildingSummary> buildings,
            boolean available,
            String failureCode) {
        public Snapshot {
            riskLevels = List.copyOf(riskLevels == null ? List.of() : riskLevels);
            categories = List.copyOf(categories == null ? List.of() : categories);
            statuses = List.copyOf(statuses == null ? List.of() : statuses);
            buildings = List.copyOf(buildings == null ? List.of() : buildings);
        }

        public static Snapshot unavailable(String failureCode) {
            return new Snapshot(0, 0, List.of(), List.of(), List.of(), List.of(), false, failureCode);
        }
    }

    record Breakdown(String key, long count) {}

    record BuildingSummary(String buildingId, long alertCount, long highRiskAlertCount) {}

    record AlertReference(
            String alertId,
            String buildingId,
            String deviceId,
            String category,
            String riskLevel,
            String status,
            Instant occurredAt,
            String redactedSummary,
            String executionRunId) {}
}
