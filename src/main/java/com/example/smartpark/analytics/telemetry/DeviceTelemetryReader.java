package com.example.smartpark.analytics.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface DeviceTelemetryReader {
    Snapshot read(Request request, TelemetryCatalog.Definition definition);

    record Request(List<String> deviceIds, Instant from, Instant to,
                   DeviceTelemetryQuery.Granularity granularity) {
        public Request { deviceIds = List.copyOf(deviceIds); }
    }

    record DeviceDescriptor(String deviceId, String buildingId, String deviceType,
                            String status, Instant snapshotAt) {}
    record Row(String deviceId, String buildingId, String deviceType, Instant bucketTimestamp,
               BigDecimal value, String quality, Instant observedAt) {}

    record Snapshot(List<DeviceDescriptor> devices, List<Row> rows, boolean available,
                    boolean truncated, String failureCode) {
        public Snapshot {
            devices = List.copyOf(devices == null ? List.of() : devices);
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
        public static Snapshot available(List<DeviceDescriptor> devices, List<Row> rows, boolean truncated) {
            return new Snapshot(devices, rows, true, truncated, truncated ? "RESULT_TRUNCATED" : null);
        }
        public static Snapshot unavailable(String failureCode) {
            return new Snapshot(List.of(), List.of(), false, false, failureCode);
        }
    }
}
