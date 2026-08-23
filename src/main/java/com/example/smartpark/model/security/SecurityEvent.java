package com.example.smartpark.model.security;

import java.time.Instant;
import java.util.Objects;

public record SecurityEvent(
        String eventId,
        String parkId,
        String buildingId,
        String eventType,
        Instant occurredAt,
        String evidenceSummary) {

    public SecurityEvent {
        eventId = requireText(eventId, "eventId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        eventType = requireText(eventType, "eventType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        evidenceSummary = requireText(evidenceSummary, "evidenceSummary");
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
