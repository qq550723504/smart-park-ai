package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.securityincident.SecurityIncident;
import com.example.smartpark.securityincident.SecurityIncidentRisk;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Iterator;

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
        SecurityIncidentHandoff handoff = existing == null
                ? new SecurityIncidentHandoff("SECURITY_INCIDENT:" + incident.incidentId(), incident.incidentId(),
                        incident.parkId(), incident.buildingId(), incident.riskLevel(), incident.summary(), now)
                : new SecurityIncidentHandoff(existing.workItemId(), existing.incidentId(), existing.parkId(),
                        existing.buildingId(), higherRisk(existing.riskLevel(), incident.riskLevel()), incident.summary(),
                        existing.createdAt());
        handoffs.put(incident.incidentId(), handoff);
        while (handoffs.size() > capacity) {
            Iterator<String> ids = handoffs.keySet().iterator();
            ids.next();
            ids.remove();
        }
        return handoff;
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

    @Override
    public synchronized List<SecurityIncidentHandoff> list() {
        return List.copyOf(handoffs.values());
    }
}
