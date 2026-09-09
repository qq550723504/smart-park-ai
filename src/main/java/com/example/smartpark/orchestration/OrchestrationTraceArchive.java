package com.example.smartpark.orchestration;

import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionScenario;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

public final class OrchestrationTraceArchive implements ExecutionEventArchive {
    private final OrchestrationRunStore store;
    private final ExecutionEventPublisher publisher;

    public OrchestrationTraceArchive(OrchestrationRunStore store) {
        this(store, null);
    }

    public OrchestrationTraceArchive(OrchestrationRunStore store, ExecutionEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public List<ExecutionEvent> history(UUID runId) {
        return store.find(runId).map(run -> run.traceEvents().stream()
                .map(event -> new ExecutionEvent(event.eventId(), run.traceId(), event.sequence(),
                        event.timestamp(), ExecutionScenario.ORCHESTRATION, event.actor(), event.stage(),
                        event.eventType(), event.status(), event.safeSummary(), null))
                .toList()).orElseGet(List::of);
    }

    @Override
    public void authorize(UUID runId, String role) {
        var persisted = store.find(runId);
        if (persisted.isPresent()) {
            OrchestrationRun run = persisted.orElseThrow();
            String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            if (!"ADMIN".equals(normalized) && !run.role().equals(normalized)) {
                throw new SecurityException("role is not allowed to read orchestration trace");
            }
            return;
        }
        if (publisher != null && publisher.history(runId).stream()
                .anyMatch(event -> event.scenario() == ExecutionScenario.ORCHESTRATION)) {
            throw new NoSuchElementException("Unknown orchestration trace");
        }
    }
}
