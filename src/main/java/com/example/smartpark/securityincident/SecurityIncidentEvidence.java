package com.example.smartpark.securityincident;

import com.example.smartpark.model.security.RedactedEvidencePolicy;

import java.time.Instant;
import java.util.Objects;

public record SecurityIncidentEvidence(String sourceId, Instant occurredAt, String summary) {
    public SecurityIncidentEvidence {
        sourceId = requireText(sourceId, "sourceId");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        summary = RedactedEvidencePolicy.require(summary, "summary");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

}
