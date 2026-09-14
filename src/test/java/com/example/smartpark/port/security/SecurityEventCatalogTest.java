package com.example.smartpark.port.security;

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
    void requiresAnExactSourceForASourceQualifiedReference() {
        SecurityEvent legacy = event("SEC-QUALIFIED", SecuritySourceRef.unknown(), BASE);
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(legacy), List.of());

        // The requested source never produced this id; the source-less legacy copy is only
        // an alias and must not be handed back as the requested source's event.
        assertThatThrownBy(() -> catalog.getEvent(
                new SecurityEventIdentity(access("access-1"), "SEC-QUALIFIED", PARK, BUILDING)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void doesNotSatisfyASourceQualifiedReferenceWithAnotherSourcesCopy() {
        SecurityEvent legacy = event("SEC-QUALIFIED", SecuritySourceRef.unknown(), BASE);
        SecurityEvent camera = event("SEC-QUALIFIED",
                new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"), BASE.plusSeconds(60));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(legacy), List.of(adapter(camera)));

        assertThatThrownBy(() -> catalog.getEvent(
                new SecurityEventIdentity(access("access-1"), "SEC-QUALIFIED", PARK, BUILDING)))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(catalog.getEvent(new SecurityEventIdentity(
                new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"), "SEC-QUALIFIED", PARK, BUILDING)))
                .isEqualTo(camera);
    }

    @Test
    void rejectsAnAmbiguousBareIdSharedByTwoSources() {
        SecurityEvent access = event("SEC-DUAL", access("access-1"), BASE);
        SecurityEvent camera = event("SEC-DUAL", new SecuritySourceRef(SecuritySourceType.CAMERA_ANALYTICS, "camera-1"),
                BASE.plusSeconds(60));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of(adapter(access), adapter(camera)));
        SecurityEventIdentity sourceLess = new SecurityEventIdentity(SecuritySourceRef.unknown(), "SEC-DUAL", PARK,
                BUILDING);

        assertThatThrownBy(() -> catalog.getEvent("SEC-DUAL"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
        assertThatThrownBy(() -> catalog.getEvent(sourceLess))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void rejectsAnAmbiguousBareIdThatCollidesAcrossBuildings() {
        SecurityEvent here = event("SEC-SPREAD", access("access-1"), BASE);
        SecurityEvent elsewhere = new SecurityEvent("SEC-SPREAD", PARK, "A2", SecurityEventType.ACCESS_ANOMALY,
                "ACCESS", access("access-1"), SecurityEventLocation.empty(), BASE, BASE.plusSeconds(30),
                SecurityEventSeverity.UNKNOWN, null, SecurityPrivacyMetadata.redactedOnly(),
                SecurityDispositionRecord.unreviewed(), "test", null, "REDACTED: safe event summary");
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(here), List.of(adapter(elsewhere)));

        assertThatThrownBy(() -> catalog.getEvent("SEC-SPREAD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ambiguous");
    }

    @Test
    void failsWhenNoEventMatches() {
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(), List.of());

        assertThatThrownBy(() -> catalog.getEvent("SEC-MISSING")).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> catalog.getEvent(
                new SecurityEventIdentity(SecuritySourceRef.unknown(), "SEC-MISSING", PARK, BUILDING)))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void attachesTheReconciledDecisionToThePreferredCatalogCopy() {
        SecurityDispositionRecord corrected = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(300));
        SecurityEvent legacy = event("SEC-DECISION", SecuritySourceRef.unknown(), BASE)
                .withDisposition(corrected);
        SecurityEvent stale = event("SEC-DECISION", access("access-1"), BASE.plusSeconds(600));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(legacy), List.of(adapter(stale)));

        SecurityEvent resolved = catalog.getEvent("SEC-DECISION");

        // The concrete, receipt-fresher copy wins representation, but the corrected decision
        // carried by the older legacy snapshot must not be lost.
        assertThat(resolved.source()).isEqualTo(access("access-1"));
        assertThat(resolved.receivedAt()).isEqualTo(BASE.plusSeconds(600));
        assertThat(resolved.disposition().disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(resolved.disposition()).isEqualTo(corrected);
    }

    @Test
    void attachesTheLatestDecisionWhenQualifiedCatalogCopiesDisagree() {
        SecurityDispositionRecord corrected = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-3", "2026.11", "evt-3",
                BASE.plusSeconds(120));
        SecurityEvent olderDecision = event("SEC-QUAL", access("access-1"), BASE).withDisposition(corrected);
        SecurityEvent newerUnreviewed = event("SEC-QUAL", access("access-1"), BASE.plusSeconds(600));
        SecurityEventCatalog catalog = new SecurityEventCatalog(reader(olderDecision),
                List.of(adapter(newerUnreviewed)));

        SecurityEvent resolved = catalog.getEvent(
                new SecurityEventIdentity(access("access-1"), "SEC-QUAL", PARK, BUILDING));

        assertThat(resolved.receivedAt()).isEqualTo(BASE.plusSeconds(600));
        assertThat(resolved.disposition()).isEqualTo(corrected);
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
