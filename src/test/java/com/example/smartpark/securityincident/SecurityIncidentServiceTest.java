package com.example.smartpark.securityincident;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventSeverity;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.port.security.SecuritySourceDescriptor;
import com.example.smartpark.model.security.SecurityEventType;
import com.example.smartpark.model.security.SecuritySourceType;
import com.example.smartpark.model.security.SecuritySourceRef;
import com.example.smartpark.collaborationcenter.SecurityIncidentHandoffStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityIncidentServiceTest {

    private static final Instant BASE = Instant.parse("2026-09-02T08:00:00Z");

    @Test
    void groupsSameAreaAndTypeWithinFifteenMinutesButSplitsTheNextWindow() {
        SecurityIncidentService service = service(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(15 * 60)),
                event("SEC-3", "A1", "ACCESS", BASE.plusSeconds(30 * 60 + 1))));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).eventIds()).containsExactly("SEC-3");
        assertThat(page.items().get(1).eventIds()).containsExactly("SEC-1", "SEC-2");
    }

    @Test
    void correlatesEventsContributedByRegisteredSourceAdapters() {
        SecuritySourceAdapter adapter = adapterReturning(event("SEC-ADAPTER", "A1", "ACCESS", BASE));
        SecurityIncidentService service = service(List.of(), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapter));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement()
                .satisfies(incident -> assertThat(incident.eventIds()).containsExactly("SEC-ADAPTER"));
    }

    @Test
    void deduplicatesEventsSharedByTheReaderAndASourceAdapter() {
        SecurityEvent shared = event("SEC-SHARED", "A1", "ACCESS", BASE);
        SecurityIncidentService service = service(List.of(shared), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(shared)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement()
                .satisfies(incident -> assertThat(incident.eventIds()).containsExactly("SEC-SHARED"));
    }

    @Test
    void deduplicatesLogicallyIdenticalEventsAndKeepsTheClassifiedRepresentation() {
        SecurityEvent readerCopy = event("SEC-ENRICHED", "A1", "ACCESS", BASE);
        SecurityDispositionRecord registered = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(30));
        SecurityEvent adapterCopy = enrichedEvent(readerCopy, registered, BASE.minusSeconds(5),
                SecurityEventSeverity.HIGH);
        SecurityIncidentService service = service(List.of(readerCopy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(adapterCopy)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement().satisfies(incident -> {
            assertThat(incident.eventIds()).containsExactly("SEC-ENRICHED");
            assertThat(incident.evidence()).singleElement()
                    .satisfies(evidence -> assertThat(evidence.severity()).isEqualTo("HIGH"));
            assertThat(incident.timeline())
                    .filteredOn(entry -> entry.sourceType().equals("SECURITY_EVENT")).hasSize(1);
            assertThat(incident.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
            assertThat(incident.dispositionRecord()).isEqualTo(registered);
        });
    }

    @Test
    void prefersTheNewestDecisionWhenBothIngestionPathsDeliverDecidedRecords() {
        SecurityDispositionRecord stale = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(10));
        SecurityDispositionRecord correction = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(60));
        SecurityEvent readerCopy = eventWithDisposition(
                enrichedEvent(event("SEC-CORRECT", "A1", "ACCESS", BASE), stale, BASE.plusSeconds(120),
                        SecurityEventSeverity.HIGH),
                stale);
        SecurityEvent adapterCopy = withSource(
                enrichedEvent(event("SEC-CORRECT", "A1", "ACCESS", BASE), correction, BASE,
                        SecurityEventSeverity.HIGH),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityIncidentService service = service(List.of(readerCopy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(adapterCopy)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement().satisfies(incident -> {
            assertThat(incident.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
            assertThat(incident.dispositionRecord()).isEqualTo(correction);
        });
    }

    @Test
    void prefersTheEnrichedRepresentationWhenBothIngestionPathsAgreeOnTheDecision() {
        SecurityDispositionRecord shared = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(5));
        SecurityEvent readerCopy = eventWithDisposition(
                enrichedEvent(event("SEC-AGREE", "A1", "ACCESS", BASE), shared, BASE,
                        SecurityEventSeverity.LOW),
                shared);
        SecurityEvent adapterCopy = withSource(
                enrichedEvent(event("SEC-AGREE", "A1", "ACCESS", BASE), shared, BASE,
                        SecurityEventSeverity.HIGH),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityIncidentService service = service(List.of(readerCopy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(adapterCopy)));

        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(incident.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(incident.dispositionRecord()).isEqualTo(shared);
        assertThat(incident.evidence()).singleElement().satisfies(evidence -> {
            assertThat(evidence.severity()).isEqualTo("HIGH");
            assertThat(evidence.eventSourceId()).isEqualTo("access-1");
        });
    }

    @Test
    void prefersTheFreshestRepresentationWhenNoDispositionIsAvailable() {
        SecurityEvent readerCopy = event("SEC-FRESH", "A1", "ACCESS", BASE);
        SecurityEvent adapterCopy = enrichedEvent(readerCopy, SecurityDispositionRecord.unreviewed(),
                BASE.plusSeconds(5), SecurityEventSeverity.HIGH);
        SecurityIncidentService service = service(List.of(readerCopy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(adapterCopy)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement()
                .satisfies(incident -> {
                    assertThat(incident.eventIds()).containsExactly("SEC-FRESH");
                    assertThat(incident.evidence()).singleElement()
                            .satisfies(evidence -> assertThat(evidence.severity()).isEqualTo("HIGH"));
                });
    }

    @Test
    void keepsEventsFromDifferentSourcesThatReuseTheSameSourceLocalId() {
        SecurityEvent access = withSource(event("SEC-DUAL", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEvent camera = withSource(event("SEC-DUAL", "A1", "ACCESS", BASE),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        SecurityIncidentService service = service(List.of(), List.of(), 50,
                new SecurityIncidentHandoffStore(10),
                List.of(adapterReturning(access), adapterReturning(camera)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement().satisfies(incident -> {
            assertThat(incident.evidence()).hasSize(2);
            assertThat(incident.evidence()).extracting(SecurityIncidentEvidence::eventSourceId)
                    .containsExactlyInAnyOrder("access-1", "camera-1");
        });
    }

    @Test
    void prefersTheEnrichedAdapterCopyWhenTimestampsTie() {
        SecurityEvent readerCopy = event("SEC-TIE", "A1", "ACCESS", BASE);
        SecurityEvent adapterCopy = withSource(
                enrichedEvent(readerCopy, SecurityDispositionRecord.unreviewed(), BASE, SecurityEventSeverity.HIGH),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityIncidentService service = service(List.of(readerCopy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(adapterCopy)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement()
                .satisfies(incident -> assertThat(incident.evidence()).singleElement()
                        .satisfies(evidence -> {
                            assertThat(evidence.severity()).isEqualTo("HIGH");
                            assertThat(evidence.eventSourceId()).isEqualTo("access-1");
                        }));
    }

    @Test
    void keepsDispositionsOfDifferentSourcesThatReuseTheSameEventIdApart() {
        SecurityEvent access = withSource(event("SEC-DUAL-POLL", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEvent camera = withSource(event("SEC-DUAL-POLL", "A1", "ACCESS", BASE),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        List<SecurityEvent> polled = new java.util.ArrayList<>(List.of(access));
        SecurityIncidentService service = service(List.of(), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterBackedBy(polled)));

        SecurityIncident reviewed = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(reviewed.incidentId(), SecurityDisposition.FALSE_POSITIVE, "APPROVER");
        service.handoff(reviewed.incidentId());

        polled.clear();
        polled.add(camera);
        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement().satisfies(incident -> {
            assertThat(incident.incidentId()).isNotEqualTo(reviewed.incidentId());
            assertThat(incident.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);
            assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.OPEN);
            assertThat(incident.handoffWorkItemId()).isNull();
        });
    }

    @Test
    void resolvesALegacyAliasToAtMostOneConcreteSource() {
        List<SecurityEvent> polled = new ArrayList<>(List.of(event("SEC-LEGACY-DUAL", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(List.of(), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterBackedBy(polled)));
        SecurityIncident reviewed = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(reviewed.incidentId(), SecurityDisposition.FALSE_POSITIVE, "APPROVER");
        service.handoff(reviewed.incidentId());

        polled.clear();
        polled.add(withSource(event("SEC-LEGACY-DUAL", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1"));
        polled.add(withSource(event("SEC-LEGACY-DUAL", "A1", "ACCESS", BASE.plusSeconds(20 * 60)),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1"));

        List<SecurityIncident> incidents = service.list(new SecurityIncidentQuery(null, 20)).items();

        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "access-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
                    assertThat(incident.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
                });
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "camera-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.OPEN);
                    assertThat(incident.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);
                    assertThat(incident.handoffWorkItemId()).isNull();
                });
    }

    @Test
    void prefersTheAliasCopyWithTheMatchingOccurrenceTimeOverAnEarlierUnrelatedSource() {
        List<SecurityEvent> polled = new ArrayList<>(List.of(event("SEC-LEGACY-ORDER", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(List.of(), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterBackedBy(polled)));
        SecurityIncident reviewed = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(reviewed.incidentId(), SecurityDisposition.FALSE_POSITIVE, "APPROVER");
        service.handoff(reviewed.incidentId());

        polled.clear();
        polled.add(withSource(event("SEC-LEGACY-ORDER", "A1", "ACCESS", BASE.minusSeconds(20 * 60)),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1"));
        polled.add(withSource(event("SEC-LEGACY-ORDER", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1"));

        List<SecurityIncident> incidents = service.list(new SecurityIncidentQuery(null, 20)).items();

        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "access-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
                    assertThat(incident.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
                });
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "camera-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.OPEN);
                    assertThat(incident.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);
                    assertThat(incident.handoffWorkItemId()).isNull();
                });
    }

    @Test
    void foldsALegacyEventIntoTheAdapterCopyWithTheMatchingEventFacts() {
        SecurityDispositionRecord registered = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1", BASE);
        SecurityEvent legacy = eventWithDisposition(event("SEC-LEGACY-FACTS", "A1", "ACCESS", BASE), registered);
        SecurityEvent earlierCamera = withSource(
                event("SEC-LEGACY-FACTS", "A1", "ACCESS", BASE.minusSeconds(20 * 60)),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        SecurityEvent matchingAccess = withSource(event("SEC-LEGACY-FACTS", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityIncidentService service = service(List.of(legacy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(earlierCamera, matchingAccess)));

        List<SecurityIncident> incidents = service.list(new SecurityIncidentQuery(null, 20)).items();

        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "camera-1"))
                .singleElement().satisfies(incident ->
                        assertThat(incident.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED));
        assertThat(incidents).filteredOn(incident ->
                        incident.disposition() == SecurityDisposition.CONFIRMED_INCIDENT)
                .singleElement();
    }

    @Test
    void aliasesALegacyEventWithItsEnrichedAdapterCopy() {
        SecurityEvent legacy = event("SEC-ALIAS", "A1", "ACCESS", BASE);
        SecurityDispositionRecord registered = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(10));
        SecurityEvent enriched = withSource(
                enrichedEvent(legacy, registered, BASE.plusSeconds(5), SecurityEventSeverity.HIGH),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityIncidentService service = service(List.of(legacy), List.of(), 50,
                new SecurityIncidentHandoffStore(10), List.of(adapterReturning(enriched)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).singleElement().satisfies(incident -> {
            assertThat(incident.evidence()).singleElement()
                    .satisfies(evidence -> {
                        assertThat(evidence.eventSourceId()).isEqualTo("access-1");
                        assertThat(evidence.severity()).isEqualTo("HIGH");
                    });
            assertThat(incident.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        });
    }

    @Test
    void carriesARegisteredModelDispositionIntoTheBuiltIncident() {
        SecurityDispositionRecord registered = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1", BASE);
        SecurityEvent classified = eventWithDisposition(event("SEC-MODEL", "A1", "ACCESS", BASE), registered);

        SecurityIncidentService service = service(List.of(classified));
        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(incident.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(incident.dispositionRecord()).isEqualTo(registered);
        assertThat(incident.reviewedAt()).isEqualTo(BASE);
    }

    @Test
    void keepsTheLatestDecidedSourceDispositionWhenEventsDisagree() {
        SecurityDispositionRecord earlier = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.HUMAN_REVIEW, "analyst-1", null, null, null, BASE);
        SecurityDispositionRecord later = new SecurityDispositionRecord(SecurityDisposition.INCONCLUSIVE,
                SecurityDispositionSource.HUMAN_REVIEW, "analyst-2", null, null, null, BASE.plusSeconds(60));
        SecurityEvent firstEvent = eventWithDisposition(event("SEC-D1", "A1", "ACCESS", BASE), earlier);
        SecurityEvent secondEvent = eventWithDisposition(event("SEC-D2", "A1", "ACCESS", BASE.plusSeconds(60)), later);

        SecurityIncidentService service = service(List.of(firstEvent, secondEvent));
        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(incident.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(incident.disposition()).isEqualTo(SecurityDisposition.INCONCLUSIVE);
        assertThat(incident.dispositionRecord()).isEqualTo(later);
        assertThat(incident.reviewedAt()).isEqualTo(BASE.plusSeconds(60));
    }

    @Test
    void upgradesAStoredUnreviewedIncidentWhenTheSourceLaterSuppliesADisposition() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-UPGRADE", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(events, List.of(), 50);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        assertThat(initial.status()).isEqualTo(SecurityIncidentStatus.OPEN);
        assertThat(initial.disposition()).isEqualTo(SecurityDisposition.UNREVIEWED);

        SecurityDispositionRecord model = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(300));
        events.set(0, eventWithDisposition(event("SEC-UPGRADE", "A1", "ACCESS", BASE), model));

        SecurityIncident upgraded = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(upgraded.incidentId()).isEqualTo(initial.incidentId());
        assertThat(upgraded.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(upgraded.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(upgraded.dispositionRecord()).isEqualTo(model);
        assertThat(upgraded.reviewedAt()).isEqualTo(BASE.plusSeconds(300));
    }

    @Test
    void keepsAStoredHumanDecisionWhenTheSourceLaterSuppliesANewerModelDecision() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-HUMAN", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(events, List.of(), 50);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        SecurityIncident reviewed = service.review(initial.incidentId(), SecurityDisposition.CONFIRMED_INCIDENT,
                "APPROVER");

        SecurityDispositionRecord newerModel = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(600));
        events.set(0, eventWithDisposition(event("SEC-HUMAN", "A1", "ACCESS", BASE), newerModel));

        SecurityIncident restored = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(restored.dispositionRecord()).isEqualTo(reviewed.dispositionRecord());
        assertThat(restored.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
        assertThat(restored.reviewedAt()).isEqualTo(reviewed.reviewedAt());
    }

    @Test
    void replacesAnOlderStoredSourceDecisionWithANewerOne() {
        SecurityDispositionRecord older = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(60));
        List<SecurityEvent> events = new ArrayList<>(List.of(eventWithDisposition(
                event("SEC-MODEL-CORRECTION", "A1", "ACCESS", BASE), older)));
        SecurityIncidentService service = service(events, List.of(), 50);
        service.list(new SecurityIncidentQuery(null, 20));

        SecurityDispositionRecord newer = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(600));
        events.set(0, eventWithDisposition(event("SEC-MODEL-CORRECTION", "A1", "ACCESS", BASE), newer));

        SecurityIncident corrected = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(corrected.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(corrected.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(corrected.dispositionRecord()).isEqualTo(newer);
        assertThat(corrected.reviewedAt()).isEqualTo(BASE.plusSeconds(600));
    }

    @Test
    void appliesANewerSourceCorrectionToAnEvictedHandedOffIncident() {
        SecurityDispositionRecord initial = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(60));
        List<SecurityEvent> events = new ArrayList<>(List.of(eventWithDisposition(
                event("SEC-CORRECT", "A1", "ACCESS", BASE), initial)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initialIncident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        assertThat(initialIncident.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        SecurityIncident handedOff = service.handoff(initialIncident.incidentId());

        events.add(event("SEC-EVICT", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));

        SecurityDispositionRecord correction = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(600));
        events.set(0, eventWithDisposition(event("SEC-CORRECT", "A1", "ACCESS", BASE), correction));

        SecurityIncident restored = service.get(initialIncident.incidentId());

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(restored.dispositionRecord()).isEqualTo(correction);
        assertThat(handoffs.list()).singleElement()
                .satisfies(handoff -> assertThat(handoff.dispositionRecord()).isEqualTo(correction));
    }

    @Test
    void reservesARetainedHandoffForTheSourceWhoseOccurrenceTimeMatches() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-ALIAS-EVICT", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(initial.incidentId(), SecurityDisposition.FALSE_POSITIVE, "APPROVER");
        SecurityIncident handedOff = service.handoff(initial.incidentId());

        // A later event evicts the source-less incident from the bounded incident store
        // while its human-reviewed handoff survives.
        events.add(event("SEC-EVICT", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));

        // Two concrete sources now reuse the evicted event id: an unrelated camera copy
        // 20 minutes earlier and the access copy matching the handed-off occurrence.
        events.remove(0);
        events.add(withSource(event("SEC-ALIAS-EVICT", "A1", "ACCESS", BASE.minusSeconds(20 * 60)),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1"));
        events.add(withSource(event("SEC-ALIAS-EVICT", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1"));

        SecurityIncident restored = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.status() == SecurityIncidentStatus.HANDOFF)
                .findFirst().orElseThrow();

        assertThat(restored.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(hasEventSource(restored, "access-1")).isTrue();
        assertThat(hasEventSource(restored, "camera-1")).isFalse();
        assertThat(handoffs.list()).singleElement()
                .satisfies(handoff -> assertThat(handoff.eventIdentities())
                        .anyMatch(identity -> identity.source().sourceId().equals("access-1")));
    }

    @Test
    void keepsAStoredHumanHandoffDispositionWhenTheSourceLaterSuppliesANewerModelDecision() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-HANDOFF-HUMAN", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        SecurityIncident reviewed = service.review(initial.incidentId(), SecurityDisposition.CONFIRMED_INCIDENT,
                "APPROVER");
        service.handoff(initial.incidentId());

        events.add(event("SEC-EVICT", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));

        SecurityDispositionRecord newerModel = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                BASE.plusSeconds(600));
        events.set(0, eventWithDisposition(event("SEC-HANDOFF-HUMAN", "A1", "ACCESS", BASE), newerModel));

        SecurityIncident restored = service.get(initial.incidentId());

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(restored.dispositionRecord()).isEqualTo(reviewed.dispositionRecord());
        assertThat(handoffs.list()).singleElement()
                .satisfies(handoff -> assertThat(handoff.dispositionRecord().source())
                        .isEqualTo(SecurityDispositionSource.HUMAN_REVIEW));
    }

    @Test
    void keepsAStoredHumanDecisionWhenRecoveringFromAnEvictedModelHandoff() {
        SecurityDispositionRecord model = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1",
                BASE.plusSeconds(60));
        List<SecurityEvent> events = new ArrayList<>(List.of(
                eventWithDisposition(event("SEC-MODEL", "A1", "ACCESS", BASE), model)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident modelIncident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        SecurityIncident handedOff = service.handoff(modelIncident.incidentId());

        // The source-modelled incident is evicted from the incident store while its
        // handoff survives; the newer human-reviewed incident remains stored.
        events.add(event("SEC-HUMAN", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        SecurityIncident humanIncident = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.disposition() == SecurityDisposition.UNREVIEWED).findFirst().orElseThrow();
        SecurityIncident reviewed = service.review(humanIncident.incidentId(), SecurityDisposition.CONFIRMED_INCIDENT,
                "APPROVER");

        // A bridge event merges the fresh evidence back together; recovering the retained
        // model handoff must not bury the stored human decision.
        events.add(event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));
        SecurityIncident restored = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(restored.dispositionRecord()).isEqualTo(reviewed.dispositionRecord());
        assertThat(handoffs.list()).singleElement()
                .satisfies(handoff -> assertThat(handoff.dispositionRecord()).isEqualTo(reviewed.dispositionRecord()));
    }

    @Test
    void removesSupersededStatesBeforeSavingNewIncidents() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-A-1", "A1", "ACCESS", BASE),
                event("SEC-C-1", "C1", "ACCESS", BASE.plusSeconds(10 * 60)),
                event("SEC-A-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentService service = service(events, List.of(), 3);
        service.list(new SecurityIncidentQuery(null, 20));
        events.add(event("SEC-A-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));
        events.add(event("SEC-D-1", "D1", "ACCESS", BASE.plusSeconds(2 * 60 * 60)));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).extracting(SecurityIncident::buildingId)
                .contains("A1", "C1", "D1");
    }

    @Test
    void returnsAnOffsetPageWhileKeepingTheFullIncidentTotal() {
        SecurityIncidentService service = service(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A2", "ACCESS", BASE.plusSeconds(60))));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 1, 1));

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).buildingId()).isEqualTo("A1");
    }

    @Test
    void findsMatchingIncidentBeyondTheFirstListPageFromOneSnapshot() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-TARGET", "TARGET", "ACCESS", BASE)));
        for (int index = 1; index <= 100; index++) {
            events.add(event("SEC-NEW-" + index, "NEW-" + index, "ACCESS", BASE.plusSeconds(index * 60L)));
        }
        SecurityIncidentService service = service(events, List.of(), 200);

        assertThat(service.list(new SecurityIncidentQuery(null, 0, 100)).items())
                .extracting(SecurityIncident::buildingId).doesNotContain("TARGET");
        assertThat(service.findMatching(List.of("TARGET"), null))
                .singleElement().satisfies(incident ->
                        assertThat(incident.eventIds()).containsExactly("SEC-TARGET"));
    }

    @Test
    void doesNotExposeHandedOffIncidentsWhoseWorkItemsWereEvicted() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            events.add(event("SEC-" + index, "B" + index, "ACCESS", BASE.plusSeconds(index * 60L)));
        }
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(100);
        SecurityIncidentService service = service(events, List.of(), 200, handoffs);
        List<SecurityIncident> incidents = new ArrayList<>(service.list(new SecurityIncidentQuery(null, 0, 100)).items());
        incidents.addAll(service.list(new SecurityIncidentQuery(null, 100, 100)).items());
        for (SecurityIncident incident : incidents) {
            service.review(incident.incidentId());
            service.handoff(incident.incidentId());
        }

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 0, 100));

        assertThat(page.total()).isEqualTo(100);
        assertThat(page.items()).allMatch(incident -> handoffs.list().stream()
                .anyMatch(handoff -> handoff.workItemId().equals(incident.handoffWorkItemId())));
    }

    @Test
    void retiresAHandoffWhenItsEntireCorrelationDisappears() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 10, handoffs);
        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(incident.incidentId());
        service.handoff(incident.incidentId());
        events.clear();

        service.list(new SecurityIncidentQuery(null, 20));

        assertThat(handoffs.list()).isEmpty();
    }

    @Test
    void correlationIsStableForShuffledInputAndHighAlert() {
        List<SecurityEvent> events = List.of(
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(9 * 60)),
                event("SEC-1", "A1", "ACCESS", BASE));
        Alert alert = new Alert("ALT-1", "PARK-A", "A1", "DEV-ACCESS-001", AlertClassification.ACCESS,
                RiskLevel.HIGH, "REDACTED: access alert", BASE.plusSeconds(9 * 60), List.of("security-event:SEC-2"));

        SecurityIncidentService first = service(events, List.of(alert));
        SecurityIncidentService second = service(new ArrayList<>(List.of(events.get(1), events.get(0))), List.of(alert));

        SecurityIncident left = first.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        SecurityIncident right = second.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(left.incidentId()).isEqualTo(right.incidentId());
        assertThat(left.eventIds()).containsExactly("SEC-1", "SEC-2");
        assertThat(left.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
    }

    @Test
    void preservesTheHistoricalHandoffRiskWhenTheAlertLeavesTheActiveFeed() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        List<Alert> alerts = new ArrayList<>(List.of(new Alert("ALT-1", "PARK-A", "A1", "DEV-ACCESS-001",
                AlertClassification.ACCESS, RiskLevel.HIGH, "REDACTED: access alert", BASE,
                List.of("security-event:SEC-1"))));
        SecurityIncidentService service = service(events, alerts);
        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
        service.review(incident.incidentId());
        service.handoff(incident.incidentId());
        alerts.clear();

        SecurityIncident restored = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
        assertThat(restored.recommendations()).containsExactly(
                "核对安全处置手册并由授权人员复核。", "必要时记录协同交接并保留人工审计。");
    }

    @Test
    void linksAlertsBySourceQualifiedEventReference() {
        SecurityEvent access = withSource(event("SEC-SHARED-REF", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        SecurityEvent camera = withSource(event("SEC-SHARED-REF", "A1", "ACCESS", BASE.plusSeconds(20 * 60)),
                SecuritySourceType.CAMERA_ANALYTICS, "camera-1");
        Alert accessAlert = new Alert("ALT-ACCESS", "PARK-A", "A1", "DEV-1", AlertClassification.ACCESS,
                RiskLevel.HIGH, "REDACTED: access alert", BASE,
                List.of(SecurityEventIdentity.of(access).reference()));
        SecurityIncidentService service = service(List.of(access, camera), List.of(accessAlert));

        List<SecurityIncident> incidents = service.list(new SecurityIncidentQuery(null, 20)).items();

        assertThat(incidents).hasSize(2);
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "access-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.alertIds()).containsExactly("ALT-ACCESS");
                    assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
                });
        assertThat(incidents).filteredOn(incident -> hasEventSource(incident, "camera-1"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.alertIds()).isEmpty();
                    assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.MEDIUM);
                });
    }

    @Test
    void keepsLegacyAlertReferencesAsAnExplicitAlias() {
        SecurityEvent access = withSource(event("SEC-LEGACY-REF", "A1", "ACCESS", BASE),
                SecuritySourceType.ACCESS_CONTROL, "access-1");
        Alert legacyAlert = new Alert("ALT-LEGACY", "PARK-A", "A1", "DEV-1", AlertClassification.ACCESS,
                RiskLevel.HIGH, "REDACTED: legacy alert", BASE, List.of("security-event:SEC-LEGACY-REF"));
        SecurityIncidentService service = service(List.of(access), List.of(legacyAlert));

        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(incident.alertIds()).containsExactly("ALT-LEGACY");
        assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
    }

    @Test
    void onlyLinksAlertsToEventsInTheSameLocation() {
        Alert foreignAlert = new Alert("ALT-FOREIGN", "PARK-B", "B1", "DEV-1", AlertClassification.ACCESS,
                RiskLevel.HIGH, "REDACTED: foreign alert", BASE, List.of("security-event:SEC-DUP"));
        SecurityIncidentService service = service(List.of(
                event("SEC-DUP", "PARK-A", "A1", "ACCESS", BASE),
                event("SEC-DUP", "PARK-B", "B1", "ACCESS", BASE.plusSeconds(60))),
                List.of(foreignAlert));

        List<SecurityIncident> incidents = service.list(new SecurityIncidentQuery(null, 20)).items();

        assertThat(incidents).filteredOn(incident -> incident.parkId().equals("PARK-A"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.alertIds()).isEmpty();
                    assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.MEDIUM);
                });
        assertThat(incidents).filteredOn(incident -> incident.parkId().equals("PARK-B"))
                .singleElement().satisfies(incident -> {
                    assertThat(incident.alertIds()).containsExactly("ALT-FOREIGN");
                    assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
                });
    }

    @Test
    void reviewAndHandoffAreIdempotent() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        SecurityIncident reviewed = service.review(incidentId);
        SecurityIncident reviewedAgain = service.review(incidentId);
        SecurityIncident handedOff = service.handoff(incidentId);
        SecurityIncident handedOffAgain = service.handoff(incidentId);

        assertThat(reviewed.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(reviewedAgain.reviewedAt()).isEqualTo(reviewed.reviewedAt());
        assertThat(handedOff.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(handedOffAgain.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
    }

    @Test
    void preservesReviewedAndHandoffStateWhenAnEarlierEventExpandsTheIncident() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(9 * 60))));
        SecurityIncidentService service = service(events);
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();
        service.review(incidentId);
        SecurityIncident handedOff = service.handoff(incidentId);

        events.add(event("SEC-1", "A1", "ACCESS", BASE));

        SecurityIncident refreshed = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(refreshed.incidentId()).isEqualTo(incidentId);
        assertThat(refreshed.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(refreshed.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
        assertThat(refreshed.eventIds()).containsExactly("SEC-1", "SEC-2");
    }

    @Test
    void reconcilesAllStoredStatesWhenALateEventBridgesTwoIncidents() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentService service = service(events);

        SecurityIncident later = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.eventIds().contains("SEC-2"))
                .findFirst().orElseThrow();
        service.review(later.incidentId());
        SecurityIncident handedOff = service.handoff(later.incidentId());
        events.add(event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));

        SecurityIncident merged = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(merged.incidentId()).isNotEqualTo(later.incidentId());
        assertThat(merged.eventIds()).containsExactly("SEC-1", "SEC-BRIDGE", "SEC-2");
        assertThat(merged.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(merged.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
    }

    @Test
    void refreshesExistingHandoffProjectionAfterCorrelationDataChanges() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE, "REDACTED:旧摘要")));
        List<Alert> alerts = new ArrayList<>();
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, alerts, 50, handoffs);
        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(incident.incidentId());
        service.handoff(incident.incidentId());

        events.set(0, event("SEC-1", "A1", "ACCESS", BASE, "REDACTED:新摘要"));
        alerts.add(new Alert("ALT-1", "PARK-A", "A1", "DEV-1", AlertClassification.ACCESS,
                RiskLevel.HIGH, "REDACTED:高风险告警", BASE.plusSeconds(30), List.of("security-event:SEC-1")));
        service.list(new SecurityIncidentQuery(null, 20));

        assertThat(handoffs.list()).singleElement().satisfies(handoff -> {
            assertThat(handoff.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
            assertThat(handoff.safeSummary()).isEqualTo("REDACTED:新摘要");
        });
    }

    @Test
    void missingRiskInformationDefaultsToMedium() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));

        SecurityIncident incident = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(incident.riskLevel()).isEqualTo(SecurityIncidentRisk.MEDIUM);
    }

    @Test
    void keepsCorrelationBucketsDistinctWhenIdentifiersContainDelimiters() {
        SecurityIncidentService service = service(List.of(
                event("SEC-1", "P:A", "B", "ACCESS", BASE),
                event("SEC-2", "P", "A:B", "ACCESS", BASE.plusSeconds(60))));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).extracting(SecurityIncident::eventIds)
                .containsExactlyInAnyOrder(List.of("SEC-1"), List.of("SEC-2"));
    }

    @Test
    void generatesOpaqueUrlSafeIncidentIdsForSlashContainingIdentifiers() {
        SecurityIncidentService service = service(List.of(
                event("SEC/1", "P/A", "B/A", "ACCESS/ATTEMPT", BASE)));

        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        assertThat(incidentId).matches("INC:[0-9a-f]{64}");
    }

    @Test
    void handoffRequiresACompletedReview() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        assertThatThrownBy(() -> service.handoff(incidentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("security incident must be reviewed before handoff");
    }

    @Test
    void doesNotPublishAHandoffForAnOpenIncident() {
        List<SecurityIncidentHandoff> created = new ArrayList<>();
        SecurityIncidentHandoffPort handoffs = new SecurityIncidentHandoffPort() {
            @Override
            public SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
                SecurityIncidentHandoff result = new SecurityIncidentHandoff("WI:" + incident.incidentId(), incident.incidentId(),
                        incident.parkId(), incident.buildingId(), incident.riskLevel(), incident.summary(), now);
                created.add(result);
                return result;
            }

            @Override
            public List<SecurityIncidentHandoff> list() { return List.copyOf(created); }

            @Override
            public void retire(String incidentId) {
                created.removeIf(handoff -> handoff.incidentId().equals(incidentId));
            }
        };
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)), List.of(), 50, handoffs);
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        assertThatThrownBy(() -> service.handoff(incidentId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("security incident must be reviewed before handoff");
        assertThat(created).isEmpty();
    }

    @Test
    void rejectsAnIncidentIdThatIsAbsentFromTheCurrentCorrelation() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(events);
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();
        events.clear();

        assertThatThrownBy(() -> service.get(incidentId))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessage("security incident not found");
    }

    @Test
    void preservesStateWhenTheFiniteEventWindowShrinks() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(8 * 60)),
                event("SEC-3", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentService service = service(events);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(initial.incidentId());
        SecurityIncident handedOff = service.handoff(initial.incidentId());
        events.removeIf(event -> event.eventId().equals("SEC-1"));

        SecurityIncident refreshed = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(refreshed.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(refreshed.handoffWorkItemId()).isEqualTo(handedOff.handoffWorkItemId());
    }

    @Test
    void keepsReadingWhenMergedWindowsContainConflictingHandoffs() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentService service = service(events);
        List<SecurityIncident> initial = service.list(new SecurityIncidentQuery(null, 20)).items();
        for (SecurityIncident incident : initial) {
            service.review(incident.incidentId());
            service.handoff(incident.incidentId());
        }
        events.add(event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));

        SecurityIncidentPage merged = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(merged.items()).hasSize(1);
        assertThat(merged.items().get(0).status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
    }

    @Test
    void preservesFinalizedStateWhenAnOpenWindowJoinsTwoHandedOffWindows() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)),
                event("SEC-3", "A1", "ACCESS", BASE.plusSeconds(32 * 60))));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 50, handoffs);
        List<SecurityIncident> separated = service.list(new SecurityIncidentQuery(null, 20)).items();
        SecurityIncident secondIncident = separated.stream()
                .filter(incident -> incident.eventIds().contains("SEC-2"))
                .findFirst().orElseThrow();
        SecurityIncident thirdIncident = separated.stream()
                .filter(incident -> incident.eventIds().contains("SEC-3"))
                .findFirst().orElseThrow();
        service.review(secondIncident.incidentId());
        SecurityIncident second = service.handoff(secondIncident.incidentId());
        service.review(thirdIncident.incidentId());
        SecurityIncident third = service.handoff(thirdIncident.incidentId());
        events.add(event("SEC-BRIDGE-1", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));
        events.add(event("SEC-BRIDGE-2", "A1", "ACCESS", BASE.plusSeconds(24 * 60)));

        SecurityIncident merged = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(merged.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(merged.handoffWorkItemId()).isIn(second.handoffWorkItemId(), third.handoffWorkItemId());
        assertThat(handoffs.list()).hasSize(1);
    }

    @Test
    void preservesReviewTimestampWhenRestoringAnEvictedHandoff() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        SecurityIncident reviewed = service.review(initial.incidentId(), SecurityDisposition.FALSE_POSITIVE,
                "APPROVER");
        service.handoff(initial.incidentId());
        events.add(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));

        SecurityIncident restored = service.get(initial.incidentId());

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.reviewedAt()).isEqualTo(reviewed.reviewedAt());
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(restored.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
    }

    @Test
    void keepsAHumanDispositionWhenTheEventStoreEvictsAHandedOffIncident() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(initial.incidentId(), SecurityDisposition.FALSE_POSITIVE, "APPROVER");
        service.handoff(initial.incidentId());
        events.add(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));

        SecurityIncident restored = service.get(initial.incidentId());

        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(restored.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
        assertThat(restored.dispositionRecord().actor()).isEqualTo("APPROVER");
        assertThat(handoffs.list()).singleElement()
                .satisfies(handoff -> assertThat(handoff.dispositionRecord().disposition())
                        .isEqualTo(SecurityDisposition.FALSE_POSITIVE));
    }

    @Test
    void listDoesNotReturnIncidentsEvictedByTheBoundedStore() {
        SecurityIncidentService service = service(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A2", "ACCESS", BASE.plusSeconds(60))), List.of(), 1);

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).hasSize(1);
        assertThat(service.get(page.items().get(0).incidentId())).isEqualTo(page.items().get(0));
    }

    @Test
    void keepsHandedOffIncidentsReachableAfterStateEviction() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident handedOff = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(handedOff.incidentId());
        SecurityIncident completed = service.handoff(handedOff.incidentId());
        events.add(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));

        service.list(new SecurityIncidentQuery(null, 20));

        assertThat(service.get(handedOff.incidentId()).status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(service.get(handedOff.incidentId()).handoffWorkItemId())
                .isEqualTo(completed.handoffWorkItemId());
    }

    @Test
    void restoresAHandedOffIncidentWhenItsDerivedIdChangesAfterStateEviction() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(9 * 60))));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);
        SecurityIncident initial = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(initial.incidentId());
        SecurityIncident completed = service.handoff(initial.incidentId());
        events.add(event("SEC-OTHER", "B1", "ACCESS", BASE.plusSeconds(2 * 60 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));
        events.add(event("SEC-1", "A1", "ACCESS", BASE));

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).anyMatch(incident -> incident.buildingId().equals("A1"));
        SecurityIncident restored = page.items().stream()
                .filter(incident -> incident.buildingId().equals("A1"))
                .findFirst().orElseThrow();
        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(restored.incidentId()).isNotEqualTo(initial.incidentId());
        assertThat(restored.handoffWorkItemId()).isEqualTo(completed.handoffWorkItemId());
        assertThat(service.get(restored.incidentId()).status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
    }

    @Test
    void reconcilesEveryRetainedHandoffWhenEvictedWindowsMerge() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE)
        ));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 1, handoffs);

        SecurityIncident first = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(first.incidentId());
        SecurityIncident firstHandoff = service.handoff(first.incidentId());

        events.add(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)));
        SecurityIncident second = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.eventIds().contains("SEC-2"))
                .findFirst().orElseThrow();
        service.review(second.incidentId());
        SecurityIncident secondHandoff = service.handoff(second.incidentId());

        events.add(event("SEC-OTHER", "B1", "ACCESS", BASE.plusSeconds(2 * 60 * 60)));
        service.list(new SecurityIncidentQuery(null, 20));
        events.add(event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));

        SecurityIncident merged = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.buildingId().equals("A1"))
                .findFirst().orElseThrow();

        assertThat(merged.status()).isEqualTo(SecurityIncidentStatus.HANDOFF);
        assertThat(merged.eventIds()).containsExactly("SEC-1", "SEC-BRIDGE", "SEC-2");
        assertThat(merged.handoffWorkItemId()).isIn(firstHandoff.handoffWorkItemId(), secondHandoff.handoffWorkItemId());
        assertThat(handoffs.list()).extracting(SecurityIncidentHandoff::workItemId)
                .containsExactly(merged.handoffWorkItemId());
    }

    @Test
    void preservesFinalizedStateForEveryGroupAfterACorrelationResplit() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60)),
                event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60))));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 50, handoffs);
        SecurityIncident merged = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(merged.incidentId());
        SecurityIncident completed = service.handoff(merged.incidentId());
        events.removeIf(event -> event.eventId().equals("SEC-BRIDGE"));

        SecurityIncidentPage split = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(split.items()).hasSize(2);
        assertThat(split.items()).extracting(SecurityIncident::status)
                .containsOnly(SecurityIncidentStatus.HANDOFF);
        assertThat(split.items()).extracting(SecurityIncident::incidentId)
                .doesNotHaveDuplicates();
        assertThat(split.items()).extracting(SecurityIncident::handoffWorkItemId)
                .doesNotContainNull();
        assertThat(split.items()).extracting(SecurityIncident::handoffWorkItemId)
                .contains(completed.handoffWorkItemId());
        assertThat(handoffs.list()).extracting(SecurityIncidentHandoff::workItemId)
                .containsExactly(completed.handoffWorkItemId());
        assertThat(split.items()).extracting(SecurityIncident::handoffWorkItemId)
                .containsOnly(completed.handoffWorkItemId());
    }

    @Test
    void removesStoredCorrelationsThatDisappearBeforeSavingCurrentIncidents() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-OLD", "A1", "ACCESS", BASE),
                event("SEC-VANISHED", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentService service = service(events, List.of(), 2);
        String oldIncidentId = service.list(new SecurityIncidentQuery(null, 20)).items().stream()
                .filter(incident -> incident.eventIds().contains("SEC-OLD"))
                .findFirst().orElseThrow().incidentId();

        events.removeIf(event -> event.eventId().equals("SEC-VANISHED"));
        events.add(event("SEC-NEW", "A1", "ACCESS", BASE.plusSeconds(32 * 60)));

        SecurityIncidentPage current = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(current.items()).extracting(SecurityIncident::incidentId).contains(oldIncidentId);
        assertThat(current.items()).extracting(SecurityIncident::eventIds)
                .noneMatch(ids -> ids.contains("SEC-VANISHED"));
    }

    @Test
    void findsAHandedOffIncidentEvenWhenItFallsBeyondTheListPage() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-OLD", "A1", "ACCESS", BASE)));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(200);
        SecurityIncidentService service = service(events, List.of(), 100, handoffs);
        SecurityIncident old = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);
        service.review(old.incidentId());
        service.handoff(old.incidentId());
        for (int index = 1; index <= 100; index++) {
            events.add(event("SEC-NEW-" + index, "NEW-" + index, "ACCESS", BASE.plusSeconds(index * 60L)));
        }

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, SecurityIncidentQuery.MAX_LIMIT));

        assertThat(handoffs.list()).extracting(SecurityIncidentHandoff::incidentId).contains(old.incidentId());
        assertThat(page.total()).isEqualTo(101);
        assertThat(page.items()).extracting(SecurityIncident::incidentId).doesNotContain(old.incidentId());
        assertThat(service.get(old.incidentId()).incidentId()).isEqualTo(old.incidentId());
    }

    @Test
    void retiresSupersededHandoffsWhenCorrelationWindowsMerge() {
        List<SecurityEvent> events = new ArrayList<>(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(16 * 60))));
        SecurityIncidentHandoffStore handoffs = new SecurityIncidentHandoffStore(10);
        SecurityIncidentService service = service(events, List.of(), 50, handoffs);
        List<SecurityIncident> separated = service.list(new SecurityIncidentQuery(null, 20)).items();
        for (SecurityIncident incident : separated) {
            service.review(incident.incidentId());
            service.handoff(incident.incidentId());
        }
        events.add(event("SEC-BRIDGE", "A1", "ACCESS", BASE.plusSeconds(8 * 60)));

        SecurityIncident merged = service.list(new SecurityIncidentQuery(null, 20)).items().get(0);

        assertThat(handoffs.list()).singleElement()
                .extracting(SecurityIncidentHandoff::workItemId)
                .isEqualTo(merged.handoffWorkItemId());
    }

    @Test
    void recordsAHumanDispositionWhenReviewingAnIncident() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        SecurityIncident reviewed = service.review(incidentId, SecurityDisposition.FALSE_POSITIVE, "APPROVER");

        assertThat(reviewed.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(reviewed.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(reviewed.reviewedAt()).isEqualTo(BASE.plusSeconds(3600));
        assertThat(reviewed.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
        assertThat(reviewed.dispositionRecord().actor()).isEqualTo("APPROVER");
        assertThat(reviewed.dispositionRecord().evidenceRef()).isEqualTo("human-review:" + incidentId);
        assertThat(reviewed.dispositionRecord().decidedAt()).isEqualTo(BASE.plusSeconds(3600));
    }

    @Test
    void defaultsToConfirmedIncidentForTheLegacyReviewEntryPoint() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        SecurityIncident reviewed = service.review(incidentId);

        assertThat(reviewed.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(reviewed.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
    }

    @Test
    void keepsTheFirstDispositionOnIdempotentReview() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        service.review(incidentId, SecurityDisposition.CONFIRMED_INCIDENT, "APPROVER");
        SecurityIncident second = service.review(incidentId, SecurityDisposition.FALSE_POSITIVE, "ADMIN");

        assertThat(second.disposition()).isEqualTo(SecurityDisposition.CONFIRMED_INCIDENT);
        assertThat(second.dispositionRecord().actor()).isEqualTo("APPROVER");
    }

    @Test
    void rejectsAnUnreviewedDispositionDecision() {
        SecurityIncidentService service = service(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();

        assertThatThrownBy(() -> service.review(incidentId, SecurityDisposition.UNREVIEWED, "APPROVER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decided disposition");
    }

    @Test
    void retainsAStoredDispositionWhenNewEventsArrive() {
        List<SecurityEvent> events = new ArrayList<>(List.of(event("SEC-1", "A1", "ACCESS", BASE)));
        SecurityIncidentService service = service(events);
        String incidentId = service.list(new SecurityIncidentQuery(null, 20)).items().get(0).incidentId();
        service.review(incidentId, SecurityDisposition.FALSE_POSITIVE, "APPROVER");

        events.add(event("SEC-2", "A1", "ACCESS", BASE.plusSeconds(60)));

        SecurityIncident restored = service.get(incidentId);
        assertThat(restored.status()).isEqualTo(SecurityIncidentStatus.REVIEWED);
        assertThat(restored.disposition()).isEqualTo(SecurityDisposition.FALSE_POSITIVE);
        assertThat(restored.dispositionRecord().source()).isEqualTo(SecurityDispositionSource.HUMAN_REVIEW);
        assertThat(restored.dispositionRecord().actor()).isEqualTo("APPROVER");
    }

    private static SecurityIncidentService service(List<SecurityEvent> events) {
        return service(events, List.of(), 50);
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts) {
        return service(events, alerts, 50);
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts, int capacity) {
        List<SecurityIncidentHandoff> created = new ArrayList<>();
        return service(events, alerts, capacity, new SecurityIncidentHandoffPort() {
            @Override
            public SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
                SecurityIncidentHandoff handoff = new SecurityIncidentHandoff("WI:" + incident.incidentId(), incident.incidentId(), incident.parkId(),
                        incident.buildingId(), incident.riskLevel(), incident.summary(), now, null, now,
                        incident.eventType(), incident.eventIdentities(), incident.dispositionRecord(),
                        incident.lastOccurredAt());
                created.removeIf(existing -> existing.workItemId().equals(handoff.workItemId()));
                created.add(handoff);
                return handoff;
            }

            @Override
            public SecurityIncidentHandoff refresh(SecurityIncident incident, Instant now) {
                SecurityIncidentHandoff existing = created.stream()
                        .filter(handoff -> handoff.workItemId().equals(incident.handoffWorkItemId()))
                        .findFirst().orElse(null);
                if (existing == null) return createOrGet(incident, now);
                SecurityIncidentHandoff refreshed = new SecurityIncidentHandoff(existing.workItemId(), incident.incidentId(),
                        incident.parkId(), incident.buildingId(), incident.riskLevel(), incident.summary(),
                        existing.createdAt(), existing.reviewedAt(), now, incident.eventType(), incident.eventIdentities(),
                        incident.dispositionRecord(), incident.lastOccurredAt());
                created.removeIf(handoff -> handoff.workItemId().equals(existing.workItemId()));
                created.add(refreshed);
                return refreshed;
            }

            @Override
            public List<SecurityIncidentHandoff> list() {
                return List.copyOf(created);
            }

            @Override
            public void retire(String incidentId) {
                created.removeIf(handoff -> handoff.incidentId().equals(incidentId));
            }
        });
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts, int capacity,
                                                    SecurityIncidentHandoffPort handoffs) {
        return service(events, alerts, capacity, handoffs, List.of());
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts, int capacity,
                                                    SecurityIncidentHandoffPort handoffs,
                                                    List<SecuritySourceAdapter> sourceAdapters) {
        SecurityEventReader security = new SecurityEventReader() {
            @Override
            public SecurityEvent getEvent(String eventId) {
                return events.stream().filter(event -> event.eventId().equals(eventId)).findFirst().orElseThrow();
            }

            @Override
            public List<SecurityEvent> listEvents() {
                return events;
            }
        };
        AlertPort alertPort = new AlertPort() {
            @Override
            public Alert getAlert(String alertId) {
                return alerts.stream().filter(alert -> alert.id().equals(alertId)).findFirst().orElseThrow();
            }

            @Override
            public List<Alert> findHistory(String deviceId) {
                return List.of();
            }

            @Override
            public List<Alert> listActive() {
                return alerts;
            }
        };
        return new SecurityIncidentService(security, alertPort, new SecurityIncidentStore(capacity), handoffs,
                Clock.fixed(BASE.plusSeconds(3600), ZoneOffset.UTC), sourceAdapters);
    }

    private static boolean hasEventSource(SecurityIncident incident, String eventSourceId) {
        return incident.evidence().stream()
                .anyMatch(evidence -> eventSourceId.equals(evidence.eventSourceId()));
    }

    private static SecuritySourceAdapter adapterReturning(SecurityEvent... events) {
        return new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("test-adapter-feed", SecuritySourceType.ACCESS_CONTROL,
                        Set.of(SecurityEventType.ACCESS_ANOMALY), true);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return List.of(events);
            }
        };
    }

    private static SecuritySourceAdapter adapterBackedBy(List<SecurityEvent> events) {
        return new SecuritySourceAdapter() {
            @Override
            public SecuritySourceDescriptor descriptor() {
                return new SecuritySourceDescriptor("test-adapter-feed", SecuritySourceType.ACCESS_CONTROL,
                        Set.of(SecurityEventType.ACCESS_ANOMALY), true);
            }

            @Override
            public List<SecurityEvent> readEvents() {
                return List.copyOf(events);
            }
        };
    }

    private static SecurityEvent event(String id, String buildingId, String type, Instant occurredAt) {
        return event(id, "PARK-A", buildingId, type, occurredAt);
    }

    private static SecurityEvent event(String id, String parkId, String buildingId, String type, Instant occurredAt) {
        return event(id, parkId, buildingId, type, occurredAt, "REDACTED: safe event summary");
    }

    private static SecurityEvent event(String id, String buildingId, String type, Instant occurredAt, String summary) {
        return event(id, "PARK-A", buildingId, type, occurredAt, summary);
    }

    private static SecurityEvent event(String id, String parkId, String buildingId, String type, Instant occurredAt,
                                       String summary) {
        return new SecurityEvent(id, parkId, buildingId, type, occurredAt, summary);
    }

    private static SecurityEvent withSource(SecurityEvent base, SecuritySourceType sourceType, String sourceId) {
        return new SecurityEvent(base.eventId(), base.parkId(), base.buildingId(), base.eventType(),
                base.rawEventType(), new SecuritySourceRef(sourceType, sourceId), base.location(), base.observedAt(),
                base.receivedAt(), base.severity(), base.confidence(), base.privacy(), base.disposition(),
                base.ingestedBy(), base.ingestVersion(), base.evidenceSummary());
    }

    private static SecurityEvent enrichedEvent(SecurityEvent base, SecurityDispositionRecord disposition,
                                               Instant receivedAt, SecurityEventSeverity severity) {
        return new SecurityEvent(base.eventId(), base.parkId(), base.buildingId(), base.eventType(),
                base.rawEventType(), base.source(), base.location(), base.observedAt(), receivedAt, severity, 0.9d,
                base.privacy(), disposition, "adapter-1", "v2", base.evidenceSummary());
    }

    private static SecurityEvent eventWithDisposition(SecurityEvent base, SecurityDispositionRecord disposition) {
        return new SecurityEvent(base.eventId(), base.parkId(), base.buildingId(), base.eventType(),
                base.rawEventType(), base.source(), base.location(), base.observedAt(), base.receivedAt(),
                base.severity(), base.confidence(), base.privacy(), disposition, base.ingestedBy(),
                base.ingestVersion(), base.evidenceSummary());
    }
}
