package com.example.smartpark.model.security;

import java.time.Instant;
import java.util.Objects;

/**
 * Unified security domain event. Adapters map vendor-private payloads into this
 * model; the UI never depends on vendor fields. All optional metadata reflects
 * what the source actually provided and is never synthesized.
 */
public record SecurityEvent(
        String eventId,
        String parkId,
        String buildingId,
        SecurityEventType eventType,
        String rawEventType,
        SecuritySourceRef source,
        SecurityEventLocation location,
        Instant observedAt,
        Instant receivedAt,
        SecurityEventSeverity severity,
        Double confidence,
        SecurityPrivacyMetadata privacy,
        SecurityDispositionRecord disposition,
        String ingestedBy,
        String ingestVersion,
        String evidenceSummary) {

    public SecurityEvent {
        eventId = requireText(eventId, "eventId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        eventType = Objects.requireNonNull(eventType, "eventType");
        rawEventType = requireText(rawEventType, "rawEventType");
        source = source == null ? SecuritySourceRef.unknown() : source;
        location = location == null ? SecurityEventLocation.empty() : location;
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        receivedAt = receivedAt == null ? observedAt : receivedAt;
        severity = severity == null ? SecurityEventSeverity.UNKNOWN : severity;
        if (confidence != null && (confidence.isNaN() || confidence < 0.0d || confidence > 1.0d)) {
            throw new IllegalArgumentException("confidence must be between 0.0 and 1.0");
        }
        privacy = privacy == null ? SecurityPrivacyMetadata.redactedOnly() : privacy;
        disposition = disposition == null ? SecurityDispositionRecord.unreviewed() : disposition;
        ingestedBy = SecurityIdentifierPolicy.optionalSafe(ingestedBy, "ingestedBy");
        ingestedBy = ingestedBy == null ? "unspecified" : ingestedBy;
        ingestVersion = SecurityIdentifierPolicy.optionalSafe(ingestVersion, "ingestVersion");
        evidenceSummary = RedactedEvidencePolicy.require(evidenceSummary, "evidenceSummary");
    }

    /**
     * Backwards-compatible constructor for simple single-source events. The raw
     * type is mapped to a standard type, severity stays {@code UNKNOWN}, and no
     * confidence is invented.
     */
    public SecurityEvent(String eventId, String parkId, String buildingId, String rawEventType,
                         Instant occurredAt, String evidenceSummary) {
        this(eventId, parkId, buildingId, SecurityEventType.fromRaw(rawEventType), rawEventType,
                SecuritySourceRef.unknown(), SecurityEventLocation.empty(), occurredAt, occurredAt,
                SecurityEventSeverity.UNKNOWN, null, SecurityPrivacyMetadata.redactedOnly(),
                SecurityDispositionRecord.unreviewed(), "unspecified", null, evidenceSummary);
    }

    /** Legacy alias: {@code occurredAt} is the observed time. */
    public Instant occurredAt() {
        return observedAt;
    }

    /**
     * Returns a copy carrying {@code replacement}. Used when reconciliation selects a
     * decision that arrived through another ingestion path, so the enriched
     * representation can keep its source, severity, confidence and ingest metadata.
     */
    public SecurityEvent withDisposition(SecurityDispositionRecord replacement) {
        return new SecurityEvent(eventId, parkId, buildingId, eventType, rawEventType, source, location, observedAt,
                receivedAt, severity, confidence, privacy, replacement, ingestedBy, ingestVersion, evidenceSummary);
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
