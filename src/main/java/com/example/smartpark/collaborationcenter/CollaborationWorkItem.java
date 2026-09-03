package com.example.smartpark.collaborationcenter;

import java.time.Instant;
import java.util.Objects;

public record CollaborationWorkItem(
        String id,
        Source source,
        Status status,
        Priority priority,
        String title,
        String safeSummary,
        String parkId,
        String buildingId,
        String deviceId,
        Instant updatedAt,
        Instant openedAt,
        Instant slaDueAt,
        SlaState slaState,
        String detailPath,
        String incidentId) {

    public CollaborationWorkItem {
        id = requireText(id, "id");
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        priority = Objects.requireNonNull(priority, "priority");
        title = requireText(title, "title");
        safeSummary = requireText(safeSummary, "safeSummary");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        slaState = Objects.requireNonNull(slaState, "slaState");
        detailPath = requireText(detailPath, "detailPath");
    }

    public CollaborationWorkItem(String id, Source source, Status status, Priority priority, String title,
                                 String safeSummary, String parkId, String buildingId, String deviceId,
                                 Instant updatedAt, String detailPath) {
        this(id, source, status, priority, title, safeSummary, parkId, buildingId, deviceId,
                updatedAt, null, null, SlaState.NOT_APPLICABLE, detailPath, null);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Source { ALERT_WORKFLOW, CUSTOMER_TICKET, SECURITY_INCIDENT }

    public enum Priority { HIGH, NORMAL }

    public enum SlaState { ON_TRACK, DUE_SOON, OVERDUE, COMPLETED, NOT_APPLICABLE }

    public enum Status {
        RUNNING,
        WAITING_APPROVAL,
        COMPLETED,
        REJECTED,
        FAILED,
        WORK_ORDER_FAILED,
        WAITING_AGENT,
        ASSIGNED,
        IN_PROGRESS,
        WAITING_CUSTOMER,
        RESOLVED,
        CLOSED,
        CANCELLED
    }
}
