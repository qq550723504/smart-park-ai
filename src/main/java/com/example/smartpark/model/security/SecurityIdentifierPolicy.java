package com.example.smartpark.model.security;

import java.util.List;
import java.util.Locale;

/**
 * Shared identifier safety policy: security identifiers must never carry camera
 * credentials, internal URLs or tokens into the domain model or the UI.
 */
final class SecurityIdentifierPolicy {
    private static final List<String> FORBIDDEN_MARKERS = List.of(
            "://", "token", "password", "passwd", "secret", "apikey", "api_key", "credential");

    private SecurityIdentifierPolicy() {
    }

    static String requireSafe(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return optionalSafe(value, field);
    }

    static String optionalSafe(String value, String field) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty()) return null;
        String searchable = normalized.toLowerCase(Locale.ROOT);
        if (FORBIDDEN_MARKERS.stream().anyMatch(searchable::contains)) {
            throw new IllegalArgumentException(field + " must not contain credentials or URLs");
        }
        return normalized;
    }
}
