package com.example.smartpark.port.collaboration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.example.smartpark.securityincident.SecurityIncidentRisk;

public record SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                      SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                      Instant reviewedAt, Instant updatedAt, String eventType, List<String> eventIds) {
    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, null, createdAt,
                null, List.of());
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, createdAt,
                null, List.of());
    }

    public SecurityIncidentHandoff(String workItemId, String incidentId, String parkId, String buildingId,
                                   SecurityIncidentRisk riskLevel, String safeSummary, Instant createdAt,
                                   Instant reviewedAt, Instant updatedAt) {
        this(workItemId, incidentId, parkId, buildingId, riskLevel, safeSummary, createdAt, reviewedAt, updatedAt,
                null, List.of());
    }

    public SecurityIncidentHandoff {
        workItemId = requireText(workItemId, "workItemId");
        incidentId = requireText(incidentId, "incidentId");
        parkId = requireText(parkId, "parkId");
        buildingId = requireText(buildingId, "buildingId");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        safeSummary = requireText(safeSummary, "safeSummary");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        eventType = eventType == null || eventType.isBlank() ? null : eventType.trim();
        eventIds = eventIds == null ? List.of() : List.copyOf(eventIds);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
