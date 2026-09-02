package com.example.smartpark.securityincident;

import com.example.smartpark.model.alert.Alert;
import com.example.smartpark.model.alert.AlertClassification;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.security.SecurityEvent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.port.security.SecurityPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(page.items().get(0).eventIds()).containsExactly("SEC-1", "SEC-2");
        assertThat(page.items().get(1).eventIds()).containsExactly("SEC-3");
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

    private static SecurityIncidentService service(List<SecurityEvent> events) {
        return service(events, List.of());
    }

    private static SecurityIncidentService service(List<SecurityEvent> events, List<Alert> alerts) {
        SecurityPort security = new SecurityPort() {
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
        SecurityIncidentHandoffPort handoffs = new SecurityIncidentHandoffPort() {
            @Override
            public SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
                return new SecurityIncidentHandoff("WI:" + incident.incidentId(), incident.incidentId(), now);
            }

            @Override
            public List<SecurityIncidentHandoff> list() {
                return List.of();
            }
        };
        return new SecurityIncidentService(security, alertPort, new SecurityIncidentStore(50), handoffs,
                Clock.fixed(BASE.plusSeconds(3600), ZoneOffset.UTC));
    }

    private static SecurityEvent event(String id, String buildingId, String type, Instant occurredAt) {
        return new SecurityEvent(id, "PARK-A", buildingId, type, occurredAt, "REDACTED: safe event summary");
    }
}
