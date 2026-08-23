package com.example.smartpark.model.common;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Objects;

public record WorkOrder(
        String id,
        String workflowId,
        String parkId,
        String buildingId,
        String deviceId,
        String alertId,
        String summary,
        RiskLevel riskLevel,
        WorkflowStatus status,
        Optional<ApprovalDecision> approvalDecision,
        List<String> evidence,
        Instant createdAt,
        Instant updatedAt) {

    public WorkOrder {
        id = Objects.requireNonNull(id, "id");
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        parkId = Objects.requireNonNull(parkId, "parkId");
        buildingId = Objects.requireNonNull(buildingId, "buildingId");
        deviceId = Objects.requireNonNull(deviceId, "deviceId");
        alertId = Objects.requireNonNull(alertId, "alertId");
        summary = Objects.requireNonNull(summary, "summary");
        riskLevel = Objects.requireNonNull(riskLevel, "riskLevel");
        status = Objects.requireNonNull(status, "status");
        approvalDecision = Objects.requireNonNull(approvalDecision, "approvalDecision");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
