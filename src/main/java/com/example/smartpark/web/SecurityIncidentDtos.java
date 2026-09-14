package com.example.smartpark.web;

import com.example.smartpark.model.security.SecurityDispositionRecord;
import com.example.smartpark.model.security.SecurityDispositionSource;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentEvidence;
import com.example.smartpark.securityincident.SecurityIncidentPage;
import com.example.smartpark.securityincident.SecurityIncidentTimelineEntry;

import java.util.LinkedHashMap;
import java.util.Map;

final class SecurityIncidentDtos {
    private SecurityIncidentDtos() { }

    /** Optional review body; empty body keeps legacy {@code CONFIRMED_INCIDENT} behaviour. */
    record ReviewRequest(String disposition) {
    }

    static Map<String, Object> page(SecurityIncidentPage page) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("items", page.items().stream().map(SecurityIncidentDtos::summary).toList());
        dto.put("total", page.total());
        return dto;
    }

    static Map<String, Object> detail(SecurityIncident incident) {
        Map<String, Object> dto = summary(incident);
        dto.put("eventIds", incident.eventIds());
        dto.put("alertIds", incident.alertIds());
        dto.put("evidence", incident.evidence().stream().map(SecurityIncidentDtos::evidence).toList());
        dto.put("timeline", incident.timeline().stream().map(SecurityIncidentDtos::timeline).toList());
        dto.put("recommendations", incident.recommendations());
        if (incident.reviewedAt() != null) dto.put("reviewedAt", incident.reviewedAt().toString());
        if (incident.handoffWorkItemId() != null) dto.put("handoffWorkItemId", incident.handoffWorkItemId());
        return dto;
    }

    private static Map<String, Object> summary(SecurityIncident incident) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("incidentId", incident.incidentId());
        dto.put("parkId", incident.parkId());
        dto.put("buildingId", incident.buildingId());
        dto.put("eventType", incident.eventType());
        dto.put("riskLevel", incident.riskLevel().name());
        dto.put("status", incident.status().name());
        dto.put("openedAt", incident.openedAt().toString());
        dto.put("lastOccurredAt", incident.lastOccurredAt().toString());
        dto.put("eventCount", incident.eventIds().size());
        dto.put("alertCount", incident.alertIds().size());
        dto.put("summary", incident.summary());
        dto.put("disposition", incident.disposition().name());
        SecurityDispositionRecord record = incident.dispositionRecord();
        if (record.source() != SecurityDispositionSource.NONE) {
            dto.put("dispositionSource", record.source().name());
        }
        if (record.decidedAt() != null) {
            dto.put("dispositionDecidedAt", record.decidedAt().toString());
        }
        return dto;
    }

    private static Map<String, Object> evidence(SecurityIncidentEvidence evidence) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("sourceId", evidence.sourceId());
        dto.put("occurredAt", evidence.occurredAt().toString());
        dto.put("summary", evidence.summary());
        if (evidence.rawEventType() != null) dto.put("rawEventType", evidence.rawEventType());
        if (evidence.sourceType() != null) dto.put("sourceType", evidence.sourceType());
        if (evidence.eventSourceId() != null) dto.put("eventSourceId", evidence.eventSourceId());
        if (evidence.severity() != null) dto.put("severity", evidence.severity());
        if (evidence.confidence() != null) dto.put("confidence", evidence.confidence());
        return dto;
    }

    private static Map<String, Object> timeline(SecurityIncidentTimelineEntry entry) {
        return Map.of("sourceType", entry.sourceType(), "sourceId", entry.sourceId(),
                "occurredAt", entry.occurredAt().toString(), "label", entry.label());
    }
}
