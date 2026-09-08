package com.example.smartpark.orchestration;

import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionScenario;

import java.util.List;
import java.util.UUID;

public final class OrchestrationTraceArchive implements ExecutionEventArchive {
    private final OrchestrationRunStore store;

    public OrchestrationTraceArchive(OrchestrationRunStore store) {
        this.store = store;
    }

    @Override
    public List<ExecutionEvent> history(UUID runId) {
        return store.find(runId).map(run -> run.traceEvents().stream()
                .map(event -> new ExecutionEvent(event.eventId(), run.traceId(), event.sequence(),
                        event.timestamp(), ExecutionScenario.ORCHESTRATION, event.actor(), event.stage(),
                        event.eventType(), event.status(), event.safeSummary(), null))
                .toList()).orElseGet(List::of);
    }
}
