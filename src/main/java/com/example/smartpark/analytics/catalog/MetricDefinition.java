package com.example.smartpark.analytics.catalog;

import java.util.Set;
import java.util.Objects;

/** Immutable metric definition; the catalog is the single source of truth for口径. */
public record MetricDefinition(
        String name,
        String displayName,
        Set<String> aliases,
        String unit,
        String sourceView,
        Set<String> allowedDimensions,
        String expression,
        int defaultLookbackDays,
        String condition) {

    public MetricDefinition {
        name = requireNonBlank(name, "name");
        displayName = requireNonBlank(displayName, "displayName");
        aliases = Set.copyOf(Objects.requireNonNullElse(aliases, Set.of()));
        unit = Objects.requireNonNull(unit, "unit");
        sourceView = requireNonBlank(sourceView, "sourceView");
        allowedDimensions = Set.copyOf(Objects.requireNonNull(allowedDimensions, "allowedDimensions"));
        expression = requireNonBlank(expression, "expression");
        if (defaultLookbackDays < 1 || defaultLookbackDays > 90) {
            throw new IllegalArgumentException("defaultLookbackDays must be 1..90");
        }
    }

    /** Convenience constructor without an extra filter condition. */
    public MetricDefinition(String name, String displayName, Set<String> aliases, String unit,
                            String sourceView, Set<String> allowedDimensions,
                            String expression, int defaultLookbackDays) {
        this(name, displayName, aliases, unit, sourceView, allowedDimensions, expression, defaultLookbackDays, null);
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
