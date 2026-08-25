package com.example.smartpark.analytics;

import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.catalog.MetricDefinition;

/**
 * Operator's structured clarification choice: an ambiguous natural-language
 * term resolved to one canonical catalog metric. Only the canonical name is
 * ever persisted or re-fed into the workflow.
 */
public record MetricSelection(String term, String metric) {

    public MetricSelection {
        if (term == null || term.isBlank()) {
            throw new IllegalArgumentException("term must not be blank");
        }
        if (metric == null || metric.isBlank()) {
            throw new IllegalArgumentException("metric must not be blank");
        }
    }

    MetricDefinition validateAgainst(MetricCatalog catalog) {
        return catalog.findByName(metric)
                .orElseThrow(() -> new IllegalArgumentException("指标 “" + metric + "” 不在指标目录中"));
    }
}
