package com.example.smartpark.web;

import com.example.smartpark.model.ApprovalDecision;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.WorkOrder;
import com.example.smartpark.model.WorkflowStatus;
import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public final class WebDtos {

    private WebDtos() {
    }

    public record WorkflowResponse(
            String workflowId,
            String alertId,
            WorkflowStatus status,
            DiagnosisResponse diagnosis,
            ApprovalResponse approval,
            WorkOrderResponse workOrder,
            List<String> errors,
            long eventSequence) {
    }

    public record DiagnosisResponse(
            String id,
            String alertId,
            String deviceId,
            String riskLevel,
            String rootCause,
            String summary,
            List<String> evidence,
            String recommendedAction,
            double confidence,
            Instant diagnosedAt) {
    }

    public record ApprovalResponse(
            String decision,
            String reviewer,
            String comment,
            Instant decidedAt) {
    }

    public record WorkOrderResponse(
            String id,
            String workflowId,
            String parkId,
            String buildingId,
            String deviceId,
            String alertId,
            String summary,
            String riskLevel,
            String status,
            ApprovalResponse approval,
            List<String> evidence,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ApprovalRequest(
            @NotNull ApprovalAction decision,
            @NotBlank String reviewer,
            @NotBlank String comment,
            @NotBlank String idempotencyKey) {

        ApprovalDecision toDomain(Instant decidedAt) {
            return new ApprovalDecision(
                    decision.toDomain(),
                    reviewer.trim(),
                    comment.trim(),
                    idempotencyKey.trim(),
                    decidedAt);
        }
    }

    public enum ApprovalAction {
        APPROVE(ApprovalDecision.Decision.APPROVED),
        REJECT(ApprovalDecision.Decision.REJECTED);

        private final ApprovalDecision.Decision domainDecision;

        ApprovalAction(ApprovalDecision.Decision domainDecision) {
            this.domainDecision = domainDecision;
        }

        ApprovalDecision.Decision toDomain() {
            return domainDecision;
        }
    }

    public record WorkflowEventDto(
            String eventId,
            String type,
            String node,
            long sequence,
            Instant timestamp,
            String redactedSummary) {
    }

    public record ApiError(
            int status,
            String error,
            String message,
            Instant timestamp) {
    }

    static WorkflowResponse from(WorkflowSnapshot snapshot) {
        return new WorkflowResponse(
                snapshot.workflowId(),
                snapshot.alertId(),
                snapshot.status(),
                diagnosis(snapshot.diagnosis()),
                snapshot.approval().map(WebDtos::approval).orElse(null),
                workOrder(snapshot.workOrder()),
                snapshot.errors(),
                snapshot.eventSequence());
    }

    static WorkflowEventDto from(WorkflowEvent event) {
        return new WorkflowEventDto(
                Long.toString(event.sequence()),
                event.eventType().name(),
                event.node(),
                event.sequence(),
                event.timestamp(),
                event.redactedSummary());
    }

    private static DiagnosisResponse diagnosis(Diagnosis diagnosis) {
        if (diagnosis == null) {
            return null;
        }
        return new DiagnosisResponse(
                diagnosis.id(),
                diagnosis.alertId(),
                diagnosis.deviceId(),
                diagnosis.riskLevel().name(),
                diagnosis.rootCause(),
                diagnosis.summary(),
                diagnosis.evidence(),
                diagnosis.recommendedAction(),
                diagnosis.confidence(),
                diagnosis.diagnosedAt());
    }

    private static ApprovalResponse approval(ApprovalDecision approval) {
        return new ApprovalResponse(
                approval.decision().name(),
                approval.reviewer(),
                approval.comment(),
                approval.decidedAt());
    }

    private static WorkOrderResponse workOrder(WorkOrder workOrder) {
        if (workOrder == null) {
            return null;
        }
        return new WorkOrderResponse(
                workOrder.id(),
                workOrder.workflowId(),
                workOrder.parkId(),
                workOrder.buildingId(),
                workOrder.deviceId(),
                workOrder.alertId(),
                workOrder.summary(),
                workOrder.riskLevel().name(),
                workOrder.status().name(),
                workOrder.approvalDecision().map(WebDtos::approval).orElse(null),
                workOrder.evidence(),
                workOrder.createdAt(),
                workOrder.updatedAt());
    }
}
