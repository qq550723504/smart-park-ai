package com.example.smartpark.model.security;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public record SecurityEvent(
        String eventId,
        String parkId,
        String buildingId,
        String eventType,
        Instant occurredAt,
        String evidenceSummary) {

    private static final String REDACTED_PREFIX = "REDACTED:";
    private static final int MAX_EVIDENCE_SUMMARY_LENGTH = 512;
    private static final List<String> FORBIDDEN_EVIDENCE_MARKERS = List.of(
            "data:",
            "base64",
            "raw video",
            "raw image",
            "raw_video",
            "raw-image",
            "原始视频",
            "原始图片",
            "face embedding",
            "face_embedding",
            "id card bytes",
            "身份证原始数据");

    public SecurityEvent {
        eventId = requireText(eventId, "eventId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        eventType = requireText(eventType, "eventType");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        evidenceSummary = requireEvidenceSummary(evidenceSummary);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static String requireEvidenceSummary(String value) {
        String normalized = requireText(value, "evidenceSummary");
        if (!normalized.startsWith(REDACTED_PREFIX)) {
            throw new IllegalArgumentException("evidenceSummary must start with " + REDACTED_PREFIX);
        }
        if (normalized.length() == REDACTED_PREFIX.length()) {
            throw new IllegalArgumentException("evidenceSummary must include a summary after " + REDACTED_PREFIX);
        }
        if (normalized.length() > MAX_EVIDENCE_SUMMARY_LENGTH) {
            throw new IllegalArgumentException("evidenceSummary exceeds the maximum length");
        }

        String searchable = normalized.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_EVIDENCE_MARKERS.stream().anyMatch(searchable::contains)) {
            throw new IllegalArgumentException("evidenceSummary contains a raw payload marker");
        }
        return normalized;
    }
}
