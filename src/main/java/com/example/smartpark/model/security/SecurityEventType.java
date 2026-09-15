package com.example.smartpark.model.security;

import java.util.Locale;

/**
 * Standardized security event types. Vendor raw values are mapped here in the
 * adapter layer; unknown raw values fall back to {@link #UNKNOWN} and are never
 * rejected, so a new vendor code cannot silently break ingestion.
 */
public enum SecurityEventType {
    FIRE_SMOKE,
    PERIMETER_INTRUSION,
    CROWDING,
    POST_ABSENCE,
    ACCESS_ANOMALY,
    UNKNOWN;

    public static SecurityEventType fromRaw(String raw) {
        if (raw == null) return UNKNOWN;
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if (normalized.isEmpty()) return UNKNOWN;
        return switch (normalized) {
            case "FIRE_SMOKE", "FIRE", "SMOKE", "FIRE_SMOKE_DETECTED", "SMOKE_DETECTED" -> FIRE_SMOKE;
            case "PERIMETER_INTRUSION", "INTRUSION", "PERIMETER", "BOUNDARY_CROSSING", "PERIMETER_BREACH" ->
                    PERIMETER_INTRUSION;
            case "CROWDING", "CROWD", "GATHERING", "PERSON_GATHERING", "CROWD_GATHERING" -> CROWDING;
            case "POST_ABSENCE", "ABSENCE", "OFF_POST", "POST_ABANDONED", "ABSENT_FROM_POST" -> POST_ABSENCE;
            case "ACCESS_ANOMALY", "UNAUTHORIZED_ACCESS_ATTEMPT", "UNAUTHORIZED_ACCESS", "ACCESS_DENIED",
                    "ACCESS_VIOLATION" -> ACCESS_ANOMALY;
            default -> UNKNOWN;
        };
    }
}
