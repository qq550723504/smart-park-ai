package com.example.smartpark.web;

import java.util.Set;

public enum DemoRole {
    VIEWER, OPERATOR, APPROVER, CUSTOMER_AGENT, ADMIN;

    static DemoRole parse(String value) {
        try {
            return value == null || value.isBlank() ? VIEWER : valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new ForbiddenOperationException("Unknown demo role");
        }
    }

    static void requireIfPresent(String value, DemoRole... allowed) {
        if (value != null && !value.isBlank()) require(value, allowed);
    }

    static void require(String value, DemoRole... allowed) {
        DemoRole actual = parse(value);
        if (!Set.of(allowed).contains(actual)) {
            throw new ForbiddenOperationException("Demo role is not allowed to perform this operation");
        }
    }
}
