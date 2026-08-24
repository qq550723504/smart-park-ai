package com.example.smartpark.analytics.model;

import com.example.smartpark.analytics.catalog.MetricDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Structured analysis plan. The limit is bounded here so no downstream stage
 * can widen it; time ranges are absolute instants bound as parameters.
 */
public record QueryPlan(
        String question,
        List<MetricDefinition> metrics,
        List<String> dimensions,
        Map<String, String> filters,
        TimeRange timeRange,
        int limit) {

    public QueryPlan {
        Objects.requireNonNull(question, "question");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("plan requires at least one metric");
        }
        dimensions = List.copyOf(Objects.requireNonNullElse(dimensions, List.of()));
        filters = Map.copyOf(Objects.requireNonNullElse(filters, Map.of()));
        Objects.requireNonNull(timeRange, "timeRange");
        if (!timeRange.isOrdered()) {
            throw new IllegalArgumentException("timeRange.from must be before timeRange.to");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be 1..500");
        }
    }

    public record TimeRange(Instant from, Instant to) {
        public boolean isOrdered() {
            return from != null && to != null && from.isBefore(to);
        }
    }
}
