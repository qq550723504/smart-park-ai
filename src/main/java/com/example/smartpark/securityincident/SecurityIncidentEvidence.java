package com.example.smartpark.securityincident;

import com.example.smartpark.model.security.RedactedEvidencePolicy;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;

import java.time.Instant;
import java.util.Objects;

public record SecurityIncidentEvidence(
        String sourceId,
        Instant occurredAt,
        String summary,
        String rawEventType,
        String sourceType,
        String eventSourceId,
        String severity,
        Double confidence) {

    public SecurityIncidentEvidence(String sourceId, Instant occurredAt, String summary) {
        this(sourceId, occurredAt, summary, null, null, null, null, null);
    }

    public SecurityIncidentEvidence {
        sourceId = requireText(sourceId, "sourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        summary = RedactedEvidencePolicy.require(summary, "summary");
        rawEventType = trimToNull(rawEventType);
        sourceType = trimToNull(sourceType);
        eventSourceId = trimToNull(eventSourceId);
        severity = trimToNull(severity);
        if (confidence != null && (confidence.isNaN() || confidence < 0.0d || confidence > 1.0d)) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    /**
     * Reconstructs the source-qualified logical identity of the evidence. A missing
     * or unrecognized source falls back to the legacy source-less alias.
     */
    public SecurityEventIdentity eventIdentity(String parkId, String buildingId) {
        SecuritySourceType type = SecuritySourceType.fromName(sourceType);
        SecuritySourceRef source = type == SecuritySourceType.UNKNOWN || eventSourceId == null
                ? SecuritySourceRef.unknown()
                : new SecuritySourceRef(type, eventSourceId);
        return new SecurityEventIdentity(source, sourceId, parkId, buildingId);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
