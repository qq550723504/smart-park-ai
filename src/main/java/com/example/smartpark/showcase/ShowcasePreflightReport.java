package com.example.smartpark.showcase;

import java.time.Instant;
import java.util.List;

public record ShowcasePreflightReport(
        Instant startedAt,
        Instant completedAt,
        List<ShowcasePreflightResult> results) {

    public ShowcasePreflightReport {
        results = List.copyOf(results);
    }
}
