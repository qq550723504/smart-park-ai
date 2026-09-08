package com.example.smartpark.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OrchestrationStep(
        String id,
        OrchestrationStepType type,
        String capability,
        boolean required,
        OrchestrationStepStatus status,
        Instant startedAt,
        Instant completedAt,
        String inputSummary,
        String outputSummary,
        String runReference,
        List<String> evidenceReferences,
        List<String> recommendations,
        String failureReason) {

    public OrchestrationStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(capability, "capability");
        Objects.requireNonNull(status, "status");
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        recommendations = List.copyOf(recommendations == null ? List.of() : recommendations);
    }

    public static OrchestrationStep pending(OrchestrationDefinition.Step definition) {
        return new OrchestrationStep(definition.id(), definition.type(), definition.capability(),
                definition.required(), OrchestrationStepStatus.PENDING,
                null, null, null, null, null, List.of(), List.of(), null);
    }

    public OrchestrationStep transition(OrchestrationStepStatus next, Instant at,
                                        String input, String output, String childRun,
                                        List<String> evidence, String failure) {
        Instant nextStarted = startedAt == null && next != OrchestrationStepStatus.PENDING ? at : startedAt;
        Instant nextCompleted = next.isTerminal() ? at : null;
        return new OrchestrationStep(id, type, capability, required, next, nextStarted, nextCompleted,
                input == null ? inputSummary : input,
                output == null ? outputSummary : output,
                childRun == null ? runReference : childRun,
                evidence == null ? evidenceReferences : evidence,
                recommendations,
                failure);
    }

    public OrchestrationStep withRecommendations(List<String> values) {
        return new OrchestrationStep(id, type, capability, required, status, startedAt, completedAt,
                inputSummary, outputSummary, runReference, evidenceReferences, values, failureReason);
    }
}
