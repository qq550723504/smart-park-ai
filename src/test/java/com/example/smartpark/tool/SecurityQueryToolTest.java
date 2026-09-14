package com.example.smartpark.tool;

import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventLocation;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecurityPrivacyMetadata;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.port.security.SecuritySourceDescriptor;
import com.example.smartpark.tool.security.SecurityQueryTool;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

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

    @Test
    void doesNotLabelAdapterResolvedEventsAsMockData() {
        SecurityEvent production = sourcedEvent("SEC-PROD", SecuritySourceType.ACCESS_CONTROL, "access-prod");
        SecuritySourceAdapter productionAdapter = new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("access-prod", SecuritySourceType.ACCESS_CONTROL,
                        Set.of(SecurityEventType.ACCESS_ANOMALY), true);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return List.of(production);
            }
        };
        SecurityQueryTool tool = new SecurityQueryTool(reader(), List.of(productionAdapter));

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-PROD");

        assertThat(result.error()).isNull();
        assertThat(result.notice()).doesNotContainIgnoringCase("mock");
        assertThat(result.notice()).contains("No raw media");
    }

    @Test
    void redactsDispositionProvenanceFromTheToolResult() {
        SecurityDispositionRecord registered = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-9", "2026.09", "evt-secret",
                Instant.parse("2026-09-14T08:05:00Z"));
        SecurityEvent decided = sourcedEvent("SEC-REDACT", SecuritySourceType.ACCESS_CONTROL, "access-1")
                .withDisposition(registered);
        SecurityQueryTool tool = new SecurityQueryTool(reader(decided), List.of());

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-REDACT");

        assertThat(result.error()).isNull();
        // The redacted projection keeps the decision status/source but not the registered-model
        // id/version/evidence reference (the HTTP DTO omits these provenance fields too).
        assertThat(result.event().toString()).contains("CONFIRMED_INCIDENT", "REGISTERED_MODEL");
        assertThat(result.event().toString()).doesNotContain("model-9", "evt-secret");
    }

    @Test
    void resolvesASourceQualifiedReferenceInsteadOfGuessingASource() {
        SecurityEvent access = sourcedEvent("SEC-DUAL", SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEvent camera = sourcedEvent("SEC-DUAL", SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        SecurityQueryTool tool = new SecurityQueryTool(reader(access, camera), List.of());

        SecurityQueryTool.SecurityLookupResult ambiguous = tool.lookupSecurityEvent("SEC-DUAL");
        assertThat(ambiguous.event()).isNull();
        assertThat(ambiguous.error()).contains("ambiguous");

        SecurityQueryTool.SecurityLookupResult resolved =
                tool.lookupSecurityEvent(SecurityEventIdentity.of(access).reference());

        assertThat(resolved.error()).isNull();
        assertThat(resolved.event().source())
                .isEqualTo(new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, "access-1"));
    }

    @Test
    void replacesAdapterFailuresWithAFixedPublicError() {
        SecuritySourceAdapter failing = new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("access-prod", SecuritySourceType.ACCESS_CONTROL,
                        Set.of(SecurityEventType.ACCESS_ANOMALY), true);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                throw new IllegalArgumentException(
                        "failed to connect jdbc:postgresql://vendor:secret@db.internal:5432/feed?token=abc");
            }
        };
        SecurityQueryTool tool = new SecurityQueryTool(reader(), List.of(failing));

        SecurityQueryTool.SecurityLookupResult result = tool.lookupSecurityEvent("SEC-ACCESS-001");

        assertThat(result.event()).isNull();
        assertThat(result.error()).isEqualTo("Security event lookup is temporarily unavailable");
        assertThat(result.error()).doesNotContain("jdbc", "secret", "token", "db.internal", "postgresql");
    }

    @Test
    void startsAgainstALegacySecurityPortThatIsNotAReader() {
        SecurityEvent legacy = sourcedEvent("SEC-LEGACY-PORT", SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityPort legacyPort = eventId -> {
            if (!eventId.equals(legacy.eventId())) {
                throw new NoSuchElementException("security event not found: " + eventId);
            }
            return legacy;
        };

        new ApplicationContextRunner()
                .withUserConfiguration(SecurityQueryTool.class)
                .withBean(SecurityPort.class, () -> legacyPort)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SecurityQueryTool.class);
                    SecurityQueryTool.SecurityLookupResult result = context.getBean(SecurityQueryTool.class)
                            .lookupSecurityEvent("SEC-LEGACY-PORT");
                    assertThat(result.error()).isNull();
                    assertThat(result.event().eventId()).isEqualTo("SEC-LEGACY-PORT");
                });
    }

    @Test
    void prefersAReaderOverALegacyPortWhenBothAreRegistered() {
        SecurityEvent readerEvent = sourcedEvent("SEC-READER-WINS", SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityPort unusedLegacyPort = eventId -> {
            throw new AssertionError("legacy port must not be queried when a reader is registered");
        };

        new ApplicationContextRunner()
                .withUserConfiguration(SecurityQueryTool.class)
                .withBean(SecurityPort.class, () -> unusedLegacyPort)
                .withBean(SecurityEventReader.class, () -> reader(readerEvent))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SecurityQueryTool.class).lookupSecurityEvent("SEC-READER-WINS").event())
                            .isNotNull();
                });
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
