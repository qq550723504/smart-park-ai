package com.example.smartpark.execution;

import com.example.smartpark.execution.model.ExecutionEvent;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Publishes, replays and terminates per-run execution event streams.
 * Implementations must guarantee strictly increasing contiguous sequences
 * per run and must never let a subscriber miss history or terminal events.
 */
public interface ExecutionEventPublisher {

    /** Publishes the event; if sequence is 0 it is assigned atomically as next of run. */
    ExecutionEvent publish(ExecutionEvent event);

    /** Immutable snapshot of all events published so far for the run; empty for unknown runs. */
    List<ExecutionEvent> history(UUID runId);

    /**
     * Subscribes to an existing run: the consumer first receives the full history,
     * then every subsequent live event until a terminal event completes the stream.
     */
    Subscription subscribe(UUID runId, Consumer<ExecutionEvent> consumer);

    /** Last known state summary for the run, e.g. RUNNING / COMPLETED / FAILED. */
    String status(UUID runId);

    /** Removes cleanup-eligible (terminal) run state; refuses non-terminal runs. */
    void remove(UUID runId);

    interface Subscription extends AutoCloseable {
        @Override
        void close();
    }
}
