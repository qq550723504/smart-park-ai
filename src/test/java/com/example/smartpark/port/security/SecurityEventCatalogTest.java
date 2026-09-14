package com.example.smartpark.port.security;

import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventLocation;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecurityPrivacyMetadata;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.model.security.SecuritySourceType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityEventCatalogTest {

    private static final Instant BASE = Instant.parse("2026-09-14T08:00:00Z");
    private static final String PARK = "PARK-A";
    private static final String BUILDING = "A1";

    @Test
    void resolvesEventsFromTheLegacyReaderOnly() {
        SecurityEvent event = event("SEC-1", SecuritySourceRef.unknown(), BASE);
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(event), List.of());

        assertThat(catalog.getEvent("SEC-1")).isEqualTo(event);
        assertThat(catalog.getEvent(SecurityEventIdentity.of(event))).isEqualTo(event);
        assertThat(catalog.listEvents()).containsExactly(event);
    }

    @Test
    void resolvesAdapterEventsWhenNoLegacyReaderServesThem() {
        SecurityEvent event = event("SEC-ADAPTER", access("access-1"), BASE);
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of(adapter(event)));

        assertThat(catalog.getEvent("SEC-ADAPTER")).isEqualTo(event);
        assertThat(catalog.getEvent(SecurityEventIdentity.of(event))).isEqualTo(event);
    }

    @Test
    void keepsTwoSourcesThatReuseTheSameEventIdApart() {
        SecurityEvent access = event("SEC-DUAL", access("access-1"), BASE);
        SecurityEvent camera = event("SEC-DUAL", new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"),
                BASE.plusSeconds(60));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of(adapter(access), adapter(camera)));

        assertThat(catalog.getEvent(SecurityEventIdentity.of(access))).isEqualTo(access);
        assertThat(catalog.getEvent(SecurityEventIdentity.of(camera))).isEqualTo(camera);
    }

    @Test
    void prefersTheConcreteSourceOverTheSourceLessAlias() {
        SecurityEvent legacy = event("SEC-ALIAS", SecuritySourceRef.unknown(), BASE);
        SecurityEvent enriched = event("SEC-ALIAS", access("access-1"), BASE.plusSeconds(30));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(legacy), List.of(adapter(enriched)));

        SecurityEventIdentity sourceLess = new SecurityEventIdentity(SecuritySourceRef.unknown(), "SEC-ALIAS", PARK,
                BUILDING);

        assertThat(catalog.getEvent("SEC-ALIAS")).isEqualTo(enriched);
        assertThat(catalog.getEvent(sourceLess)).isEqualTo(enriched);
    }

    @Test
    void resolvesASourceQualifiedReferenceToTheReferencedSource() {
        SecurityEvent access = event("SEC-REF", access("access-1"), BASE);
        SecurityEvent camera = event("SEC-REF", new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"),
                BASE.plusSeconds(120));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of(adapter(camera), adapter(access)));

        SecurityEventIdentity requested = SecurityEventIdentity.fromReference(accessIdentity().reference(), PARK,
                BUILDING);

        assertThat(catalog.getEvent(requested)).isEqualTo(access);
    }

    @Test
    void failsWhenNoEventMatches() {
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of());

        assertThatThrownBy(() -> catalog.getEvent("SEC-MISSING")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> catalog.getEvent(
                new SecurityEventIdentity(SecuritySourceRef.unknown(), "SEC-MISSING", PARK, BUILDING)))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static SecurityEventIdentity accessIdentity() {
        return new SecurityEventIdentity(access("access-1"), "SEC-REF", PARK, BUILDING);
    }

    private static SecuritySourceRef access(String sourceId) {
        return new SecuritySourceRef(SecuritySourceType.ACCESS_CONTROL, sourceId);
    }

    private static SecurityEvent event(String eventId, SecuritySourceRef source, Instant receivedAt) {
        return new SecurityEvent(eventId, PARK, BUILDING, SecurityEventType.ACCESS_ANOMALY, "ACCESS", source,
                SecurityEventLocation.empty(), BASE, receivedAt, SecurityEventSeverity.UNKNOWN, null,
                SecurityPrivacyMetadata.redactedOnly(), SecurityDispositionRecord.unreviewed(), "test", null,
                "REDACTED: safe event summary");
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

    private static SecuritySourceAdapter adapter(SecurityEvent... events) {
        List<SecurityEvent> all = List.of(events);
        return new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("test-adapter", SecuritySourceType.ACCESS_CONTROL, Set.of(),
                        false);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return all;
            }
        };
    }
}
