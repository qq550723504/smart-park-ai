package com.example.smartpark.execution.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Unified execution event shared by voice, expert collaboration, operations
 * analysis and the legacy alert workflow. Field set is frozen by the P1
 * cross-plan contract; sanitization happens server side before construction.
 */
public record ExecutionEvent(
        UUID eventId,
        UUID runId,
        long sequence,
        Instant timestamp,
        ExecutionScenario scenario,
        String actor,
        ExecutionStage stage,
        ExecutionEventType eventType,
        ExecutionStatus status,
        String safeSummary,
        DisplayPayload displayPayload) {

    public ExecutionEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(runId, "runId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(scenario, "scenario");
        actor = Objects.requireNonNull(actor, "actor");
        if (actor.isBlank()) {
            throw new IllegalArgumentException("actor must not be blank");
        }
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(status, "status");
        safeSummary = Objects.requireNonNull(safeSummary, "safeSummary");
    }

    public boolean isTerminal() {
        return eventType.isTerminal() || status.isTerminal();
    }
}
