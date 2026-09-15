package com.example.smartpark.securityincident;

import java.time.Instant;
import java.util.Objects;

/**
 * A timeline entry. {@code sourceType} and {@code sourceId} identify the entry
 * kind (for example {@code SECURITY_EVENT} plus the event id); {@code reference}
 * carries the source-qualified security event reference when the entry is an
 * event, so consumers can key entries that reuse a source-local id.
 */
public record SecurityIncidentTimelineEntry(String sourceType, String sourceId, Instant occurredAt, String label,
                                            String reference) {

    public SecurityIncidentTimelineEntry(String sourceType, String sourceId, Instant occurredAt, String label) {
        this(sourceType, sourceId, occurredAt, label, null);
    }

    public SecurityIncidentTimelineEntry {
        sourceType = requireText(sourceType, "sourceType");
        sourceId = requireText(sourceId, "sourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        label = requireText(label, "label");
        reference = reference == null || reference.isBlank() ? null : reference.trim();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
