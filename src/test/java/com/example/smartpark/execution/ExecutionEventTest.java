package com.example.smartpark.execution;

import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionEventTest {

    private static final UUID RUN_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void rejectsBlankActorAndNullSafeSummary() {
        assertThatThrownBy(() -> new ExecutionEvent(UUID.randomUUID(), RUN_ID, 1, Instant.now(),
                ExecutionScenario.VOICE, " ", ExecutionStage.INPUT_CAPTURE,
                ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "ok", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExecutionEvent(UUID.randomUUID(), RUN_ID, 1, Instant.now(),
                ExecutionScenario.VOICE, "system", ExecutionStage.INPUT_CAPTURE,
                ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNegativeSequence() {
        assertThatThrownBy(() -> new ExecutionEvent(UUID.randomUUID(), RUN_ID, -1, Instant.now(),
                ExecutionScenario.VOICE, "system", ExecutionStage.INPUT_CAPTURE,
                ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "started", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsUnknownScenariosAtCompileTimeThroughEnum() {
        assertThat(Set.of(ExecutionScenario.values()))
                .containsExactlyInAnyOrder(ExecutionScenario.VOICE,
                        ExecutionScenario.EXPERT_COLLABORATION,
                        ExecutionScenario.OPERATIONS_ANALYSIS,
                        ExecutionScenario.ALERT_WORKFLOW);
    }

    @Test
    void rejectsSensitiveDisplayFields() {
        assertThatThrownBy(() -> DisplayPayload.toolCall(
                "EnergyQueryTool", Map.of("apiKey", "secret")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DisplayPayload.toolCall(
                "EnergyQueryTool", Map.of("connectionString", "jdbc:postgresql://db")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DisplayPayload.toolCall(
                "EnergyQueryTool", Map.of("Authorization", "Bearer x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsPlainSafeToolArguments() {
        DisplayPayload.ToolCallPayload payload = DisplayPayload.toolCall(
                "EnergyQueryTool", Map.of("buildingId", "B1"));
        assertThat(payload.toolName()).isEqualTo("EnergyQueryTool");
        assertThat(payload.safeArguments()).isEqualTo(Map.of("buildingId", "B1"));
    }

    @Test
    void errorPayloadOnlyCarriesTheFourSafeFields() {
        DisplayPayload.ErrorPayload payload = DisplayPayload.error(
                ExecutionStage.TOOL_EXECUTION, "TOOL_TIMEOUT", true, "tool timed out");
        assertThat(payload.stage()).isEqualTo(ExecutionStage.TOOL_EXECUTION);
        assertThat(payload.errorCode()).isEqualTo("TOOL_TIMEOUT");
        assertThat(payload.retryable()).isTrue();
        assertThat(payload.safeMessage()).isEqualTo("tool timed out");
    }

    @Test
    void displayPayloadRemainsAClosedUnion() {
        assertThat(DisplayPayload.class.getPermittedSubclasses())
                .extracting(clazz -> clazz.getSimpleName())
                .containsExactlyInAnyOrder("TextPayload", "ToolCallPayload", "ExpertHandoffPayload",
                        "SqlPayload",
                    "TimeRangePayload", "ChartPayload", "AudioPayload", "ErrorPayload");
    }

    @Test
    void eventTypeCoversTerminalStates() {
        assertThat(ExecutionEventType.COMPLETED).isNotNull();
        assertThat(ExecutionEventType.FAILED).isNotNull();
        assertThat(ExecutionEventType.INTERRUPTED).isNotNull();
    }

    private static ExecutionEvent event() {
        return new ExecutionEvent(UUID.randomUUID(), RUN_ID, 1, Instant.now(),
                ExecutionScenario.VOICE, "system", ExecutionStage.INITIALIZATION,
                ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "session created", null);
    }
}
