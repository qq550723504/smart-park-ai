package com.example.smartpark.securityincident;

public record SecurityIncidentQuery(SecurityIncidentStatus status, int limit) {
    public static final int MAX_LIMIT = 100;

    public SecurityIncidentQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
