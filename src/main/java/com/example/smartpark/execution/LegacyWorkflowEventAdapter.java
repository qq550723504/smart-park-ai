package com.example.smartpark.execution;

import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.workflow.WorkflowEvent;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One-way compatibility projection: legacy alert {@link WorkflowEvent} instances
 * appear in the unified execution trace without changing the legacy REST/SSE contract.
 */
@Component
public class LegacyWorkflowEventAdapter {

    private final ExecutionEventPublisher publisher;

    public LegacyWorkflowEventAdapter(ExecutionEventPublisher publisher) {
        this.publisher = publisher;
    }

    private static final String REJECTED_SUMMARY = "workflow rejected";

    /** Deterministic workflowId → runId mapping so both traces address the same run. */
    public static UUID runIdFor(String workflowId) {
        return UUID.nameUUIDFromBytes(
                ("smart-park-alert-workflow:" + workflowId).getBytes(StandardCharsets.UTF_8));
    }

    public ExecutionEvent project(WorkflowEvent legacy) {
        boolean rejectedCompletion = legacy.eventType() == WorkflowEvent.EventType.COMPLETED
                && REJECTED_SUMMARY.equals(legacy.redactedSummary());
        return publisher.publish(new ExecutionEvent(
                UUID.randomUUID(),
                runIdFor(legacy.workflowId()),
                legacy.sequence(),
                legacy.timestamp(),
                ExecutionScenario.ALERT_WORKFLOW,
                actorFor(legacy),
                stageFor(legacy, rejectedCompletion),
                eventTypeFor(legacy.eventType(), rejectedCompletion),
                statusFor(legacy, rejectedCompletion),
                legacy.redactedSummary(),
                payloadFor(legacy)));
    }

    private static String actorFor(WorkflowEvent legacy) {
        return legacy.eventType() == WorkflowEvent.EventType.TOOL_CALLED ? "tool" : "alert workflow";
    }

    private static ExecutionStage stageFor(WorkflowEvent legacy, boolean rejectedCompletion) {
        if (rejectedCompletion) {
            return ExecutionStage.FAILURE;
        }
        return switch (legacy.eventType()) {
            case STARTED -> ExecutionStage.INITIALIZATION;
            case NODE_STARTED, NODE_COMPLETED, TOOL_CALLED -> ExecutionStage.TOOL_EXECUTION;
            case PAUSED, RESUMED -> ExecutionStage.HUMAN_APPROVAL;
            case FAILED -> ExecutionStage.FAILURE;
            case COMPLETED -> ExecutionStage.COMPLETION;
        };
    }

    private static ExecutionEventType eventTypeFor(WorkflowEvent.EventType type, boolean rejectedCompletion) {
        if (type == WorkflowEvent.EventType.COMPLETED && rejectedCompletion) {
            // A rejected operator intervention is a failed outcome in the
            // unified contract — never a successful completion.
            return ExecutionEventType.FAILED;
        }
        return switch (type) {
            case STARTED -> ExecutionEventType.RUN_STARTED;
            case NODE_STARTED -> ExecutionEventType.NODE_STARTED;
            case NODE_COMPLETED -> ExecutionEventType.NODE_COMPLETED;
            case TOOL_CALLED -> ExecutionEventType.TOOL_CALL_COMPLETED;
            case PAUSED -> ExecutionEventType.PAUSED;
            case RESUMED -> ExecutionEventType.RESUMED;
            case FAILED -> ExecutionEventType.FAILED;
            case COMPLETED -> ExecutionEventType.COMPLETED;
        };
    }

    private static com.example.smartpark.execution.model.ExecutionStatus statusFor(WorkflowEvent legacy,
                                                                                boolean rejectedCompletion) {
        if (rejectedCompletion) {
            return com.example.smartpark.execution.model.ExecutionStatus.FAILED;
        }
        return switch (legacy.eventType()) {
            case FAILED -> com.example.smartpark.execution.model.ExecutionStatus.FAILED;
            case COMPLETED -> com.example.smartpark.execution.model.ExecutionStatus.SUCCEEDED;
            default -> com.example.smartpark.execution.model.ExecutionStatus.RUNNING;
        };
    }

    private static DisplayPayload payloadFor(WorkflowEvent legacy) {
        return switch (legacy.eventType()) {
            case TOOL_CALLED -> new DisplayPayload.ToolCallPayload(
                    legacy.redactedSummary(), java.util.Map.of(), "");
            case FAILED -> DisplayPayload.error(
                    ExecutionStage.FAILURE, legacy.redactedSummary(), false, legacy.redactedSummary());
            default -> null;
        };
    }
}
