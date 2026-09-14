package com.example.smartpark.model.security;

/**
 * Legal provenance of a disposition decision. {@code FALSE_POSITIVE} may only
 * come from a human review or an auditable, registered model with a version and
 * evidence reference.
 */
public enum SecurityDispositionSource {
    NONE,
    HUMAN_REVIEW,
    REGISTERED_MODEL
}
