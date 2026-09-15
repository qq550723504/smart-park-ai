package com.example.smartpark.model.security;

import java.time.Instant;

/**
 * Immutable disposition decision with its legal provenance. {@code UNREVIEWED}
 * carries no decision fields; every decided disposition needs a source, a
 * {@code decidedAt}, and — for {@code FALSE_POSITIVE} — an auditable evidence
 * reference. A registered-model decision additionally needs model id/version.
 */
public record SecurityDispositionRecord(
        SecurityDisposition disposition,
        SecurityDispositionSource source,
        String actor,
        String modelId,
        String modelVersion,
        String evidenceRef,
        Instant decidedAt) {

    public SecurityDispositionRecord {
        if (disposition == null) throw new IllegalArgumentException("disposition must not be null");
        if (source == null) throw new IllegalArgumentException("source must not be null");
        actor = trimToNull(actor);
        modelId = trimToNull(modelId);
        modelVersion = trimToNull(modelVersion);
        evidenceRef = trimToNull(evidenceRef);

        if (disposition == SecurityDisposition.UNREVIEWED) {
            requireUnreviewed(source, actor, modelId, modelVersion, evidenceRef, decidedAt);
        } else {
            if (disposition == SecurityDisposition.FALSE_POSITIVE
                    && source != SecurityDispositionSource.HUMAN_REVIEW
                    && source != SecurityDispositionSource.REGISTERED_MODEL) {
                throw new IllegalArgumentException(
                        "FALSE_POSITIVE requires a human review or a registered model");
            }
            if (source == SecurityDispositionSource.NONE) {
                throw new IllegalArgumentException("decided disposition requires a review source");
            }
            if (decidedAt == null) {
                throw new IllegalArgumentException("decidedAt is required for a decided disposition");
            }
            if (source == SecurityDispositionSource.HUMAN_REVIEW) {
                if (actor == null) {
                    throw new IllegalArgumentException("actor is required for a human review");
                }
                if (modelId != null || modelVersion != null) {
                    throw new IllegalArgumentException("human review must not carry model provenance");
                }
            }
            if (source == SecurityDispositionSource.REGISTERED_MODEL) {
                if (modelId == null) throw new IllegalArgumentException("modelId is required for a registered model");
                if (modelVersion == null) throw new IllegalArgumentException("modelVersion is required for a registered model");
                if (evidenceRef == null) throw new IllegalArgumentException("evidenceRef is required for a registered model");
            }
            if (disposition == SecurityDisposition.FALSE_POSITIVE && evidenceRef == null) {
                throw new IllegalArgumentException("FALSE_POSITIVE requires an auditable evidenceRef");
            }
        }
    }

    public static SecurityDispositionRecord unreviewed() {
        return new SecurityDispositionRecord(SecurityDisposition.UNREVIEWED, SecurityDispositionSource.NONE,
                null, null, null, null, null);
    }

    private static void requireUnreviewed(SecurityDispositionSource source, String actor, String modelId,
                                          String modelVersion, String evidenceRef, Instant decidedAt) {
        if (source != SecurityDispositionSource.NONE) {
            throw new IllegalArgumentException("UNREVIEWED must not carry a decision source");
        }
        if (actor != null || modelId != null || modelVersion != null || evidenceRef != null || decidedAt != null) {
            throw new IllegalArgumentException("UNREVIEWED must not carry decision fields");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
