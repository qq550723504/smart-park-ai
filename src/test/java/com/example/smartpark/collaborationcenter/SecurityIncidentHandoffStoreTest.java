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

    private static SecurityIncident incident() {
        Instant at = Instant.parse("2026-09-02T08:00:00Z");
        return new SecurityIncident("INC-1", "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.HIGH,
                SecurityIncidentStatus.OPEN, at, at, List.of("SEC-1"), List.of("ALT-1"),
                List.of(new SecurityIncidentEvidence("SEC-1", at, "REDACTED: safe")), List.of(),
                List.of("核对安全处置手册。"), null, null);
    }
}
