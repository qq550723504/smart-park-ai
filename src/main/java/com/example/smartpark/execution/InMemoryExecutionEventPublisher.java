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
 * can never duplicate or skip a sequence. Terminal run histories are retained
 * only for a bounded retention window; an opportunistic sweep triggered by
 * publishing evicts them once they have stayed replayable long enough.
 */
@Component
public class InMemoryExecutionEventPublisher implements ExecutionEventPublisher {

    /** Default replayable window after a run reaches a terminal state. */
    private static final java.time.Duration DEFAULT_RETENTION = java.time.Duration.ofMinutes(30);

    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();
    private final java.time.Clock clock;
    private final java.time.Duration retention;

    public InMemoryExecutionEventPublisher() {
        this(DEFAULT_RETENTION, java.time.Clock.systemUTC());
    }

    public InMemoryExecutionEventPublisher(java.time.Duration retention, java.time.Clock clock) {
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.retention = retention;
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ExecutionEvent publish(ExecutionEvent event) {
        evictExpiredRuns(clock.instant());
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
                state.terminalAt = clock.instant();
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
        return last.isTerminal() ? last.eventType().name() : last.status().name();
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

    /** Removes terminal runs whose replayable window has elapsed; running runs are never touched. */
    private void evictExpiredRuns(java.time.Instant now) {
        for (var entry : runs.entrySet()) {
            RunState state = entry.getValue();
            state.lock.lock();
            try {
                if (state.closed && state.terminalAt != null
                        && state.terminalAt.plus(retention).compareTo(now) <= 0) {
                    runs.remove(entry.getKey(), state);
                }
            } finally {
                state.lock.unlock();
            }
        }
    }

    private static final class RunState {
        final ReentrantLock lock = new ReentrantLock();
        final CopyOnWriteArrayList<Consumer<ExecutionEvent>> consumers = new CopyOnWriteArrayList<>();
        final List<ExecutionEvent> historyBacking = new ArrayList<>();
        volatile boolean closed;
        volatile java.time.Instant terminalAt;
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
