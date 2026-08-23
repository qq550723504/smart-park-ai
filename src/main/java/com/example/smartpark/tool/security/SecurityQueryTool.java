package com.example.smartpark.tool.security;

import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.security.SecurityPort;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled", havingValue = "true", matchIfMissing = true)
public class SecurityQueryTool {

    private static final String MOCK_NOTICE = "Mock redacted security data only. No raw media, identity record, or device control is available.";

    private final SecurityPort securityPort;

    public SecurityQueryTool(SecurityPort securityPort) {
        this.securityPort = Objects.requireNonNull(securityPort, "securityPort");
    }

    @Tool(name = "lookupSecurityEvent", description = "Look up a redacted security event summary by event ID. Returns no raw video, image, biometric, identity, or access-control payload. Never invent security evidence.")
    public SecurityLookupResult lookupSecurityEvent(String eventId) {
        String normalizedEventId = normalize(eventId);
        if (normalizedEventId.isEmpty()) {
            return SecurityLookupResult.error(normalizedEventId, "eventId must not be blank");
        }
        try {
            return SecurityLookupResult.success(normalizedEventId, securityPort.getEvent(normalizedEventId));
        }
        catch (IllegalArgumentException ex) {
            return SecurityLookupResult.error(normalizedEventId, ex.getMessage());
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
            SecurityEvent event,
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
            return new SecurityLookupResult(eventId, Objects.requireNonNull(event, "event"), null, MOCK_NOTICE);
        }

        private static SecurityLookupResult error(String eventId, String error) {
            return new SecurityLookupResult(eventId, null, requireText(error, "error"), MOCK_NOTICE);
        }
    }
}
