package com.example.smartpark.securityincident;

import java.time.Instant;
import java.util.Objects;

public record SecurityIncidentTimelineEntry(String sourceType, String sourceId, Instant occurredAt, String label) {
    public SecurityIncidentTimelineEntry {
        sourceType = requireText(sourceType, "sourceType");
        sourceId = requireText(sourceId, "sourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        label = requireText(label, "label");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
