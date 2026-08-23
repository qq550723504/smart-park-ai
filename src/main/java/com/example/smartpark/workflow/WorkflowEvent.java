package com.example.smartpark.workflow;

import java.time.Instant;
import java.util.Objects;

public record WorkflowEvent(
        String workflowId,
        long sequence,
        EventType eventType,
        String node,
        Instant timestamp,
        String redactedSummary) {

    public WorkflowEvent {
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        eventType = Objects.requireNonNull(eventType, "eventType");
        node = Objects.requireNonNull(node, "node");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        redactedSummary = Objects.requireNonNull(redactedSummary, "redactedSummary");
    }

    public enum EventType {
        STARTED,
        NODE_STARTED,
        NODE_COMPLETED,
        TOOL_CALLED,
        PAUSED,
        RESUMED,
        FAILED,
        COMPLETED
    }
}
