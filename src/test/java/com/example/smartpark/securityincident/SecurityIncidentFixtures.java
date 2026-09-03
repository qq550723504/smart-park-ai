package com.example.smartpark.securityincident;

import java.time.Instant;
import java.util.List;

final class SecurityIncidentFixtures {
    private SecurityIncidentFixtures() { }

    static SecurityIncident incident(String id, Instant occurredAt) {
        return new SecurityIncident(id, "PARK-A", "A1", "ACCESS", SecurityIncidentRisk.LOW,
                SecurityIncidentStatus.OPEN, occurredAt, occurredAt, List.of("SEC-1"), List.of(),
                List.of(new SecurityIncidentEvidence("SEC-1", occurredAt, "REDACTED: safe")), List.of(),
                List.of("Verify the approved security runbook."), null, null);
    }
}
