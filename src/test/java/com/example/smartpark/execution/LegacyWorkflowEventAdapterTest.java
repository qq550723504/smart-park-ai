package com.example.smartpark.execution;

import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.workflow.WorkflowEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LegacyWorkflowEventAdapterTest {

    private static final AtomicInteger WORKFLOW_IDS = new AtomicInteger();

    private final InMemoryExecutionEventPublisher unified = new InMemoryExecutionEventPublisher();
    private final LegacyWorkflowEventAdapter adapter = new LegacyWorkflowEventAdapter(unified);

    @Test
    void convertsWorkflowIdToStableRunId() {
        UUID first = LegacyWorkflowEventAdapter.runIdFor("wf-123");
        UUID second = LegacyWorkflowEventAdapter.runIdFor("wf-123");
        assertThat(first).isEqualTo(second);
        assertThat(first).isNotEqualTo(LegacyWorkflowEventAdapter.runIdFor("wf-other"));
    }

    @Test
    void preservesSequenceAndSafeSummaryForEveryLegacyEventType() {
        for (WorkflowEvent.EventType type : WorkflowEvent.EventType.values()) {
            String summary = type == WorkflowEvent.EventType.TOOL_CALLED
                    ? "AlertPort.getAlert"
                    : "alert workflow started";
            WorkflowEvent legacy = legacy(nextWorkflowId(), 1, type, summary);
            ExecutionEvent projected = adapter.project(legacy);

            assertThat(projected.scenario()).isEqualTo(ExecutionScenario.ALERT_WORKFLOW);
            assertThat(projected.sequence()).isEqualTo(1L);
            assertThat(projected.safeSummary()).isEqualTo(summary);
            assertThat(projected.timestamp()).isEqualTo(legacy.timestamp());
            assertThat(projected.eventType()).isEqualTo(expectedType(type));
        }
    }

    @Test
    void unknownSensitiveTextRemainsRedactedInProjection() {
        ExecutionEvent projected = adapter.project(
                legacy(nextWorkflowId(), 1, WorkflowEvent.EventType.NODE_STARTED, "raw internal prompt xyz"));
        assertThat(projected.safeSummary()).isEqualTo("[REDACTED]");
    }

    @Test
    void failedEventsCarryErrorPayloadWithoutVendorDetail() {
        ExecutionEvent projected = adapter.project(
                legacy(nextWorkflowId(), 1, WorkflowEvent.EventType.FAILED, "DIAGNOSIS_FAILED"));
        DisplayPayload payload = projected.displayPayload();
        assertThat(payload).isInstanceOf(DisplayPayload.ErrorPayload.class);
        assertThat(((DisplayPayload.ErrorPayload) payload).errorCode()).isEqualTo("DIAGNOSIS_FAILED");
        assertThat(projected.eventType()).isEqualTo(ExecutionEventType.FAILED);
    }

    @Test
    void projectionsLandInTheUnifiedPublisherHistory() {
        String workflowId = nextWorkflowId();
        adapter.project(legacy(workflowId, 1, WorkflowEvent.EventType.STARTED, "alert workflow started"));
        adapter.project(legacy(workflowId, 2, WorkflowEvent.EventType.COMPLETED, "workflow completed"));

        List<ExecutionEvent> history = unified.history(LegacyWorkflowEventAdapter.runIdFor(workflowId));
        assertThat(history).extracting(ExecutionEvent::sequence).containsExactly(1L, 2L);
        assertThat(history.get(history.size() - 1).eventType()).isEqualTo(ExecutionEventType.COMPLETED);
    }

    private static String nextWorkflowId() {
        return "wf-" + WORKFLOW_IDS.incrementAndGet();
    }

    private static WorkflowEvent legacy(String workflowId, long sequence, WorkflowEvent.EventType type, String summary) {
        return new WorkflowEvent(workflowId, sequence, type, "classifyAlert", Instant.parse("2026-08-24T08:00:00Z"), summary);
    }

    private static ExecutionEventType expectedType(WorkflowEvent.EventType type) {
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
}
