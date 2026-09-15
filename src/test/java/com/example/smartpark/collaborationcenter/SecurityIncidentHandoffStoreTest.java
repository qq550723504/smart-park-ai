package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentRisk;
import com.example.smartpark.securityincident.SecurityIncidentStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityIncidentHandoffStoreTest {
    @Test
    void rejectsHandoffSummariesThatAreNotRedacted() {
        assertThatThrownBy(() -> new SecurityIncidentHandoff("WI:UNSAFE", "INC-1", "PARK-A", "A1",
                SecurityIncidentRisk.HIGH, "raw adapter summary", Instant.parse("2026-09-02T08:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("safeSummary");
    }

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
        assertThat(escalated.updatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(escalated.riskLevel()).isEqualTo(SecurityIncidentRisk.HIGH);
        assertThat(escalated.safeSummary()).isEqualTo("REDACTED:高风险");
        assertThat(store.list()).containsExactly(escalated);
    }

    @Test
    void preservesProjectionUpdateTimeWhenTheProjectedDataDoesNotChange() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        SecurityIncidentHandoff first = store.createOrGet(incident("INC-1", SecurityIncidentRisk.MEDIUM, "REDACTED:中风险"), now);
        SecurityIncidentHandoff changed = store.createOrGet(incident("INC-1", SecurityIncidentRisk.HIGH, "REDACTED:高风险"), now.plusSeconds(1));
        SecurityIncidentHandoff unchanged = store.createOrGet(incident("INC-1", SecurityIncidentRisk.HIGH, "REDACTED:高风险"), now.plusSeconds(2));

        assertThat(changed.createdAt()).isEqualTo(first.createdAt());
        assertThat(changed.updatedAt()).isEqualTo(now.plusSeconds(1));
        assertThat(unchanged.updatedAt()).isEqualTo(changed.updatedAt());
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

    @Test
    void evictsByCreationTimeAfterAHandOffIsRekeyed() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(2);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        SecurityIncidentHandoff old = store.createOrGet(incident("INC-OLD"), now);
        SecurityIncidentHandoff newer = store.createOrGet(incident("INC-NEW"), now.plusSeconds(1));
        SecurityIncident migratedIncident = new SecurityIncident("INC-MIGRATED", "PARK-A", "A1", "ACCESS",
                SecurityIncidentRisk.HIGH, SecurityIncidentStatus.HANDOFF, now, now, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", now, "REDACTED:migrated")), List.of(),
                List.of("核对安全处置手册。"), now, old.workItemId());
        store.refresh(migratedIncident, now.plusSeconds(2));
        SecurityIncidentHandoff latest = store.createOrGet(incident("INC-LATEST"), now.plusSeconds(3));

        assertThat(store.list()).extracting(SecurityIncidentHandoff::workItemId)
                .containsExactly(newer.workItemId(), latest.workItemId());
    }

    @Test
    void advancesProjectionUpdateTimeWhenOnlyTheDispositionChanges() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        SecurityDispositionRecord confirmed = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1", now);
        SecurityDispositionRecord corrected = new SecurityDispositionRecord(SecurityDisposition.FALSE_POSITIVE,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-2", "2026.10", "evt-2",
                now.plusSeconds(120));

        SecurityIncidentHandoff first = store.createOrGet(reviewedIncident("INC-1", confirmed), now);
        SecurityIncidentHandoff changed = store.createOrGet(reviewedIncident("INC-1", corrected), now.plusSeconds(60));

        assertThat(first.dispositionRecord()).isEqualTo(confirmed);
        assertThat(changed.dispositionRecord()).isEqualTo(corrected);
        assertThat(changed.updatedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(changed.createdAt()).isEqualTo(first.createdAt());
    }

    @Test
    void keepsProjectionUpdateTimeWhenARedundantSourceDispositionRepeats() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        SecurityDispositionRecord confirmed = new SecurityDispositionRecord(SecurityDisposition.CONFIRMED_INCIDENT,
                SecurityDispositionSource.REGISTERED_MODEL, null, "model-1", "2026.09", "evt-1", now);

        SecurityIncidentHandoff first = store.createOrGet(reviewedIncident("INC-1", confirmed), now);
        SecurityIncidentHandoff repeated = store.createOrGet(reviewedIncident("INC-1", confirmed), now.plusSeconds(60));

        assertThat(repeated.updatedAt()).isEqualTo(first.updatedAt());
    }

    @Test
    void advancesProjectionUpdateTimeWhenOnlyTheOccurrenceTimeChanges() {
        SecurityIncidentHandoffStore store = new SecurityIncidentHandoffStore(10);
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        Instant occurrence = Instant.parse("2026-09-02T08:00:00Z");

        SecurityIncidentHandoff first = store.createOrGet(incidentAt("INC-1", occurrence), now);
        SecurityIncidentHandoff corrected = store.createOrGet(incidentAt("INC-1", occurrence.plusSeconds(300)),
                now.plusSeconds(60));

        assertThat(corrected.lastOccurredAt()).isEqualTo(occurrence.plusSeconds(300));
        assertThat(corrected.updatedAt()).isEqualTo(now.plusSeconds(60));
        assertThat(corrected.createdAt()).isEqualTo(first.createdAt());
    }

    private static SecurityIncident incident() {
        return incident("INC-1");
    }

    private static SecurityIncident incident(String incidentId) {
        return incident(incidentId, SecurityIncidentRisk.HIGH, "REDACTED: safe");
    }

    private static SecurityIncident incident(String incidentId, SecurityIncidentRisk risk, String summary) {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return incidentAt(incidentId, at, risk, summary);
    }

    private static SecurityIncident incidentAt(String incidentId, Instant lastOccurredAt) {
        return incidentAt(incidentId, lastOccurredAt, SecurityIncidentRisk.HIGH, "REDACTED: safe");
    }

    private static SecurityIncident incidentAt(String incidentId, Instant lastOccurredAt, SecurityIncidentRisk risk,
                                               String summary) {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident(incidentId, "PARK-A", "A1", "ACCESS", risk,
                SecurityIncidentStatus.OPEN, at, lastOccurredAt, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, summary)), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }

    private static SecurityIncident reviewedIncident(String incidentId, SecurityDispositionRecord record) {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident(incidentId, "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.REVIEWED, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe")), List.of(),
                List.of("核对安全处置手册。"), record.decidedAt(), null, record.disposition(), record);
    }
}
