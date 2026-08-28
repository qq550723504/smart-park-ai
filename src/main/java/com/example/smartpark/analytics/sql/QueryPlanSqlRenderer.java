package com.example.smartpark.analytics.sql;

import com.example.smartpark.analytics.catalog.MetricDefinition;
import com.example.smartpark.analytics.model.QueryPlan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders the narrow, validated QueryPlan shape into parameterized SQL.
 * User and model text never participates in the SQL grammar; only catalog
 * expressions, catalog identifiers, and typed plan filters are rendered.
 */
public final class QueryPlanSqlRenderer {

    public String render(QueryPlan plan) {
        if (plan == null) throw new IllegalArgumentException("plan must not be null");
        Set<String> views = plan.metrics().stream()
                .map(MetricDefinition::sourceView)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (views.size() != 1) {
            throw new IllegalArgumentException("only one source view can be rendered");
        }
        String sourceView = plan.metrics().get(0).sourceView();
        String timeColumn = plan.metrics().get(0).timeColumn();
        if (plan.metrics().stream().anyMatch(metric -> !metric.timeColumn().equalsIgnoreCase(timeColumn))) {
            throw new IllegalArgumentException("metrics must share one time column");
        }
        requireIdentifier(sourceView, "source view");
        requireIdentifier(timeColumn, "time column");
        plan.dimensions().forEach(dimension -> requireIdentifier(dimension, "dimension"));

        List<String> projections = new ArrayList<>(plan.dimensions());
        for (MetricDefinition metric : plan.metrics()) {
            projections.add(metric.expression() + " AS " + metric.name());
        }
        StringBuilder sql = new StringBuilder("SELECT ")
                .append(String.join(", ", projections))
                .append(" FROM ").append(sourceView)
                .append(" WHERE ").append(timeColumn).append(" >= :fromTs")
                .append(" AND ").append(timeColumn).append(" < :toTs");

        for (var filter : plan.filters().entrySet()) {
            requireIdentifier(filter.getKey(), "filter dimension");
            sql.append(" AND ").append(filter.getKey())
                    .append(" = :").append(QueryPlan.filterParameterName(filter.getKey()));
        }
        for (String condition : uniqueConditions(plan)) {
            sql.append(" AND ").append(condition);
        }
        if (!plan.dimensions().isEmpty()) {
            sql.append(" GROUP BY ").append(String.join(", ", plan.dimensions()));
        }
        sql.append(" LIMIT ").append(plan.limit());
        return sql.toString();
    }

    private static List<String> uniqueConditions(QueryPlan plan) {
        List<String> conditions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (MetricDefinition metric : plan.metrics()) {
            if (metric.condition() != null && seen.add(metric.condition())) {
                conditions.add(metric.condition());
            }
        }
        return conditions;
    }

    private static void requireIdentifier(String identifier, String field) {
        if (identifier == null || !identifier.matches("[a-zA-Z_][a-zA-Z0-9_.]*")) {
            throw new IllegalArgumentException(field + " is not a safe catalog identifier");
        }
    }
}
