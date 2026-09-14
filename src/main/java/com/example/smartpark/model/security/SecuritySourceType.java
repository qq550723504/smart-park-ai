package com.example.smartpark.model.security;

/**
 * Category of the source that produced a security event. The UI must not depend
 * on vendor-private fields, only on this normalized category.
 */
public enum SecuritySourceType {
    CAMERA_ANALYTICS,
    ACCESS_CONTROL,
    EXISTING_FEED,
    UNKNOWN
}
