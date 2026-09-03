package com.example.smartpark.analytics.anomaly;

import java.time.Instant;
import java.util.List;

/** Read-only device snapshot facts for operations aggregation. */
public interface DeviceAnalyticsReader {
    Snapshot read(OperationsAnomalyQuery query);

    default EvidenceResult<DeviceReference> evidence(String buildingId, OperationsAnomalyQuery query) {
        return EvidenceResult.available(List.of());
    }

    record Snapshot(
            long offlineDeviceCount,
            List<AlertAnalyticsReader.Breakdown> deviceTypes,
            List<BuildingSummary> buildings,
            Instant asOf,
            boolean available,
            String failureCode) {
        public Snapshot {
            deviceTypes = List.copyOf(deviceTypes == null ? List.of() : deviceTypes);
            buildings = List.copyOf(buildings == null ? List.of() : buildings);
        }

        public static Snapshot unavailable(String failureCode) {
            return new Snapshot(0, List.of(), List.of(), null, false, failureCode);
        }
    }

    record BuildingSummary(String buildingId, long offlineDeviceCount) {}

    record DeviceReference(
            String deviceId,
            String buildingId,
            String deviceType,
            String status,
            Instant snapshotAt,
            long openAlertCount,
            String redactedSummary,
            String executionRunId) {}
}
