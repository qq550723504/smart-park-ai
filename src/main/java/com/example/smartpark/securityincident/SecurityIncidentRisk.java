package com.example.smartpark.securityincident;

public enum SecurityIncidentRisk {
    LOW,
    MEDIUM,
    HIGH;

    public boolean isHighRisk() {
        return this == HIGH;
    }
}
