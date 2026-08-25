package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.CollaborationRun;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-memory store of collaboration runs. Terminal records (COMPLETED/FAILED)
 * retain their full plan/findings/synthesis only for a bounded replayable
 * window and are then swept opportunistically, mirroring the analytics run
 * store; RUNNING and NEEDS_CLARIFICATION records are never evicted.
 */
public final class CollaborationRunStore {

    /** Default replayable window after a run reaches a terminal state. */
    private static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofMinutes(30);

    private final ConcurrentMap<UUID, CollaborationRun> runs = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration terminalRetention;

    public CollaborationRunStore() {
        this(DEFAULT_TERMINAL_RETENTION, Clock.systemUTC());
    }

    public CollaborationRunStore(Duration terminalRetention, Clock clock) {
        if (terminalRetention == null || terminalRetention.isZero() || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must be positive");
        }
        this.terminalRetention = terminalRetention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public CollaborationRun save(CollaborationRun run) {
        evictExpiredTerminalRuns(clock.instant());
        runs.put(run.runId(), run);
        return run;
    }

    public CollaborationRun get(UUID id) {
        evictExpiredTerminalRuns(clock.instant());
        CollaborationRun run = runs.get(id);
        if (run == null) throw new NoSuchElementException("Unknown collaboration run: " + id);
        return run;
    }

    private void evictExpiredTerminalRuns(Instant now) {
        for (var entry : runs.entrySet()) {
            CollaborationRun run = entry.getValue();
            if (isTerminal(run.status()) && !run.updatedAt().plus(terminalRetention).isAfter(now)) {
                runs.remove(entry.getKey(), run);
            }
        }
    }

    private static boolean isTerminal(CollaborationRun.RunStatus status) {
        return status == CollaborationRun.RunStatus.COMPLETED
                || status == CollaborationRun.RunStatus.FAILED;
    }
}
