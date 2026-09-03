package com.example.smartpark.securityincident;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.collaborationcenter.SecurityIncidentHandoffStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

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
    void listDoesNotReturnIncidentsEvictedByTheBoundedStore() {
        SecurityIncidentService service = service(List.of(
                event("SEC-1", "A1", "ACCESS", BASE),
                event("SEC-2", "A2", "ACCESS", BASE.plusSeconds(60))), List.of(), 1);

        SecurityIncidentPage page = service.list(new SecurityIncidentQuery(null, 20));

        assertThat(page.items()).hasSize(1);
        assertThat(service.get(page.items().get(0).incidentId())).isEqualTo(page.items().get(0));
    }

    private static SecurityIncidentService service(List<SecurityEvent> events) {
        return service(events, List.of(), 50);
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts) {
        return service(events, alerts, 50);
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts, int capacity) {
        return service(events, alerts, capacity, new SecurityIncidentHandoffPort() {
            @Override
            public SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
                return new SecurityIncidentHandoff("WI:" + incident.incidentId(), incident.incidentId(), incident.parkId(),
                        incident.buildingId(), incident.riskLevel(), incident.summary(), now);
            }

            @Override
            public List<SecurityIncidentHandoff> list() {
                return List.of();
            }
        });
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts, int capacity,
                                                    SecurityIncidentHandoffPort handoffs) {
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
                Clock.fixed(BASE.plusSeconds(3600), ZoneOffset.UTC));
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
}
