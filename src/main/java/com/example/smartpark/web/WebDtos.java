package com.example.smartpark.web;

import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkOrderStatus;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.AlertWorkflowState;
import com.example.smartpark.workflow.CustomerConversation;
import com.example.smartpark.workflow.WorkflowEvent;
import com.example.smartpark.workflow.WorkflowSnapshot;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public final class WebDtos {

    public record CustomerServiceRequest(@NotBlank @Size(max = 500) String question) { }

    public record CustomerTicketUpdateRequest(
            @NotBlank
            @jakarta.validation.constraints.Pattern(regexp = "ASSIGNED|IN_PROGRESS|WAITING_CUSTOMER|RESOLVED|CLOSED|CANCELLED")
            String status) { }

    public record CustomerConversationResponse(
            String sessionId,
            List<CustomerMessageResponse> messages,
            List<RetrievalTraceResponse> retrievals,
            boolean humanHandoff) { }

    public record CustomerMessageResponse(String role, String text, Instant createdAt) { }
    public record RetrievalTraceResponse(String query, List<String> documentIds, Instant createdAt) { }

    static CustomerConversationResponse from(CustomerConversation conversation) {
        return new CustomerConversationResponse(
                conversation.sessionId(),
                conversation.messages().stream()
                        .map(message -> new CustomerMessageResponse(message.role(), message.text(), message.createdAt()))
                        .toList(),
                conversation.retrievals().stream()
                        .map(trace -> new RetrievalTraceResponse(trace.query(), trace.documentIds(), trace.createdAt()))
                        .toList(),
                conversation.humanHandoff());
    }
    public record CustomerTicketResponse(
            String id,
            String sessionId,
            String intent,
            String status,
            String safeSummary,
            Instant createdAt) { }

    public record CustomerServiceResponse(
            String sessionId,
            String intent,
            String answer,
            List<String> knowledgeSources,
            boolean needsHuman,
            CustomerTicketResponse ticket,
            String reason,
            List<String> citationIds) { }

    static CustomerServiceResponse from(com.example.smartpark.model.customer.CustomerServiceResult result) {
        var ticket = result.ticket();
        return new CustomerServiceResponse(
                result.sessionId(), result.intent(), result.answer(), result.knowledgeSources(), result.needsHuman(),
                ticket == null ? null : new CustomerTicketResponse(
                        ticket.id(), ticket.sessionId(), ticket.intent(), ticket.status(), ticket.safeSummary(), ticket.createdAt()),
                result.reason().name(), result.citationIds());
    }

    private static final String REDACTED = "[REDACTED]";
    private static final Pattern WORKFLOW_ID = Pattern.compile(
            "(?:wf-[A-Za-z0-9._:-]{1,120}|[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12})");
    private static final Pattern ALERT_ID = Pattern.compile("ALT-[A-Z0-9-]{1,120}");
    private static final Pattern DIAGNOSIS_ID = Pattern.compile("diag-[A-Za-z0-9-]{1,120}");
    private static final Pattern DEVICE_ID = Pattern.compile("DEV-[A-Z0-9-]{1,120}");
    private static final Pattern WORK_ORDER_ID = Pattern.compile("(?:WO|MOCK-WO)-[A-Z0-9-]{1,120}");
    private static final Pattern PARK_ID = Pattern.compile("PARK-[A-Z0-9-]{1,120}");
    private static final Pattern BUILDING_ID = Pattern.compile("[A-Z][A-Z0-9-]{0,31}");
    private static final Pattern EVENT_NODE = Pattern.compile(
            "(?:workflow|classifyAlert|collectParkContext|energyAnalysis|securityReview|retrieveKnowledge|diagnoseAlert|riskGate|humanApproval|createWorkOrder|summarizeResult)");

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
            long eventSequence,
            List<String> riskReasons) {

        public WorkflowResponse {
            workflowId = safeIdentifier(workflowId, WORKFLOW_ID);
            alertId = safeIdentifier(alertId, ALERT_ID);
            status = Objects.requireNonNull(status, "status");
            errors = stableItems(errors, "Workflow error recorded");
            riskReasons = List.copyOf(Objects.requireNonNull(riskReasons, "riskReasons"));
        }
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

        public DiagnosisResponse {
            id = safeIdentifier(id, DIAGNOSIS_ID);
            alertId = safeIdentifier(alertId, ALERT_ID);
            deviceId = safeIdentifier(deviceId, DEVICE_ID);
            riskLevel = safeChoice(riskLevel, Set.of("LOW", "MEDIUM", "HIGH"));
            rootCause = "Diagnosis content withheld";
            summary = "Diagnosis content withheld";
            evidence = stableItems(evidence, "Diagnosis content withheld");
            recommendedAction = "Diagnosis content withheld";
            diagnosedAt = Objects.requireNonNull(diagnosedAt, "diagnosedAt");
        }
    }

    public record ApprovalResponse(
            String decision,
            String reviewer,
            String comment,
            Instant decidedAt) {

        public ApprovalResponse {
            decision = safeChoice(decision, Set.of("APPROVED", "REJECTED"));
            reviewer = "Operator identity withheld";
            comment = "Operator comment recorded";
            decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
        }
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

        public WorkOrderResponse {
            id = safeIdentifier(id, WORK_ORDER_ID);
            workflowId = safeIdentifier(workflowId, WORKFLOW_ID);
            parkId = safeIdentifier(parkId, PARK_ID);
            buildingId = safeIdentifier(buildingId, BUILDING_ID);
            deviceId = safeIdentifier(deviceId, DEVICE_ID);
            alertId = safeIdentifier(alertId, ALERT_ID);
            summary = "Work order content withheld";
            riskLevel = safeChoice(riskLevel, Set.of("LOW", "MEDIUM", "HIGH"));
                status = safeChoice(status, java.util.Arrays.stream(WorkOrderStatus.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            evidence = stableItems(evidence, "Work order content withheld");
            createdAt = Objects.requireNonNull(createdAt, "createdAt");
            updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        }
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

    public record WorkflowObservabilityResponse(
            String workflowId,
            long totalEvents,
            long toolCalls,
            List<String> tools,
            List<String> failedNodes) { }

    public record WorkflowEventDto(
            String eventId,
            String type,
            String node,
            long sequence,
            Instant timestamp,
            String redactedSummary) {

        public WorkflowEventDto {
            eventId = Long.toString(sequence);
            type = safeChoice(type, java.util.Arrays.stream(WorkflowEvent.EventType.values())
                    .map(Enum::name)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet()));
            node = safeIdentifier(node, EVENT_NODE);
            timestamp = Objects.requireNonNull(timestamp, "timestamp");
            redactedSummary = WorkflowEvent.redact(
                    Objects.requireNonNull(redactedSummary, "redactedSummary"));
        }
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
                snapshot.eventSequence(),
                riskReasons(snapshot));
    }

    private static List<String> riskReasons(WorkflowSnapshot snapshot) {
        Object value = snapshot.statePayload().get(AlertWorkflowState.RISK_REASONS);
        if (!(value instanceof List<?> values)) return List.of();
        return values.stream().map(String::valueOf).toList();
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

    private static String safeIdentifier(String value, Pattern pattern) {
        return value != null && pattern.matcher(value).matches() ? value : REDACTED;
    }

    private static String safeChoice(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : REDACTED;
    }

    private static List<String> stableItems(List<String> values, String summary) {
        List<String> required = List.copyOf(Objects.requireNonNull(values, "values"));
        return IntStream.range(0, required.size())
                .mapToObj(ignored -> summary)
                .toList();
    }
}
