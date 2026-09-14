package com.example.smartpark.model.security;

/**
 * Privacy/redaction metadata attached to a security event. This deployment
 * never stores raw personal data or raw media, so both flags must stay false;
 * the record enforces that invariant instead of documenting it in prose only.
 */
public record SecurityPrivacyMetadata(RedactionPolicy redactionPolicy, boolean personalDataPresent, boolean mediaStored) {

    public enum RedactionPolicy {
        REDACTED_ONLY,
        AGGREGATED_ONLY
    }

    public SecurityPrivacyMetadata {
        redactionPolicy = redactionPolicy == null ? RedactionPolicy.REDACTED_ONLY : redactionPolicy;
        if (personalDataPresent) {
            throw new IllegalArgumentException("personalDataPresent must be false");
        }
        if (mediaStored) {
            throw new IllegalArgumentException("mediaStored must be false");
        }
    }

    public static SecurityPrivacyMetadata redactedOnly() {
        return new SecurityPrivacyMetadata(RedactionPolicy.REDACTED_ONLY, false, false);
    }
}
