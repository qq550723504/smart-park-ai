package com.example.smartpark.tool;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventLocation;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecurityPrivacyMetadata;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.tool.security.SecurityQueryTool;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityQueryToolTest {

    @Test
    void returnsOnlyTheRedactedSummaryForAKnownSecurityEvent() {
        SecurityQueryTool tool = new SecurityQueryTool(new MockParkFixture().security(), List.of());

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.error()).isNull();
        assertThat(result.event().eventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
        assertThat(result.event().rawEventType()).isEqualTo("UNAUTHORIZED_ACCESS_ATTEMPT");
        assertThat(result.event().evidenceSummary()).startsWith("REDACTED:");
        assertThat(result.event().evidenceSummary()).doesNotContain("base64", "data:image", "身份证");
        assertThat(result.notice()).contains("No raw media");
    }

    @Test
    void unknownEventReturnsSafeErrorWithoutInventingEvidence() {
        SecurityQueryTool.SecurityLookupResult result = new SecurityQueryTool(new MockParkFixture().security(), List.of())
                .lookupSecurityEvent("missing-event");

        assertThat(result.event()).isNull();
        assertThat(result.error()).contains("Unknown security event");
    }

    @Test
    void resolvesAdapterEventsWhenALegacyReaderIsAlsoRegistered() {
        SecurityEventReader emptyLegacyReader = new SecurityEventReader() {
            @Override
            public SecurityEvent getEvent(String eventId) {
                throw new NoSuchElementException("security event not found: " + eventId);
            }

            @Override
            public List<SecurityEvent> listEvents() {
                return List.of();
            }
        };
        SecurityQueryTool tool = new SecurityQueryTool(emptyLegacyReader, List.of(new MockParkFixture().security()));

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.error()).isNull();
        assertThat(result.event().eventType()).isEqualTo(SecurityEventType.ACCESS_ANOMALY);
    }

    @Test
    void rejectsAnAmbiguousBareEventIdInsteadOfGuessingASource() {
        SecurityEvent access = sourcedEvent("SEC-DUAL", SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEvent camera = sourcedEvent("SEC-DUAL", SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        SecurityQueryTool tool = new SecurityQueryTool(reader(access, camera), List.of());

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-DUAL");

        assertThat(result.event()).isNull();
        assertThat(result.error()).contains("ambiguous");
    }

    private static SecurityEvent sourcedEvent(String eventId, SecuritySourceType type, String sourceId) {
        return new SecurityEvent(eventId, "PARK-A", "A1", SecurityEventType.ACCESS_ANOMALY, "ACCESS",
                new SecuritySourceRef(type, sourceId), SecurityEventLocation.empty(),
                Instant.parse("2026-09-14T08:00:00Z"), Instant.parse("2026-09-14T08:00:00Z"),
                SecurityEventSeverity.UNKNOWN, null, SecurityPrivacyMetadata.redactedOnly(),
                SecurityDispositionRecord.unreviewed(), "test", null, "REDACTED: safe event summary");
    }

    private static SecurityEventReader reader(SecurityEvent... events) {
        List<SecurityEvent> all = List.of(events);
        return new SecurityEventReader() {
            @Override
            public SecurityEvent getEvent(String eventId) {
                return all.stream().filter(event -> event.eventId().equals(eventId)).findFirst().orElseThrow();
            }

            @Override
            public List<SecurityEvent> listEvents() {
                return all;
            }
        };
    }
}
