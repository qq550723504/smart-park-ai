package com.example.smartpark.audit;

import java.time.Instant;
import java.util.Objects;

public record AuditEntry(
        String actorRole,
        String action,
        String resourceId,
        String outcome,
        Instant timestamp) {
    public AuditEntry {
        actorRole = requireText(actorRole, "actorRole");
        action = requireText(action, "action");
        resourceId = requireText(resourceId, "resourceId");
        outcome = requireText(outcome, "outcome");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
