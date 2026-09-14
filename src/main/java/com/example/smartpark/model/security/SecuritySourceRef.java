package com.example.smartpark.model.security;

import java.util.Objects;

/**
 * Normalized source reference for a security event. {@code sourceId} is a safe
 * logical identifier only and must not contain credentials or internal URLs.
 */
public record SecuritySourceRef(SecuritySourceType sourceType, String sourceId) {
    public SecuritySourceRef {
        sourceType = Objects.requireNonNull(sourceType, "sourceType");
        sourceId = SecurityIdentifierPolicy.requireSafe(sourceId, "sourceId");
    }

    public static SecuritySourceRef unknown() {
        return new SecuritySourceRef(SecuritySourceType.UNKNOWN, "unknown");
    }
}
