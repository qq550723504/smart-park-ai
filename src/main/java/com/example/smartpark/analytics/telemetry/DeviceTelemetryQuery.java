package com.example.smartpark.analytics.telemetry;

import java.time.Instant;
import java.util.List;

public record DeviceTelemetryQuery(String telemetryType, List<String> deviceIds,
                                   Instant from, Instant to, Granularity granularity) {
    public enum Granularity { HOUR }
}
