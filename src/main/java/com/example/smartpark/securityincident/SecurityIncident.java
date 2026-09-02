package com.example.smartpark.securityincident;

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
        String handoffWorkItemId) {

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
        if (eventIds.isEmpty()) throw new IllegalArgumentException("eventIds must not be empty");
        if (reviewedAt != null && status == SecurityIncidentStatus.OPEN) {
            throw new IllegalArgumentException("open incident cannot have reviewedAt");
        }
        if (handoffWorkItemId != null && status != SecurityIncidentStatus.HANDOFF) {
            throw new IllegalArgumentException("handoff id requires HANDOFF status");
        }
    }

    public SecurityIncident review(Instant at) {
        Objects.requireNonNull(at, "at");
        if (status != SecurityIncidentStatus.OPEN) return this;
        return copy(SecurityIncidentStatus.REVIEWED, at, handoffWorkItemId);
    }

    public SecurityIncident handoff(String workItemId, Instant at) {
        return copy(SecurityIncidentStatus.HANDOFF, reviewedAt == null ? at : reviewedAt, requireText(workItemId, "workItemId"));
    }

    private SecurityIncident copy(SecurityIncidentStatus nextStatus, Instant nextReviewedAt, String nextHandoffId) {
        return new SecurityIncident(incidentId, parkId, buildingId, eventType, riskLevel, nextStatus, openedAt,
                lastOccurredAt, eventIds, alertIds, evidence, timeline, recommendations, nextReviewedAt, nextHandoffId);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
