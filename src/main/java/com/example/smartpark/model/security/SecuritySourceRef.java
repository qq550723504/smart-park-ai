package com.example.smartpark.model.security;

import java.util.Objects;

/**
 * Normalized source reference for a security event. {@code sourceId} is a safe
 * logical identifier only and must not contain credentials or internal URLs.
 */
public record SecuritySourceRef(SecuritySourceType sourceType, String sourceId) {
    /** The only source id allowed with {@link SecuritySourceType#UNKNOWN}. */
    public static final String UNKNOWN_SOURCE_ID = "unknown";

    public SecuritySourceRef {
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = SecurityIdentifierPolicy.requireSafe(sourceId, "sourceId");
        // An UNKNOWN source carrying a real vendor id cannot be encoded in a
        // reference (reference() never emits UNKNOWN) nor distinguished from the
        // canonical source-less alias, so two such adapters would be silently
        // folded as legacy aliases of the same event id. Require the canonical id
        // so "source-less" stays a single, unambiguous representation.
        if (sourceType == SecuritySourceType.UNKNOWN && !UNKNOWN_SOURCE_ID.equals(sourceId)) {
            throw new IllegalArgumentException("sourceId must be '" + UNKNOWN_SOURCE_ID
                    + "' when sourceType is UNKNOWN: " + sourceId);
        }
    }

    public static SecuritySourceRef unknown() {
        return new SecuritySourceRef(SecuritySourceType.UNKNOWN, UNKNOWN_SOURCE_ID);
    }
}
