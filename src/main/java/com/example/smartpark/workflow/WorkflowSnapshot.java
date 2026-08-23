package com.example.smartpark.workflow;

import com.example.smartpark.model.ApprovalDecision;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.WorkOrder;
import com.example.smartpark.model.WorkflowStatus;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record WorkflowSnapshot(
        String workflowId,
        String alertId,
        WorkflowStatus status,
        Map<String, Object> statePayload,
        Diagnosis diagnosis,
        Optional<ApprovalDecision> approval,
        WorkOrder workOrder,
        List<String> errors,
        long eventSequence) {

    public WorkflowSnapshot {
        workflowId = Objects.requireNonNull(workflowId, "workflowId");
        alertId = Objects.requireNonNull(alertId, "alertId");
        status = Objects.requireNonNull(status, "status");
        statePayload = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(statePayload, "statePayload")));
        approval = Objects.requireNonNull(approval, "approval");
        errors = List.copyOf(Objects.requireNonNull(errors, "errors"));
        if (eventSequence < 0) {
            throw new IllegalArgumentException("eventSequence must not be negative");
        }
    }

    static WorkflowSnapshot from(AlertWorkflowState state) {
        WorkflowStatus status = state.status();
        return new WorkflowSnapshot(
                state.workflowId(),
                state.alertId(),
                status,
                state.snapshotPayload(),
                state.diagnosis().orElse(null),
                state.approval(),
                state.workOrder().orElse(null),
                state.errors(),
                state.eventSequence());
    }
}
