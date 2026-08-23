package com.example.smartpark.workflow;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface WorkflowEventPublisher {

    void publish(WorkflowEvent event);

    Flux<WorkflowEvent> events(String workflowId);

    default void complete(String workflowId) {
    }

    static WorkflowEventPublisher inMemory() {
        return new InMemoryWorkflowEventPublisher();
    }
}

final class InMemoryWorkflowEventPublisher implements WorkflowEventPublisher {

    private final Map<String, Sinks.Many<WorkflowEvent>> sinks = new ConcurrentHashMap<>();

    @Override
    public void publish(WorkflowEvent event) {
        sink(event.workflowId()).emitNext(
                event,
                (signalType, emitResult) -> emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED);
    }

    @Override
    public Flux<WorkflowEvent> events(String workflowId) {
        return sink(workflowId).asFlux();
    }

    @Override
    public void complete(String workflowId) {
        sink(workflowId).emitComplete(
                (signalType, emitResult) -> emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED);
    }

    private Sinks.Many<WorkflowEvent> sink(String workflowId) {
        return sinks.computeIfAbsent(workflowId, ignored -> Sinks.many().replay().all());
    }
}
