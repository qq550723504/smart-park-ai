package com.example.smartpark.securityincident;

import java.time.Instant;
import java.util.Objects;

public record SecurityIncidentEvidence(String sourceId, Instant occurredAt, String summary) {
    public SecurityIncidentEvidence {
        sourceId = requireText(sourceId, "sourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        summary = requireRedacted(summary);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    private static String requireRedacted(String value) {
        String normalized = requireText(value, "summary");
        if (!normalized.startsWith("REDACTED:")) {
            throw new IllegalArgumentException("summary must start with REDACTED:");
        }
        return normalized;
    }
}
