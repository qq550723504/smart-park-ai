package com.example.smartpark.analytics.energy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Stable, source-truthful projection for dashboard and Agent consumers. */
public final class EnergyTimeSeriesDtos {
    private EnergyTimeSeriesDtos() {}

    public enum Status {
        AVAILABLE,
        PARTIAL,
        UNAVAILABLE
    }

    public record Window(Instant from, Instant to, EnergyTimeSeriesQuery.Granularity granularity) {}

    public record Point(Instant timestamp, BigDecimal value) {}

    public record Series(String buildingId, List<Point> points, List<Instant> missingTimestamps) {
        public Series {
            points = List.copyOf(points == null ? List.of() : points);
            missingTimestamps = List.copyOf(missingTimestamps == null ? List.of() : missingTimestamps);
        }
    }

    /** Safe provenance: intentionally omits relation names, SQL and connection details. */
    public record Source(String system, String metricDefinition, Status status) {}

    public record Evidence(String buildingId, int expectedPointCount, int actualPointCount,
                           Instant firstObservedAt, Instant lastObservedAt) {}

    public record Response(String metric, String unit, String timezone, Window window, Status status,
                           List<Series> series, Instant asOf, Source source, List<Evidence> evidence) {
        public Response {
            series = List.copyOf(series == null ? List.of() : series);
            evidence = List.copyOf(evidence == null ? List.of() : evidence);
        }
    }
}
