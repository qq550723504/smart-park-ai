package com.example.smartpark.securityincident;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class SecurityIncidentStore {
    private final int capacity;
    private final Map<String, SecurityIncident> incidents = new LinkedHashMap<>();

    public SecurityIncidentStore(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized Optional<SecurityIncident> get(String incidentId) {
        return Optional.ofNullable(incidents.get(incidentId));
    }

    public synchronized void save(SecurityIncident incident) {
        incidents.put(incident.incidentId(), incident);
        while (incidents.size() > capacity) {
            String oldest = incidents.values().stream()
                    .min(Comparator.comparing(SecurityIncident::lastOccurredAt).thenComparing(SecurityIncident::incidentId))
                    .map(SecurityIncident::incidentId).orElseThrow();
            incidents.remove(oldest);
        }
    }

    public synchronized void remove(String incidentId) {
        incidents.remove(incidentId);
    }

    public synchronized List<SecurityIncident> findAll() {
        return List.copyOf(new ArrayList<>(incidents.values()));
    }

    public synchronized Optional<SecurityIncident> findByHandoff(String workItemId) {
        return incidents.values().stream()
                .filter(incident -> workItemId.equals(incident.handoffWorkItemId()))
                .findFirst();
    }
}
