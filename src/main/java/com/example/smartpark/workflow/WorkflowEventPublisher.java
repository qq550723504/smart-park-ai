package com.example.smartpark.workflow;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface WorkflowEventPublisher {

    WorkflowEvent publish(
            String workflowId,
            WorkflowEvent.EventType eventType,
            String node,
            Instant timestamp,
            String summary);

    Flux<WorkflowEvent> events(String workflowId);

    default void complete(String workflowId) {
    }

    static WorkflowEventPublisher inMemory() {
        return new InMemoryWorkflowEventPublisher();
    }
}

final class InMemoryWorkflowEventPublisher implements WorkflowEventPublisher {

    private final Map<String, EventStream> streams = new ConcurrentHashMap<>();

    @Override
    public WorkflowEvent publish(
            String workflowId,
            WorkflowEvent.EventType eventType,
            String node,
            Instant timestamp,
            String summary) {
        return stream(workflowId).publish(workflowId, eventType, node, timestamp, summary);
    }

    @Override
    public Flux<WorkflowEvent> events(String workflowId) {
        return stream(workflowId).events();
    }

    @Override
    public void complete(String workflowId) {
        stream(workflowId).complete();
    }

    private EventStream stream(String workflowId) {
        return streams.computeIfAbsent(workflowId, ignored -> new EventStream());
    }

    private static final class EventStream {
        private final Sinks.Many<WorkflowEvent> sink = Sinks.many().replay().all();
        private long sequence;

        private synchronized WorkflowEvent publish(
                String workflowId,
                WorkflowEvent.EventType eventType,
                String node,
                Instant timestamp,
                String summary) {
            WorkflowEvent event = new WorkflowEvent(
                    workflowId,
                    ++sequence,
                    eventType,
                    node,
                    timestamp,
                    summary);
            Sinks.EmitResult result = sink.tryEmitNext(event);
            if (result.isFailure()) {
                throw new IllegalStateException("Unable to publish workflow event: " + result);
            }
            return event;
        }

        private Flux<WorkflowEvent> events() {
            return sink.asFlux();
        }

        private synchronized void complete() {
            Sinks.EmitResult result = sink.tryEmitComplete();
            if (result.isFailure() && result != Sinks.EmitResult.FAIL_TERMINATED) {
                throw new IllegalStateException("Unable to complete workflow event stream: " + result);
            }
        }
    }
}
