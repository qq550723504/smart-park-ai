package com.example.smartpark.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record OrchestrationRun(
        UUID id,
        String definitionId,
        OrchestrationStatus status,
        Instant createdAt,
        Instant startedAt,
        Instant completedAt,
        String requestedBy,
        String role,
        OrchestrationInput input,
        String summary,
        List<OrchestrationStep> steps,
        List<String> evidence,
        UUID traceId,
        String failureReason,
        OrchestrationResult result,
        String idempotencyKey,
        String requestFingerprint,
        boolean cancelRequested,
        long revision,
        List<OrchestrationTraceRecord> traceEvents) {

    public OrchestrationRun {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(requestedBy, "requestedBy");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(input, "input");
        steps = List.copyOf(steps == null ? List.of() : steps);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestFingerprint, "requestFingerprint");
        traceEvents = List.copyOf(traceEvents == null ? List.of() : traceEvents);
    }

    public OrchestrationRun copy(OrchestrationStatus nextStatus, Instant nextStartedAt,
                                 Instant nextCompletedAt, String nextSummary,
                                 List<OrchestrationStep> nextSteps, List<String> nextEvidence,
                                 String nextFailureReason, OrchestrationResult nextResult,
                                 boolean nextCancelRequested, List<OrchestrationTraceRecord> nextTraceEvents) {
        return new OrchestrationRun(id, definitionId, nextStatus, createdAt,
                nextStartedAt, nextCompletedAt, requestedBy, role, input,
                nextSummary, nextSteps, nextEvidence, traceId, nextFailureReason,
                nextResult, idempotencyKey, requestFingerprint, nextCancelRequested,
                revision + 1, nextTraceEvents);
    }
}
