package com.example.smartpark.securityincident;

public record SecurityIncidentQuery(SecurityIncidentStatus status, int offset, int limit) {
    public static final int MAX_LIMIT = 100;

    public SecurityIncidentQuery(SecurityIncidentStatus status, int limit) {
        this(status, 0, limit);
    }

    public SecurityIncidentQuery {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
