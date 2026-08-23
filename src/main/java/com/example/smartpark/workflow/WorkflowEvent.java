package com.example.smartpark.workflow;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record WorkflowEvent(
        String workflowId,
        long sequence,
        EventType eventType,
        String node,
        Instant timestamp,
        String redactedSummary) {

    private static final Set<String> SAFE_SUMMARIES = Set.of(
            "alert workflow started",
            "workflow completed",
            "workflow rejected",
            "operator approval resumed workflow",
            "waiting for operator approval",
            "operator decision recorded",
            "ALERT_LOOKUP_FAILED",
            "CLASSIFICATION_FAILED",
            "PARK_CONTEXT_FAILED",
            "KNOWLEDGE_RETRIEVAL_FAILED",
            "DIAGNOSIS_FAILED",
            "WORK_ORDER_FAILED",
            "APPROVAL_FAILED",
            "WORKFLOW_FAILED",
            "AlertPort.getAlert",
            "DevicePort.getDevice",
            "AlertPort.findHistory",
            "WorkOrderPort.findByWorkflowId",
            "WorkOrderPort.create",
            "KnowledgePort.search",
            "EnergyPort.getLatestEnergyReading",
            "SecurityPort.getEvent",
            "AgentTool.lookupDeviceStatus",
            "AgentTool.lookupAlert",
            "AgentTool.lookupAlertHistory",
            "AgentTool.lookupWorkOrders",
            "AgentTool.searchParkKnowledge");
    private static final Set<String> SAFE_NODES = Set.of(
            "classifyAlert",
            "collectParkContext",
            "energyAnalysis",
            "securityReview",
            "retrieveKnowledge",
            "diagnoseAlert",
            "riskGate",
            "humanApproval",
            "createWorkOrder",
            "summarizeResult");

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

    public static String redact(String summary) {
        if (SAFE_SUMMARIES.contains(summary)) {
            return summary;
        }
        for (String node : SAFE_NODES) {
            if (summary.equals(node + " started") || summary.equals(node + " completed")) {
                return summary;
            }
        }
        return "[REDACTED]";
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
