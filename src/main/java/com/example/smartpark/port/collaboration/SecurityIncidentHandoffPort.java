package com.example.smartpark.port.collaboration;

import com.example.smartpark.securityincident.SecurityIncident;

import java.time.Instant;
import java.util.List;

public interface SecurityIncidentHandoffPort {
    SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now);

    default SecurityIncidentHandoff refresh(SecurityIncident incident, Instant now) {
        return createOrGet(incident, now);
    }

    void retire(String incidentId);

    List<SecurityIncidentHandoff> list();
}
