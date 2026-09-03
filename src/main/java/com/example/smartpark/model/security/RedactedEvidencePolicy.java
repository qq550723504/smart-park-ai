package com.example.smartpark.model.security;

import java.util.List;
import java.util.Locale;

public final class RedactedEvidencePolicy {
    private static final String REDACTED_PREFIX = "REDACTED:";
    private static final int MAX_SUMMARY_LENGTH = 512;
    private static final List<String> FORBIDDEN_MARKERS = List.of(
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

    private RedactedEvidencePolicy() {
    }

    public static String require(String value, String fieldName) {
        String normalized = requireText(value, fieldName);
        if (!normalized.startsWith(REDACTED_PREFIX)) {
            throw new IllegalArgumentException(fieldName + " must start with " + REDACTED_PREFIX);
        }
        if (normalized.length() == REDACTED_PREFIX.length()) {
            throw new IllegalArgumentException(fieldName + " must include a summary after " + REDACTED_PREFIX);
        }
        if (normalized.length() > MAX_SUMMARY_LENGTH) {
            throw new IllegalArgumentException(fieldName + " exceeds the maximum length");
        }

        String searchable = normalized.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_MARKERS.stream().anyMatch(searchable::contains)) {
            throw new IllegalArgumentException(fieldName + " contains a raw payload marker");
        }
        return normalized;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
