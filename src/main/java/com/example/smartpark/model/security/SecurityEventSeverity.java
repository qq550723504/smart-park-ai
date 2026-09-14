package com.example.smartpark.model.security;

import java.util.Locale;

/**
 * Source-provided severity. When a source does not provide one, the event keeps
 * {@link #UNKNOWN}; severity is never synthesized.
 */
public enum SecurityEventSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL,
    UNKNOWN;

    public static SecurityEventSeverity fromRaw(String raw) {
        if (raw == null) return UNKNOWN;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return UNKNOWN;
        return switch (normalized) {
            case "LOW" -> LOW;
            case "MEDIUM" -> MEDIUM;
            case "HIGH" -> HIGH;
            case "CRITICAL" -> CRITICAL;
            default -> UNKNOWN;
        };
    }
}
