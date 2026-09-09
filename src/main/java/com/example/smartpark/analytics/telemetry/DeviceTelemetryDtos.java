package com.example.smartpark.analytics.telemetry;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Mirrors the governed energy time-series semantics without fabricating missing values. */
public final class DeviceTelemetryDtos {
    private DeviceTelemetryDtos() {}

    public enum Status { AVAILABLE, PARTIAL, UNAVAILABLE }
    public enum Freshness { FRESH, STALE, UNKNOWN }

    public record Window(Instant from, Instant to, DeviceTelemetryQuery.Granularity granularity) {}
    public record Point(Instant timestamp, BigDecimal value, String quality) {}
    public record Threshold(BigDecimal attentionAbove, BigDecimal criticalAbove, String source) {}

    public record Series(String deviceId, String buildingId, String deviceType, List<Point> points,
                         List<Instant> missingTimestamps, Freshness freshness) {
        public Series {
            points = List.copyOf(points == null ? List.of() : points);
            missingTimestamps = List.copyOf(missingTimestamps == null ? List.of() : missingTimestamps);
        }
    }

    /** Safe provenance. It intentionally excludes relation names and connection configuration. */
    public record Source(String system, String telemetryType, Status status, String datasetKind) {}

    public record Evidence(String deviceId, int expectedPointCount, int actualPointCount,
                           Instant firstObservedAt, Instant lastObservedAt) {}

    public record Response(String telemetryType, String unit, String timezone, Window window, Status status,
                           List<Series> series, Threshold threshold, Instant asOf,
                           Source source, List<Evidence> evidence) {
        public Response {
            series = List.copyOf(series == null ? List.of() : series);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }
}
