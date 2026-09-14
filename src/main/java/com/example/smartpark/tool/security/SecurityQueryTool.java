package com.example.smartpark.tool.security;

import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventLocation;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecurityPrivacyMetadata;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.port.security.SecurityEventCatalog;
import com.example.smartpark.port.security.SecurityEventLookupException;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityEventResolver;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityQueryTool {

    private static final String REDACTED_NOTICE = "Redacted security data only. No raw media, identity record, or device control is available.";

    /**
     * Fixed public error for failures that are not a user-safe lookup problem. Adapter
     * exceptions can carry connection URLs or credential-bearing configuration labels,
     * so their messages are never echoed to the AI tool consumer.
     */
    private static final String LOOKUP_UNAVAILABLE = "Security event lookup is temporarily unavailable";

    private final SecurityEventResolver securityEvents;

    /**
     * Aggregates the legacy reader with every registered adapter, so a lookup sees
     * adapter events even in a mixed deployment where the legacy reader is the only
     * {@code SecurityPort} bean.
     */
    public SecurityQueryTool(SecurityEventReader securityEventReader,
                             List<SecuritySourceAdapter> securitySourceAdapters) {
        this.securityEvents = SecurityEventCatalog.aggregating(
                Objects.requireNonNull(securityEventReader, "securityEventReader"),
                securitySourceAdapters == null ? List.of() : securitySourceAdapters);
    }

    @Tool(name = "lookupSecurityEvent", description = "Look up a redacted security event summary by event ID. Returns no raw video, image, biometric, identity, or access-control payload. Never invent security evidence.")
    public SecurityLookupResult lookupSecurityEvent(String eventId) {
        String normalizedEventId = normalize(eventId);
        if (normalizedEventId.isEmpty()) {
            return SecurityLookupResult.error(normalizedEventId, "eventId must not be blank");
        }
        try {
            SecurityEvent event = SecurityEventIdentity.isReference(normalizedEventId)
                    ? securityEvents.getEventByReference(normalizedEventId)
                    : securityEvents.getEvent(normalizedEventId);
            return SecurityLookupResult.success(normalizedEventId, event);
        }
        catch (NoSuchElementException ex) {
            return SecurityLookupResult.error(normalizedEventId, "Unknown security event: " + normalizedEventId);
        }
        catch (SecurityEventLookupException ex) {
            return SecurityLookupResult.error(normalizedEventId, ex.getMessage());
        }
        catch (RuntimeException ex) {
            return SecurityLookupResult.error(normalizedEventId, LOOKUP_UNAVAILABLE);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    public record SecurityLookupResult(
            String eventId,
            SecurityEventSummary event,
            String error,
            String notice) {

        public SecurityLookupResult {
            eventId = normalize(eventId);
            notice = requireText(notice, "notice");
            error = error == null ? null : error.trim();
            if (error == null) {
                eventId = requireText(eventId, "eventId");
                event = Objects.requireNonNull(event, "event");
            }
            else if (event != null) {
                throw new IllegalArgumentException("error results must not include an event");
            }
        }

        private static SecurityLookupResult success(String eventId, SecurityEvent event) {
            return new SecurityLookupResult(eventId,
                    SecurityEventSummary.redacted(Objects.requireNonNull(event, "event")), null, REDACTED_NOTICE);
        }

        private static SecurityLookupResult error(String eventId, String error) {
            return new SecurityLookupResult(eventId, null, requireText(error, "error"), REDACTED_NOTICE);
        }
    }

    /**
     * Provenance-free projection of a {@link SecurityEvent} returned to the AI tool
     * consumer. The disposition is reduced to its status, source and decision time:
     * the human actor and the registered-model id/version/evidence reference are
     * identity and audit records the tool contract promises not to expose. Adapter
     * ingest metadata is omitted for the same reason.
     */
    public record SecurityEventSummary(
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
            SecurityDisposition disposition,
            SecurityDispositionSource dispositionSource,
            Instant dispositionDecidedAt,
            String evidenceSummary) {

        static SecurityEventSummary redacted(SecurityEvent event) {
            SecurityDispositionRecord record = event.disposition();
            boolean decided = record != null && record.disposition() != SecurityDisposition.UNREVIEWED;
            return new SecurityEventSummary(event.eventId(), event.parkId(), event.buildingId(), event.eventType(),
                    event.rawEventType(), event.source(), event.location(), event.observedAt(), event.receivedAt(),
                    event.severity(), event.confidence(), event.privacy(),
                    decided ? record.disposition() : SecurityDisposition.UNREVIEWED,
                    decided ? record.source() : SecurityDispositionSource.NONE,
                    decided ? record.decidedAt() : null,
                    event.evidenceSummary());
        }
    }
}
