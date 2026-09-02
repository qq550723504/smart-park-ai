package com.example.smartpark.port.collaboration;

import java.time.Instant;
import java.util.Objects;

public record SecurityIncidentHandoff(String workItemId, String incidentId, Instant createdAt) {
    public SecurityIncidentHandoff {
        workItemId = requireText(workItemId, "workItemId");
        incidentId = requireText(incidentId, "incidentId");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
