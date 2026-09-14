package com.example.smartpark.model.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Source-qualified logical identity of a security event. Two adapters can reuse
 * the same source-local {@code eventId}, so the source is part of the identity.
 * A source-less ({@code UNKNOWN}) representation is an explicit legacy alias of
 * the concrete-source copy of the same event, which lets an adapter start
 * enriching an event without splitting the incident that already tracks it.
 */
public record SecurityEventIdentity(SecuritySourceRef source, String eventId, String parkId, String buildingId) {

    /** Namespace shared by alert evidence and incident projections that reference a security event. */
    public static final String REFERENCE_PREFIX = "security-event:";
    private static final String SOURCE_REFERENCE_PREFIX = "source:";

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

    /**
     * Alert-evidence reference to this logical event. A concrete source is encoded
     * with the delimiter-safe material so the reference survives any characters in
     * the source id or event id; a source-less identity keeps the legacy bare form.
     */
    public String reference() {
        return isSourceLess()
                ? legacyReference(eventId)
                : REFERENCE_PREFIX + SOURCE_REFERENCE_PREFIX + material();
    }

    /** The source-less legacy reference, which aliases any source of the same event. */
    public static String legacyReference(String eventId) {
        return REFERENCE_PREFIX + requireText(eventId, "eventId");
    }

    /** True when {@code token} is a security event reference emitted by {@link #reference()}. */
    public static boolean isReference(String token) {
        return token != null && token.startsWith(REFERENCE_PREFIX);
    }

    /**
     * Rebuilds the identity encoded in a reference token. The location comes from
     * the owning alert, since the token only carries the event identity. A legacy
     * bare token resolves to the source-less alias, which matches any source.
     */
    public static SecurityEventIdentity fromReference(String token, String parkId, String buildingId) {
        if (!isReference(token)) throw new IllegalArgumentException("not a security event reference: " + token);
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        if (body.isEmpty()) throw new IllegalArgumentException("security event reference must not be blank");
        if (body.startsWith(SOURCE_REFERENCE_PREFIX)) {
            List<String> parts = decodeMaterial(body.substring(SOURCE_REFERENCE_PREFIX.length()));
            if (parts != null) {
                SecuritySourceType type = SecuritySourceType.fromName(parts.get(0));
                // reference() never encodes a source-less (UNKNOWN) source, so a qualified
                // token that names an unknown or misspelled type is malformed. Reject it
                // instead of downgrading it to a source-less wildcard that would resolve an
                // unrelated source's event through the legacy-alias branch.
                if (type == SecuritySourceType.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "security event reference must name a known source type: " + token);
                }
                return new SecurityEventIdentity(new SecuritySourceRef(type, parts.get(1)), parts.get(2), parkId,
                        buildingId);
            }
        }
        return new SecurityEventIdentity(SecuritySourceRef.unknown(), body, parkId, buildingId);
    }

    /**
     * Extracts the source-local event id from a reference token, accepting both the
     * legacy bare form and the source-qualified form. A token that looks qualified
     * but does not decode is treated as a legacy bare event id, so ids that merely
     * begin with {@code source:} keep resolving.
     */
    public static String eventIdOfReference(String token) {
        if (!isReference(token)) return null;
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        if (body.isEmpty()) return null;
        if (body.startsWith(SOURCE_REFERENCE_PREFIX)) {
            List<String> parts = decodeMaterial(body.substring(SOURCE_REFERENCE_PREFIX.length()));
            if (parts != null) return parts.get(2);
        }
        return body;
    }

    private static List<String> decodeMaterial(String material) {
        List<String> parts = new ArrayList<>(3);
        int index = 0;
        while (index < material.length()) {
            int delimiter = material.indexOf('#', index);
            if (delimiter <= index) return null;
            int length;
            try {
                length = Integer.parseInt(material.substring(index, delimiter));
            } catch (NumberFormatException exception) {
                return null;
            }
            int start = delimiter + 1;
            if (length < 0 || length > material.length() - start) return null;
            int end = start + length;
            parts.add(material.substring(start, end));
            index = end;
            if (index < material.length()) {
                if (material.charAt(index) != ':') return null;
                index++;
            }
        }
        return parts.size() == 3 ? parts : null;
    }

    private static String encode(String value) {
        return value.length() + "#" + value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
