package com.example.smartpark.analytics.model;

import java.util.List;

/**
 * SQL that passed every AST policy check: bound-parameter time values,
 * whitelisted views only, bounded rows. This is the only shape the executor accepts.
 */
public record ValidatedSql(String sql, List<String> namedParameters, int maxRows) {

    public ValidatedSql {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("sql must not be blank");
        }
        namedParameters = List.copyOf(java.util.Objects.requireNonNullElse(namedParameters, List.of()));
        if (maxRows < 1 || maxRows > 500) {
            throw new IllegalArgumentException("maxRows must be 1..500");
        }
    }
}
