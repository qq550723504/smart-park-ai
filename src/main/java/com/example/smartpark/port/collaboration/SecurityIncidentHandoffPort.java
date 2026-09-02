package com.example.smartpark.port.collaboration;

import com.example.smartpark.securityincident.SecurityIncident;

import java.time.Instant;
import java.util.List;

public interface SecurityIncidentHandoffPort {
    SecurityIncidentHandoff createOrGet(SecurityIncident incident, Instant now);

    List<SecurityIncidentHandoff> list();
}
