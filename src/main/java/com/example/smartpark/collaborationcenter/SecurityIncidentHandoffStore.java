package com.example.smartpark.collaborationcenter;

import com.example.smartpark.port.collaboration.SecurityIncidentHandoff;
import com.example.smartpark.port.collaboration.SecurityIncidentHandoffPort;
import com.example.smartpark.securityincident.SecurityIncident;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SecurityIncidentHandoffStore implements SecurityIncidentHandoffPort {
    private final int capacity;
    private final Map<String, SecurityIncidentHandoff> handoffs = new LinkedHashMap<>();

    public SecurityIncidentHandoffStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    @Override
    public synchronized SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now) {
        return handoffs.computeIfAbsent(incident.incidentId(), ignored -> {
            String workItemId = "SECURITY_INCIDENT:" + incident.incidentId();
            return new SecurityIncidentHandoff(workItemId, incident.incidentId(), incident.parkId(), incident.buildingId(),
                    incident.riskLevel(), incident.summary(), now);
        });
    }

    @Override
    public synchronized List<SecurityIncidentHandoff> list() {
        return List.copyOf(handoffs.values());
    }
}
