package com.example.smartpark.analytics.report;

import java.util.Objects;

/** Immutable server-owned definition for one daily report section. */
public record OperationsReportSection(String id, String title, String question,
                                      String sourceSystem, String metric, String unit) {

    public OperationsReportSection(String id, String title, String question) {
        this(id, title, question, "OPERATIONS_ANALYTICS", "unknown", "unknown");
    }

    public OperationsReportSection {
        id = requireText(id, "id");
        title = requireText(title, "title");
        question = requireText(question, "question");
        sourceSystem = requireText(sourceSystem, "sourceSystem");
        metric = requireText(metric, "metric");
        unit = requireText(unit, "unit");
    }

    /** Resolves the persisted and executed question against the exact requested window. */
    public String questionFor(OperationsReportRequest request) {
        Objects.requireNonNull(request, "request");
        String metricQuestion = question.replaceFirst("^过去5天", "");
        return request.timeWindow().fromInclusive() + " 到 "
                + request.timeWindow().toExclusive() + " " + metricQuestion;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
