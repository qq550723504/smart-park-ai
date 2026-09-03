package com.example.smartpark.analytics.anomaly;

import java.time.Instant;
import java.util.List;

/** Read-only energy deviation facts for operations aggregation. */
public interface EnergyAnalyticsReader {
    Snapshot read(OperationsAnomalyQuery query);

    default List<EnergyReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        return List.of();
    }

    record Snapshot(List<BuildingSummary> buildings, boolean available, String failureCode) {
        public Snapshot {
            buildings = List.copyOf(buildings == null ? List.of() : buildings);
        }

        public static Snapshot unavailable(String failureCode) {
            return new Snapshot(List.of(), false, failureCode);
        }
    }

    record BuildingSummary(
            String buildingId,
            Double deviationPct,
            Double kwh,
            Double baselineKwh,
            Instant measuredAt) {}

    record EnergyReference(
            String buildingId,
            String meterId,
            Double deviationPct,
            Double kwh,
            Double baselineKwh,
            Instant measuredAt,
            String redactedSummary,
            String executionRunId) {}
}
