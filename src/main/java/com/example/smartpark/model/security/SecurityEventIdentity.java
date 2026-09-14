package com.example.smartpark.model.security;

import java.util.Objects;

/**
 * Source-qualified logical identity of a security event. Two adapters can reuse
 * the same source-local {@code eventId}, so the source is part of the identity.
 * A source-less ({@code UNKNOWN}) representation is an explicit legacy alias of
 * the concrete-source copy of the same event, which lets an adapter start
 * enriching an event without splitting the incident that already tracks it.
 */
public record SecurityEventIdentity(SecuritySourceRef source, String eventId, String parkId, String buildingId) {

    public SecurityEventIdentity {
        source = source == null ? SecuritySourceRef.unknown() : source;
        eventId = requireText(eventId, "eventId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
    }

    public static SecurityEventIdentity of(SecurityEvent event) {
        Objects.requireNonNull(event, "event");
        return new SecurityEventIdentity(event.source(), event.eventId(), event.parkId(), event.buildingId());
    }

    /**
     * True when this identity and {@code other} refer to the same logical event.
     * The comparison is symmetric: a source-less side matches any source, while
     * two concrete sources must agree on both type and identifier.
     */
    public boolean matches(SecurityEventIdentity other) {
        Objects.requireNonNull(other, "other");
        if (!eventId.equals(other.eventId) || !parkId.equals(other.parkId) || !buildingId.equals(other.buildingId)) {
            return false;
        }
        if (isSourceLess() || other.isSourceLess()) return true;
        return source.sourceType() == other.source.sourceType() && source.sourceId().equals(other.source.sourceId());
    }

    public boolean isSourceLess() {
        return source.sourceType() == SecuritySourceType.UNKNOWN;
    }

    /**
     * Delimiter-safe canonical material used to derive stable incident ids.
     * Source-less identities keep the legacy material so existing incident ids
     * stay reproducible while concrete sources are qualified.
     */
    public String material() {
        String sourceMaterial = isSourceLess()
                ? ""
                : encode(source.sourceType().name()) + ":" + encode(source.sourceId()) + ":";
        return sourceMaterial + encode(eventId);
    }

    private static String encode(String value) {
        return value.length() + "#" + value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
