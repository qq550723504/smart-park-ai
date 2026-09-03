package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityIncidentHandoffStoreTest {
    @Test
    void createsOneStableHandoffAndProjectsItAsHighPriorityWorkItem() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        SecurityIncident incident = incident();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        SecurityIncidentHandoff first = store.createOrGet(incident, now);
        SecurityIncidentHandoff second = store.createOrGet(incident, now.plusSeconds(1));

        assertThat(second).isEqualTo(first);
        assertThat(store.list()).containsExactly(first);
    }

    @Test
    void evictsOldestHandoffWhenCapacityIsExceeded() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(2);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        SecurityIncidentHandoff first = store.createOrGet(incident("INC-1"), now);
        SecurityIncidentHandoff second = store.createOrGet(incident("INC-2"), now.plusSeconds(1));
        SecurityIncidentHandoff third = store.createOrGet(incident("INC-3"), now.plusSeconds(2));

        assertThat(store.list()).containsExactly(second, third);
        assertThat(store.list()).doesNotContain(first);
    }

    @Test
    void refreshesAnExistingHandoffWhenIncidentRiskEscalates() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        SecurityIncidentHandoff first = store.createOrGet(incident("INC-1", SecurityIncidentRisk.MEDIUM, "REDACTED:中风险"), now);
        SecurityIncidentHandoff escalated = store.createOrGet(incident("INC-1", SecurityIncidentRisk.HIGH, "REDACTED:高风险"), now.plusSeconds(1));

        assertThat(escalated.workItemId()).isEqualTo(first.workItemId());
        assertThat(escalated.createdAt()).isEqualTo(first.createdAt());
        assertThat(escalated.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
        assertThat(escalated.safeSummary()).isEqualTo("REDACTED:高风险");
        assertThat(store.list()).containsExactly(escalated);
    }

    @Test
    void migratesAnExistingWorkItemWhenTheCanonicalIncidentIdChanges() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        SecurityIncidentHandoff first = store.createOrGet(incident("INC-OLD"), now);
        SecurityIncident merged = new SecurityIncident("INC-NEW", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.HANDOFF, now, now, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", now, "REDACTED:merged")), List.of(),
                List.of("核对安全处置手册。"), now, first.workItemId());

        SecurityIncidentHandoff migrated = store.refresh(merged, now.plusSeconds(1));

        assertThat(migrated.workItemId()).isEqualTo(first.workItemId());
        assertThat(migrated.incidentId()).isEqualTo("INC-NEW");
        assertThat(migrated.createdAt()).isEqualTo(first.createdAt());
        assertThat(store.list()).containsExactly(migrated);
    }

    private static SecurityIncident incident() {
        return incident("INC-1");
    }

    private static SecurityIncident incident(String incidentId) {
        return incident(incidentId, SecurityIncidentRisk.HIGH, "REDACTED: safe");
    }

    private static SecurityIncident incident(String incidentId, SecurityIncidentRisk risk, String summary) {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident(incidentId, "PARK-A", "A1", "ACCESS", risk,
                SecurityIncidentStatus.OPEN, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, summary)), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }
}
