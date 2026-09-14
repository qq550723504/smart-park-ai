package com.example.smartpark.port.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.example.smartpark.model.security.RedactedEvidencePolicy;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.securityincident.SecurityIncidentRisk;

/**
 * Projection of a handed-off security incident. The event identity list is
 * source-qualified so a retained handoff can be correlated against fresh
 * evidence without conflating two sources that reuse the same local event id,
 * and the projected occurrence time lets a retained-only handoff (whose incident
 * was evicted from the bounded incident store) still reserve a legacy alias for
 * the concrete source whose event time matches it. Because a correlation window
 * can hold events at different times, the per-identity occurrence time is
 * projected as well, so an alias is ranked against the time of its own identity
 * rather than the incident-wide latest event.
 */
public record SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                      SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                      Instant reviewedAt, Instant updatedAt, String eventType,
                                      List<SecurityEventIdentity> eventIdentities,
                                      SecurityDispositionRecord dispositionRecord,
                                      Instant lastOccurredAt,
                                      Map<SecurityEventIdentity, Instant> identityOccurredAt) {
    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, null, createdAt,
                null, List.of(), SecurityDispositionRecord.unreviewed(), null);
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, createdAt,
                null, List.of(), SecurityDispositionRecord.unreviewed(), null);
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt, Instant updatedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, updatedAt,
                null, List.of(), SecurityDispositionRecord.unreviewed(), null);
    }

    /** Compatibility projection without the occurrence time; such a handoff cannot reserve an alias. */
    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt, Instant updatedAt, String eventType,
                                   List<SecurityEventIdentity> eventIdentities,
                                   SecurityDispositionRecord dispositionRecord) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, updatedAt,
                eventType, eventIdentities, dispositionRecord, null);
    }

    /** Compatibility projection with an incident-wide occurrence time but no per-identity times. */
    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt, Instant updatedAt, String eventType,
                                   List<SecurityEventIdentity> eventIdentities,
                                   SecurityDispositionRecord dispositionRecord, Instant lastOccurredAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, updatedAt,
                eventType, eventIdentities, dispositionRecord, lastOccurredAt, Map.of());
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
        identityOccurredAt = identityOccurredAt == null ? Map.of() : Map.copyOf(identityOccurredAt);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
