package com.example.smartpark.execution;

import com.example.smartpark.execution.model.ExecutionEvent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * Thread-safe in-memory publisher. A per-run lock makes sequence assignment,
 * history append and sink emission one atomic commit so concurrent publishers
 * can never duplicate or skip a sequence.
 */
@Component
public class InMemoryExecutionEventPublisher implements ExecutionEventPublisher {

    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();

    @Override
    public ExecutionEvent publish(ExecutionEvent event) {
        RunState state = runs.computeIfAbsent(event.runId(), id -> new RunState());
        state.lock.lock();
        try {
            if (state.closed) {
                throw new IllegalStateException("run " + event.runId() + " is already terminal");
            }
            long expectedNext = state.count + 1;
            long sequence = event.sequence() > 0 ? event.sequence() : expectedNext;
            if (sequence != expectedNext) {
                throw new IllegalArgumentException("out-of-order sequence " + sequence
                        + " for run " + event.runId() + "; expected " + expectedNext);
            }
            ExecutionEvent stored = new ExecutionEvent(event.eventId(), event.runId(), sequence,
                    event.timestamp(), event.scenario(), event.actor(), event.stage(),
                    event.eventType(), event.status(), event.safeSummary(), event.displayPayload());
            state.addInternal(stored);
            for (Consumer<ExecutionEvent> consumer : state.consumers) {
                consumer.accept(stored);
            }
            if (stored.isTerminal()) {
                state.closed = true;
                state.consumers.clear();
            }
            return stored;
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public List<ExecutionEvent> history(UUID runId) {
        RunState state = runs.get(runId);
        return state == null ? List.of() : state.snapshot();
    }

    @Override
    public Subscription subscribe(UUID runId, Consumer<ExecutionEvent> consumer) {
        RunState state = runs.get(runId);
        if (state == null) {
            throw new IllegalArgumentException("unknown run " + runId);
        }
        state.lock.lock();
        try {
            for (ExecutionEvent event : state.snapshot()) {
                consumer.accept(event);
            }
            if (!state.closed) {
                state.consumers.add(consumer);
            }
        } finally {
            state.lock.unlock();
        }
        return () -> state.consumers.remove(consumer);
    }

    @Override
    public String status(UUID runId) {
        RunState state = runs.get(runId);
        if (state == null) {
            return "UNKNOWN";
        }
        List<ExecutionEvent> snapshot = state.snapshot();
        if (snapshot.isEmpty()) {
            return "UNKNOWN";
        }
        ExecutionEvent last = snapshot.get(snapshot.size() - 1);
        return last.isTerminal() ? last.eventType().name() : "RUNNING";
    }

    @Override
    public void remove(UUID runId) {
        RunState state = runs.get(runId);
        if (state == null) {
            return;
        }
        state.lock.lock();
        try {
            if (!state.closed) {
                throw new IllegalStateException("cannot remove non-terminal run " + runId);
            }
        } finally {
            state.lock.unlock();
        }
        runs.remove(runId);
    }

    private static final class RunState {
        final ReentrantLock lock = new ReentrantLock();
        final CopyOnWriteArrayList<Consumer<ExecutionEvent>> consumers = new CopyOnWriteArrayList<>();
        final List<ExecutionEvent> historyBacking = new ArrayList<>();
        volatile boolean closed;
        long count;

        void addInternal(ExecutionEvent event) {
            historyBacking.add(event);
            count++;
        }

        List<ExecutionEvent> snapshot() {
            lock.lock();
            try {
                return List.copyOf(historyBacking);
            } finally {
                lock.unlock();
            }
        }
    }
}
