package com.example.smartpark.securityincident;

import com.example.smartpark.model.security.SecurityDisposition;
import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityEventIdentity;
import com.example.smartpark.model.security.SecurityEventType;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record SecurityIncident(
        String incidentId,
        String parkId,
        String buildingId,
        String eventType,
        SecurityIncidentRisk riskLevel,
        SecurityIncidentStatus status,
        Instant openedAt,
        Instant lastOccurredAt,
        List<String> eventIds,
        List<String> alertIds,
        List<SecurityIncidentEvidence> evidence,
        List<SecurityIncidentTimelineEntry> timeline,
        List<String> recommendations,
        Instant reviewedAt,
        String handoffWorkItemId,
        SecurityDisposition disposition,
        SecurityDispositionRecord dispositionRecord,
        List<SecurityEventIdentity> eventIdentities,
        SecurityEventIdentity dispositionSource) {

    /**
     * Compatibility overload for incidents that do not carry a source-qualified
     * identity projection (legacy fixtures and callers that only know bare ids).
     */
    public SecurityIncident(String incidentId, String parkId, String buildingId, String eventType,
                            SecurityIncidentRisk riskLevel, SecurityIncidentStatus status, Instant openedAt,
                            Instant lastOccurredAt, List<String> eventIds, List<String> alertIds,
                            List<SecurityIncidentEvidence> evidence, List<SecurityIncidentTimelineEntry> timeline,
                            List<String> recommendations, Instant reviewedAt, String handoffWorkItemId,
                            SecurityDisposition disposition, SecurityDispositionRecord dispositionRecord) {
        this(incidentId, parkId, buildingId, eventType, riskLevel, status, openedAt, lastOccurredAt, eventIds,
                alertIds, evidence, timeline, recommendations, reviewedAt, handoffWorkItemId, disposition,
                dispositionRecord, List.of(), null);
    }

    /**
     * Compatibility overload for incidents that project event identities but predate the
     * disposition-owning event; the source of the selected decision is unknown.
     */
    public SecurityIncident(String incidentId, String parkId, String buildingId, String eventType,
                            SecurityIncidentRisk riskLevel, SecurityIncidentStatus status, Instant openedAt,
                            Instant lastOccurredAt, List<String> eventIds, List<String> alertIds,
                            List<SecurityIncidentEvidence> evidence, List<SecurityIncidentTimelineEntry> timeline,
                            List<String> recommendations, Instant reviewedAt, String handoffWorkItemId,
                            SecurityDisposition disposition, SecurityDispositionRecord dispositionRecord,
                            List<SecurityEventIdentity> eventIdentities) {
        this(incidentId, parkId, buildingId, eventType, riskLevel, status, openedAt, lastOccurredAt, eventIds,
                alertIds, evidence, timeline, recommendations, reviewedAt, handoffWorkItemId, disposition,
                dispositionRecord, eventIdentities, null);
    }

    public SecurityIncident(String incidentId, String parkId, String buildingId, String eventType,
                            SecurityIncidentRisk riskLevel, SecurityIncidentStatus status, Instant openedAt,
                            Instant lastOccurredAt, List<String> eventIds, List<String> alertIds,
                            List<SecurityIncidentEvidence> evidence, List<SecurityIncidentTimelineEntry> timeline,
                            List<String> recommendations, Instant reviewedAt, String handoffWorkItemId) {
        this(incidentId, parkId, buildingId, eventType, riskLevel, status, openedAt, lastOccurredAt, eventIds,
                alertIds, evidence, timeline, recommendations, reviewedAt, handoffWorkItemId,
                SecurityDisposition.UNREVIEWED, SecurityDispositionRecord.unreviewed());
    }

    public SecurityIncident {
        incidentId = requireText(incidentId, "incidentId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        eventType = requireText(eventType, "eventType");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        status = Objects.requireNonNull(status, "status");
        openedAt = Objects.requireNonNull(openedAt, "openedAt");
        lastOccurredAt = Objects.requireNonNull(lastOccurredAt, "lastOccurredAt");
        eventIds = List.copyOf(eventIds);
        alertIds = List.copyOf(alertIds);
        evidence = List.copyOf(evidence);
        timeline = List.copyOf(timeline);
        recommendations = List.copyOf(recommendations);
        eventIdentities = eventIdentities == null ? List.of() : List.copyOf(eventIdentities);
        disposition = Objects.requireNonNull(disposition, "disposition");
        dispositionRecord = Objects.requireNonNull(dispositionRecord, "dispositionRecord");
        if (disposition == SecurityDisposition.UNREVIEWED && dispositionSource != null) {
            throw new IllegalArgumentException("an unreviewed incident cannot have a disposition source");
        }
        if (eventIds.isEmpty()) throw new IllegalArgumentException("eventIds must not be empty");
        if (reviewedAt != null && status == SecurityIncidentStatus.OPEN) {
            throw new IllegalArgumentException("open incident cannot have reviewedAt");
        }
        if (handoffWorkItemId != null && status != SecurityIncidentStatus.HANDOFF) {
            throw new IllegalArgumentException("handoff id requires HANDOFF status");
        }
        if (dispositionRecord.disposition() != disposition) {
            throw new IllegalArgumentException("disposition record must match incident disposition");
        }
        if (disposition == SecurityDisposition.UNREVIEWED && status == SecurityIncidentStatus.OPEN) {
            // expected default state
        } else if (disposition != SecurityDisposition.UNREVIEWED && reviewedAt == null) {
            throw new IllegalArgumentException("decided disposition requires reviewedAt");
        }
    }

    /** Standardized event type; unknown legacy labels degrade to {@code UNKNOWN}. */
    public SecurityEventType standardEventType() {
        return SecurityEventType.fromRaw(eventType);
    }

    public SecurityIncident review(SecurityDispositionRecord record, Instant at) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(at, "at");
        if (status != SecurityIncidentStatus.OPEN) return this;
        if (record.disposition() == SecurityDisposition.UNREVIEWED) {
            throw new IllegalArgumentException("review requires a decided disposition");
        }
        return copy(SecurityIncidentStatus.REVIEWED, at, handoffWorkItemId, record.disposition(), record);
    }

    public SecurityIncident handoff(String workItemId, Instant at) {
        if (status != SecurityIncidentStatus.REVIEWED) {
            throw new IllegalStateException("security incident must be reviewed before handoff");
        }
        return copy(SecurityIncidentStatus.HANDOFF, reviewedAt, requireText(workItemId, "workItemId"),
                disposition, dispositionRecord);
    }

    public String summary() {
        return evidence.isEmpty() ? "REDACTED:安全事件摘要不可用" : evidence.get(0).summary();
    }

    private SecurityIncident copy(SecurityIncidentStatus nextStatus, Instant nextReviewedAt, String nextHandoffId,
                                  SecurityDisposition nextDisposition,
                                  SecurityDispositionRecord nextDispositionRecord) {
        return new SecurityIncident(incidentId, parkId, buildingId, eventType, riskLevel, nextStatus, openedAt,
                lastOccurredAt, eventIds, alertIds, evidence, timeline, recommendations, nextReviewedAt,
                nextHandoffId, nextDisposition, nextDispositionRecord, eventIdentities, dispositionSource);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
