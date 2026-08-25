package com.example.smartpark.analytics.catalog;

import java.util.List;

/** Result of resolving a natural-language term against the governed metric catalog. */
public sealed interface MetricResolution permits MetricResolution.Resolved, MetricResolution.Ambiguous, MetricResolution.Unknown {

    record Resolved(MetricDefinition metric) implements MetricResolution {}

    record Ambiguous(String term, List<MetricDefinition> candidates) implements MetricResolution {}

    record Unknown() implements MetricResolution {}
}
