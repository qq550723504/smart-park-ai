package com.example.smartpark.analytics.model;

import java.util.List;
import java.util.Objects;

/**
 * Safe tabular output of an executed analysis query: column labels, bounded
 * rows, truncation flag and wall-clock duration. Never carries connection info.
 */
public record TabularResult(
        List<String> columnNames,
        List<List<Object>> rows,
        boolean truncated,
        long durationMs) {

    public TabularResult {
        columnNames = List.copyOf(Objects.requireNonNull(columnNames, "columnNames"));
        rows = Objects.requireNonNull(rows, "rows").stream()
                .map(row -> (List<Object>) List.copyOf(Objects.requireNonNull(row, "row")))
                .toList();
    }

    public int rowCount() {
        return rows.size();
    }
}
