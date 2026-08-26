package com.example.smartpark.analytics.model;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.catalog.CategoricalFilterVocabulary;

import java.time.Instant;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;

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
        int limit,
        TimeRangeSource timeRangeSource) {

    public QueryPlan(String question, List<MetricDefinition> metrics, List<String> dimensions,
                     Map<String, String> filters, TimeRange timeRange, int limit) {
        this(question, metrics, dimensions, filters, timeRange, limit,
                TimeRangeSource.DEFAULT_METRIC_LOOKBACK);
    }

    public QueryPlan {
        Objects.requireNonNull(question, "question");
        if (question.isBlank()) {
            throw new IllegalArgumentException("question must not be blank");
        }
        metrics = List.copyOf(Objects.requireNonNull(metrics, "metrics"));
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("plan requires at least one metric");
        }
        LinkedHashSet<String> normalizedDimensions = new LinkedHashSet<>();
        for (String dimension : Objects.requireNonNullElse(dimensions, List.<String>of())) {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("dimension must not be blank");
            }
            String normalized = dimension.strip().toLowerCase(Locale.ROOT);
            boolean allowedByEveryMetric = metrics.stream().allMatch(metric ->
                    metric.allowedDimensions().stream().anyMatch(value -> value.equalsIgnoreCase(normalized)));
            if (!allowedByEveryMetric) {
                throw new IllegalArgumentException("dimension is not approved by every metric: " + dimension);
            }
            normalizedDimensions.add(normalized);
        }
        dimensions = List.copyOf(normalizedDimensions);
        LinkedHashMap<String, String> normalizedFilters = new LinkedHashMap<>();
        for (var filter : Objects.requireNonNullElse(filters, Map.<String, String>of()).entrySet()) {
            if (filter.getKey() == null || filter.getKey().isBlank()
                    || filter.getValue() == null || filter.getValue().isBlank()) {
                throw new IllegalArgumentException("filter dimension and value must not be blank");
            }
            String dimension = filter.getKey().strip().toLowerCase(Locale.ROOT);
            if (!dimension.matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException("filter dimension is not a supported catalog identifier");
            }
            boolean allowedByEveryMetric = metrics.stream().allMatch(metric ->
                    metric.allowedDimensions().stream().anyMatch(value -> value.equalsIgnoreCase(dimension)));
            if (!allowedByEveryMetric) {
                throw new IllegalArgumentException("filter dimension is not approved by every metric: " + dimension);
            }
            String value = filter.getValue().strip();
            if (!isFilterValueCompatible(dimension, value)) {
                throw new IllegalArgumentException("filter value is incompatible with dimension "
                        + dimension + ": " + value);
            }
            if (!valueAppearsInQuestion(question, dimension, value)) {
                throw new IllegalArgumentException("filter value must appear in the original question: " + value);
            }
            if (normalizedFilters.putIfAbsent(dimension, value) != null) {
                throw new IllegalArgumentException("duplicate filter dimension: " + dimension);
            }
        }
        Set<String> filterValues = normalizedFilters.values().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (QuestionTokenScanner.Token scopedEntity : QuestionTokenScanner.entityIdentifiers(question)) {
            String identifier = scopedEntity.text();
            if (!filterValues.contains(identifier)) {
                throw new IllegalArgumentException(
                        "query plan dropped entity identifier from original question: " + identifier);
            }
        }
        filters = java.util.Collections.unmodifiableMap(normalizedFilters);
        Objects.requireNonNull(timeRange, "timeRange");
        if (!timeRange.isOrdered()) {
            throw new IllegalArgumentException("timeRange.from must be before timeRange.to");
        }
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("limit must be 1..500");
        }
        Objects.requireNonNull(timeRangeSource, "timeRangeSource");
    }

    /**
     * Entity identifiers are typed at the plan boundary. Without this check a
     * model can put a building identifier into a meter predicate (both are
     * syntactically valid strings) and silently query a different scope.
     */
    private static boolean isFilterValueCompatible(String dimension, String value) {
        return switch (dimension) {
            case "building_id" -> value.matches("(?i)B\\d+");
            case "meter_id" -> value.matches("(?i)MTR-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+");
            case "device_id" -> value.matches("(?i)(?:AC|PWR|LFT|HUM|DR|CAM|DEV)-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+");
            case "alert_id" -> value.matches("(?i)ALT-[A-Za-z0-9]+(?:-[A-Za-z0-9]+)+");
            default -> true;
        };
    }

    private static boolean valueAppearsInQuestion(String question, String dimension, String value) {
        if (Set.of("status", "risk_level", "category").contains(dimension)) {
            return CategoricalFilterVocabulary.valueAppearsInQuestion(dimension, value, question);
        }
        return question.contains(value);
    }

    public static String filterParameterName(String dimension) {
        return "filter_" + dimension.toLowerCase(Locale.ROOT);
    }

    public enum TimeRangeSource {
        EXPLICIT_USER_RANGE,
        DEFAULT_METRIC_LOOKBACK
    }

    public record TimeRange(Instant from, Instant to) {
        public boolean isOrdered() {
            return from != null && to != null && from.isBefore(to);
        }
    }
}
