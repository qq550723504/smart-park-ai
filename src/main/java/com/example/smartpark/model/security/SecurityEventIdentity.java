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
    /** Number of length-prefixed parts in {@link #material()} (type, source id, event id). */
    private static final int IDENTITY_PARTS = 3;
    /** Number of length-prefixed parts {@link #reference()} adds for the event location. */
    private static final int LOCATION_PARTS = 2;

    public SecurityEventIdentity {
        source = source == null ? SecuritySourceRef.unknown() : source;
        eventId = requireText(eventId, "eventId");
        if (mimicsQualifiedReference(eventId)) {
            throw new IllegalArgumentException(
                    "eventId must not mimic a source-qualified reference: " + eventId);
        }
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
    }

    /**
     * True when a bare event id would be decoded as the body of a source-qualified
     * reference. {@link #reference()} emits the source-less form as
     * {@code security-event:} plus the bare id, so such an id cannot round-trip and
     * must be rejected rather than silently resolving a different concrete source.
     */
    static boolean mimicsQualifiedReference(String eventId) {
        if (eventId == null || !eventId.startsWith(SOURCE_REFERENCE_PREFIX)) return false;
        return decodeParts(eventId.substring(SOURCE_REFERENCE_PREFIX.length()), IDENTITY_PARTS) != null;
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
     * with the delimiter-safe material plus the event location so a source that
     * reuses a local id across parks or buildings still has a unique reference; a
     * source-less identity keeps the legacy bare form.
     */
    public String reference() {
        if (isSourceLess()) return legacyReference(eventId);
        return REFERENCE_PREFIX + SOURCE_REFERENCE_PREFIX + material()
                + ":" + encode(parkId) + ":" + encode(buildingId);
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
     * True when {@code token} is a source-qualified reference. A token that only looks
     * qualified but does not decode is a legacy bare event id, matching
     * {@link #eventIdOfReference(String)}.
     */
    public static boolean isQualifiedReference(String token) {
        if (!isReference(token)) return false;
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        return body.startsWith(SOURCE_REFERENCE_PREFIX)
                && decodeParts(body.substring(SOURCE_REFERENCE_PREFIX.length()), IDENTITY_PARTS) != null;
    }

    /**
     * Rebuilds the identity encoded in a reference token. A location-qualified token
     * carries its own park and building, which win over the values supplied by the
     * owning alert; a legacy token that omits them falls back to those arguments. A
     * legacy bare token resolves to the source-less alias, which matches any source.
     */
    public static SecurityEventIdentity fromReference(String token, String parkId, String buildingId) {
        if (!isReference(token)) throw new IllegalArgumentException("not a security event reference: " + token);
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        if (body.isEmpty()) throw new IllegalArgumentException("security event reference must not be blank");
        if (body.startsWith(SOURCE_REFERENCE_PREFIX)) {
            String material = body.substring(SOURCE_REFERENCE_PREFIX.length());
            DecodedMaterial decoded = decodeParts(material, IDENTITY_PARTS);
            if (decoded != null) {
                List<String> parts = decoded.parts();
                if (hasBlankPart(parts)) {
                    throw new IllegalArgumentException(
                            "security event reference has a blank component: " + token);
                }
                SecuritySourceType type = SecuritySourceType.fromName(parts.get(0));
                // reference() never encodes a source-less (UNKNOWN) source, so a qualified
                // token that names an unknown or misspelled type is malformed. Reject it
                // instead of downgrading it to a source-less wildcard that would resolve an
                // unrelated source's event through the legacy-alias branch.
                if (type == SecuritySourceType.UNKNOWN) {
                    throw new IllegalArgumentException(
                            "security event reference must name a known source type: " + token);
                }
                if (hasMalformedLocation(material, decoded.consumed())) {
                    throw new IllegalArgumentException(
                            "security event reference has a malformed location: " + token);
                }
                DecodedMaterial location = decodeLocation(material, decoded.consumed());
                if (location != null && hasBlankPart(location.parts())) {
                    throw new IllegalArgumentException(
                            "security event reference has a blank location component: " + token);
                }
                return new SecurityEventIdentity(
                        new SecuritySourceRef(type, parts.get(1)),
                        parts.get(2),
                        location == null ? parkId : location.parts().get(0),
                        location == null ? buildingId : location.parts().get(1));
            }
        }
        return new SecurityEventIdentity(SecuritySourceRef.unknown(), body, parkId, buildingId);
    }

    /**
     * A decoded source-qualified reference. {@code parkId} and {@code buildingId} are
     * {@code null} for a location-less token, which matches the referenced event at any
     * location so the caller can decide whether the remaining candidates are ambiguous.
     */
    public record QualifiedReference(SecuritySourceRef source, String eventId, String parkId, String buildingId) {
        public QualifiedReference {
            Objects.requireNonNull(source, "source");
            eventId = requireText(eventId, "eventId");
        }

        public boolean hasLocation() {
            return parkId != null && buildingId != null;
        }

        /** True when {@code identity} is the concrete source event this reference names. */
        public boolean matches(SecurityEventIdentity identity) {
            Objects.requireNonNull(identity, "identity");
            if (identity.isSourceLess()) return false;
            if (!eventId.equals(identity.eventId()) || !source.equals(identity.source())) return false;
            return !hasLocation()
                    || (parkId.equals(identity.parkId()) && buildingId.equals(identity.buildingId()));
        }
    }

    /**
     * Decodes a source-qualified reference into its source, event id and optional location.
     * Returns {@code null} when {@code token} is not a decodable source-qualified reference,
     * including a token that names an unknown or misspelled source type. A location-less
     * token keeps its location parts {@code null} so a caller can match every location.
     */
    public static QualifiedReference parseQualifiedReference(String token) {
        if (!isReference(token)) return null;
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        if (!body.startsWith(SOURCE_REFERENCE_PREFIX)) return null;
        String material = body.substring(SOURCE_REFERENCE_PREFIX.length());
        DecodedMaterial decoded = decodeParts(material, IDENTITY_PARTS);
        if (decoded == null) return null;
        List<String> parts = decoded.parts();
        SecuritySourceType type = SecuritySourceType.fromName(parts.get(0));
        // A zero-length component decodes syntactically but cannot be a real identifier, so
        // the token is malformed and must not become a resolvable reference.
        if (hasBlankPart(parts)) return null;
        // reference() never encodes a source-less source, so an unknown or misspelled type
        // is malformed and must not be resolved as a wildcard.
        if (type == SecuritySourceType.UNKNOWN) return null;
        if (hasMalformedLocation(material, decoded.consumed())) return null;
        DecodedMaterial location = decodeLocation(material, decoded.consumed());
        if (location != null && hasBlankPart(location.parts())) return null;
        return new QualifiedReference(
                new SecuritySourceRef(type, parts.get(1)),
                parts.get(2),
                location == null ? null : location.parts().get(0),
                location == null ? null : location.parts().get(1));
    }

    /**
     * Canonical source-qualified reference for the prefix of {@code token} that obeys the
     * {@code source:length#value:length#value:length#value} grammar, including the optional
     * trailing {@code :length#park:length#building} location when present. Only the encoded
     * prefix is consumed, so trailing prose (a sentence period, a comma, another bare id)
     * is ignored, and a source or event id may contain any character such as {@code /}.
     * Returns {@code null} when {@code token} is not a decodable source-qualified reference;
     * a legacy bare reference also returns {@code null} because it carries no source.
     */
    public static String canonicalQualifiedReference(String token) {
        if (!isReference(token)) return null;
        String body = token.substring(REFERENCE_PREFIX.length()).trim();
        if (!body.startsWith(SOURCE_REFERENCE_PREFIX)) return null;
        String material = body.substring(SOURCE_REFERENCE_PREFIX.length());
        DecodedMaterial decoded = decodeParts(material, IDENTITY_PARTS);
        if (decoded == null) return null;
        List<String> parts = decoded.parts();
        // A zero-length component is not a real identifier; normalizing it would emit a
        // token that later fails construction, so treat the whole reference as malformed.
        if (hasBlankPart(parts)) return null;
        // reference() never encodes a source-less source, so an unknown or misspelled
        // type is malformed and must not be normalized into a resolvable token.
        if (SecuritySourceType.fromName(parts.get(0)) == SecuritySourceType.UNKNOWN) return null;
        if (hasMalformedLocation(material, decoded.consumed())) return null;
        StringBuilder canonical = new StringBuilder(REFERENCE_PREFIX).append(SOURCE_REFERENCE_PREFIX)
                .append(encode(parts.get(0))).append(':')
                .append(encode(parts.get(1))).append(':')
                .append(encode(parts.get(2)));
        DecodedMaterial location = decodeLocation(material, decoded.consumed());
        if (location != null) {
            if (hasBlankPart(location.parts())) return null;
            canonical.append(':').append(encode(location.parts().get(0)))
                    .append(':').append(encode(location.parts().get(1)));
        }
        return canonical.toString();
    }

    private static boolean hasBlankPart(List<String> parts) {
        return parts.stream().anyMatch(part -> part == null || part.isBlank());
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
            DecodedMaterial decoded = decodeParts(body.substring(SOURCE_REFERENCE_PREFIX.length()), IDENTITY_PARTS);
            if (decoded != null) return decoded.parts().get(2);
        }
        return body;
    }

    /**
     * Decodes the location that {@link #reference()} appends after the identity parts.
     * Returns {@code null} when {@code consumed} is not followed by a complete
     * {@code :length#park:length#building} suffix, which is the normal case for a
     * legacy token or trailing prose.
     */
    private static DecodedMaterial decodeLocation(String material, int consumed) {
        if (consumed >= material.length() || material.charAt(consumed) != ':') return null;
        return decodeParts(material.substring(consumed + 1), LOCATION_PARTS);
    }

    /**
     * True when {@code material} continues after the identity parts with something that
     * looks like a location suffix (a {@code :length#} prefix) but does not decode into
     * the required park and building. A damaged or tampered reference must not silently
     * pass as the intentionally supported location-less form, so callers reject it. Plain
     * trailing prose is not a length prefix and is left to the trailing-text tolerance.
     */
    private static boolean hasMalformedLocation(String material, int consumed) {
        if (consumed >= material.length() || material.charAt(consumed) != ':') return false;
        String remainder = material.substring(consumed + 1);
        return startsWithLengthPrefix(remainder) && decodeParts(remainder, LOCATION_PARTS) == null;
    }

    private static boolean startsWithLengthPrefix(String material) {
        int delimiter = material.indexOf('#');
        if (delimiter <= 0) return false;
        for (int index = 0; index < delimiter; index++) {
            if (!Character.isDigit(material.charAt(index))) return false;
        }
        return true;
    }

    /**
     * Decodes exactly {@code count} length-prefixed parts from the start of
     * {@code material} and ignores anything that follows. Consuming only the encoded
     * prefix is what makes a token with trailing prose resolvable without guessing
     * where a value ends.
     */
    private static DecodedMaterial decodeParts(String material, int count) {
        List<String> parts = new ArrayList<>(count);
        int index = 0;
        while (parts.size() < count) {
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
            if (parts.size() < count) {
                if (index >= material.length() || material.charAt(index) != ':') return null;
                index++;
            }
        }
        return new DecodedMaterial(List.copyOf(parts), index);
    }

    private record DecodedMaterial(List<String> parts, int consumed) {
    }

    private static String encode(String value) {
        return value.length() + "#" + value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
