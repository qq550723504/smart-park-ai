package com.example.smartpark.analytics.report;

import java.util.Objects;

/** Immutable server-owned definition for one daily report section. */
public record OperationsReportSection(String id, String title, String question) {

    public OperationsReportSection {
        id = requireText(id, "id");
        title = requireText(title, "title");
        question = requireText(question, "question");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
