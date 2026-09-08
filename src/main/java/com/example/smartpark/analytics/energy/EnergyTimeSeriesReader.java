package com.example.smartpark.analytics.energy;

import com.example.smartpark.analytics.catalog.MetricDefinition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-only adapter boundary over the governed Operations Analytics source. */
public interface EnergyTimeSeriesReader {
    Snapshot read(Request request, MetricDefinition metric);

    record Request(List<String> buildingIds, Instant from, Instant to,
                   EnergyTimeSeriesQuery.Granularity granularity) {
        public Request {
            buildingIds = List.copyOf(buildingIds);
        }
    }

    record Row(String buildingId, Instant bucketTimestamp, BigDecimal value, Instant observedAt) {}

    record Snapshot(List<Row> rows, boolean available, boolean truncated, String failureCode) {
        public Snapshot {
            rows = List.copyOf(rows == null ? List.of() : rows);
        }

        public static Snapshot available(List<Row> rows, boolean truncated) {
            return new Snapshot(rows, true, truncated, truncated ? "RESULT_TRUNCATED" : null);
        }

        public static Snapshot unavailable(String failureCode) {
            return new Snapshot(List.of(), false, false, failureCode);
        }
    }
}
