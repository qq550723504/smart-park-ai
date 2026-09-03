package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentRisk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;

public final class SecurityIncidentHandoffStore implements SecurityIncidentHandoffPort {
    private final int capacity;
    private final Map<String, SecurityIncidentHandoff> handoffs = new LinkedHashMap<>();

    public SecurityIncidentHandoffStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
        SecurityIncidentHandoff existing = handoffs.get(incident.incidentId());
        SecurityIncidentRisk projectedRisk = existing == null
                ? incident.riskLevel() : higherRisk(existing.riskLevel(), incident.riskLevel());
        String projectedSummary = incident.summary();
        Instant updatedAt = existing == null || projectedFieldsChanged(existing, incident.incidentId(),
                existing.parkId(), existing.buildingId(), projectedRisk, projectedSummary,
                incident.eventType(), incident.eventIds())
                ? now : existing.updatedAt();
        SecurityIncidentHandoff handoff = existing == null
                ? new SecurityIncidentHandoff("SECURITY_INCIDENT:" + incident.incidentId(), incident.incidentId(),
                        incident.parkId(), incident.buildingId(), incident.riskLevel(), incident.summary(), now,
                        incident.reviewedAt(), now, incident.eventType(), incident.eventIds())
                : new SecurityIncidentHandoff(existing.workItemId(), existing.incidentId(), existing.parkId(),
                        existing.buildingId(), projectedRisk, projectedSummary, existing.createdAt(),
                        existing.reviewedAt() != null ? existing.reviewedAt() : incident.reviewedAt(), updatedAt,
                        incident.eventType(), incident.eventIds());
        handoffs.put(incident.incidentId(), handoff);
        trimToCapacity();
        return handoff;
    }

    @Override
    public synchronized SecurityIncidentHandoff refresh(SecurityIncident incident, Instant now) {
        if (incident.handoffWorkItemId() != null) {
            String existingIncidentId = handoffs.entrySet().stream()
                    .filter(entry -> incident.handoffWorkItemId().equals(entry.getValue().workItemId()))
                    .map(Map.Entry::getKey)
                    .filter(id -> !id.equals(incident.incidentId()))
                    .findFirst()
                    .orElse(null);
            if (existingIncidentId != null) {
                SecurityIncidentHandoff existing = handoffs.remove(existingIncidentId);
                SecurityIncidentRisk projectedRisk = higherRisk(existing.riskLevel(), incident.riskLevel());
                String projectedSummary = incident.summary();
                Instant updatedAt = projectedFieldsChanged(existing, incident.incidentId(), incident.parkId(),
                        incident.buildingId(), projectedRisk, projectedSummary, incident.eventType(),
                        incident.eventIds()) ? now : existing.updatedAt();
                SecurityIncidentHandoff migrated = new SecurityIncidentHandoff(existing.workItemId(),
                        incident.incidentId(), incident.parkId(), incident.buildingId(),
                        projectedRisk, projectedSummary, existing.createdAt(),
                        existing.reviewedAt() != null ? existing.reviewedAt() : incident.reviewedAt(), updatedAt,
                        incident.eventType(), incident.eventIds());
                handoffs.put(incident.incidentId(), migrated);
                trimToCapacity();
                return migrated;
            }
            if (!handoffs.containsKey(incident.incidentId())) {
                SecurityIncidentHandoff restored = new SecurityIncidentHandoff(incident.handoffWorkItemId(),
                        incident.incidentId(), incident.parkId(), incident.buildingId(), incident.riskLevel(),
                        incident.summary(), now, incident.reviewedAt(), now, incident.eventType(), incident.eventIds());
                handoffs.put(incident.incidentId(), restored);
                trimToCapacity();
                return restored;
            }
        }
        return createOrGet(incident, now);
    }

    @Override
    public synchronized void retire(String incidentId) {
        handoffs.entrySet().removeIf(entry -> entry.getKey().equals(incidentId)
                || entry.getValue().incidentId().equals(incidentId));
    }

    private void trimToCapacity() {
        while (handoffs.size() > capacity) {
            String oldest = handoffs.entrySet().stream()
                    .min(Comparator.comparing((Map.Entry<String, SecurityIncidentHandoff> entry) -> entry.getValue().createdAt())
                            .thenComparing(Map.Entry::getKey))
                    .map(Map.Entry::getKey)
                    .orElseThrow();
            handoffs.remove(oldest);
        }
    }

    private static SecurityIncidentRisk higherRisk(SecurityIncidentRisk left, SecurityIncidentRisk right) {
        return riskRank(right) > riskRank(left) ? right : left;
    }

    private static int riskRank(SecurityIncidentRisk risk) {
        return switch (risk) {
            case HIGH -> 2;
            case MEDIUM -> 1;
            case LOW -> 0;
        };
    }

    private static boolean projectedFieldsChanged(SecurityIncidentHandoff existing, String incidentId,
                                                  String parkId, String buildingId, SecurityIncidentRisk riskLevel,
                                                  String safeSummary, String eventType, List<String> eventIds) {
        return !existing.incidentId().equals(incidentId)
                || !existing.parkId().equals(parkId)
                || !existing.buildingId().equals(buildingId)
                || existing.riskLevel() != riskLevel
                || !existing.safeSummary().equals(safeSummary)
                || !java.util.Objects.equals(existing.eventType(), eventType)
                || !existing.eventIds().equals(eventIds);
    }

    @Override
    public synchronized List<SecurityIncidentHandoff> list() {
        return List.copyOf(handoffs.values());
    }
}
