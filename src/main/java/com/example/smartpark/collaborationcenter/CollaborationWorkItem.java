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
        String detailPath) {

    public CollaborationWorkItem {
        id = requireText(id, "id");
        source = Objects.requireNonNull(source, "source");
        status = Objects.requireNonNull(status, "status");
        priority = Objects.requireNonNull(priority, "priority");
        title = requireText(title, "title");
        safeSummary = requireText(safeSummary, "safeSummary");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        detailPath = requireText(detailPath, "detailPath");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }

    public enum Source { ALERT_WORKFLOW, CUSTOMER_TICKET }

    public enum Priority { HIGH, NORMAL }

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
