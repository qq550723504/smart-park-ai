package com.example.smartpark.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record WorkflowEvent(
        String workflowId,
        long sequence,
        EventType eventType,
        String node,
        Instant timestamp,
        String redactedSummary) {

    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)\\b(?:api[-_]?key|authorization|token|prompt|provider[-_]?(?:response|payload))"
                    + "\\b\\s*[:=]\\s*(?:Bearer\\s+)?[^\\s,;]+");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)\\bBearer\\s+[^\\s,;]+");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("\\bsk-[A-Za-z0-9_-]+");

    public WorkflowEvent {
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        eventType = Objects.requireNonNull(eventType, "eventType");
        node = Objects.requireNonNull(node, "node");
        timestamp = Objects.requireNonNull(timestamp, "timestamp");
        redactedSummary = redact(Objects.requireNonNull(redactedSummary, "redactedSummary"));
    }

    static String redact(String summary) {
        String redacted = SENSITIVE_ASSIGNMENT.matcher(summary).replaceAll("[REDACTED]");
        redacted = BEARER_TOKEN.matcher(redacted).replaceAll("[REDACTED]");
        return OPENAI_STYLE_KEY.matcher(redacted).replaceAll("[REDACTED]");
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
