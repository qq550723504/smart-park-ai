package com.example.smartpark.model.security;

/**
 * Category of the source that produced a security event. The UI must not depend
 * on vendor-private fields, only on this normalized category.
 */
public enum SecuritySourceType {
    CAMERA_ANALYTICS,
    ACCESS_CONTROL,
    EXISTING_FEED,
    UNKNOWN;

    /** Resolves a stored name, falling back to {@link #UNKNOWN} for unknown or missing values. */
    public static SecuritySourceType fromName(String name) {
        if (name == null || name.isBlank()) return UNKNOWN;
        try {
            return valueOf(name.trim());
        } catch (IllegalArgumentException exception) {
            return UNKNOWN;
        }
    }
}
