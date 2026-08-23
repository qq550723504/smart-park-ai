package com.example.smartpark.feedback;

import java.time.Instant;
import java.util.Objects;

public record FeedbackEntry(
        String targetType,
        String targetId,
        FeedbackRating rating,
        String actorRole,
        Instant timestamp) {
    public FeedbackEntry {
        targetType = requireText(targetType, "targetType");
        targetId = requireText(targetId, "targetId");
        rating = Objects.requireNonNull(rating, "rating");
        actorRole = requireText(actorRole, "actorRole");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value.trim();
    }
}
