package com.example.smartpark.port.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.example.smartpark.model.security.RedactedEvidencePolicy;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.securityincident.SecurityIncidentRisk;

/**
 * Projection of a handed-off security incident. The event identity list is
 * source-qualified so a retained handoff can be correlated against fresh
 * evidence without conflating two sources that reuse the same local event id.
 */
public record SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                      SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                      Instant reviewedAt, Instant updatedAt, String eventType,
                                      List<SecurityEventIdentity> eventIdentities,
                                      SecurityDispositionRecord dispositionRecord) {
    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, null, createdAt,
                null, List.of(), SecurityDispositionRecord.unreviewed());
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, createdAt,
                null, List.of(), SecurityDispositionRecord.unreviewed());
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt, Instant updatedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, updatedAt,
                null, List.of(), SecurityDispositionRecord.unreviewed());
    }

    public SecurityIncidentHandoff {
        workItemId = requireText(workItemId, "workItemId");
        incidentId = requireText(incidentId, "incidentId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        safeSummary = RedactedEvidencePolicy.require(safeSummary, "safeSummary");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        dispositionRecord = dispositionRecord == null ? SecurityDispositionRecord.unreviewed() : dispositionRecord;
        eventType = eventType == null || eventType.isBlank() ? null : eventType.trim();
        eventIdentities = eventIdentities == null ? List.of() : List.copyOf(eventIdentities);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
