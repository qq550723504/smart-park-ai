package com.example.smartpark.analytics.energy;

import java.time.Instant;
import java.util.List;

/** Public query intent for the governed energy time-series capability. */
public record EnergyTimeSeriesQuery(
        String metric,
        List<String> buildingIds,
        Instant from,
        Instant to,
        Granularity granularity) {

    public enum Granularity {
        HOUR,
        DAY
    }
}
