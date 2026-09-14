package com.example.smartpark.model.security;

/**
 * Review/disposition outcome for a security event or incident. "False positive
 * filtering" is a disposition decision, never a confidence threshold.
 */
public enum SecurityDisposition {
    UNREVIEWED,
    CONFIRMED_INCIDENT,
    FALSE_POSITIVE,
    INCONCLUSIVE,
    DUPLICATE
}
