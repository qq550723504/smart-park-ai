package com.example.smartpark.analytics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store of analysis runs; state changes are atomic per
 * run. Terminal records (COMPLETED/FAILED) keep their result tables replayable
 * only for a bounded retention window and are then swept opportunistically, so
 * continued use cannot grow heap without bound. Non-terminal records — running
 * or paused for clarification — are never swept.
 */
public class AnalysisRunStore {

    /** Default replayable window after a run reaches a terminal state. */
    private static final Duration DEFAULT_TERMINAL_RETENTION = Duration.ofMinutes(30);

    /** Public snapshot of a run's lifecycle; never carries SQL credentials or raw vendor errors. */
    public record RunRecord(
            UUID runId,
            String question,
            String status,
            List<String> clarificationQuestions,
            /** Structured candidate metric names, one candidate set per pending question. */
            List<List<String>> clarificationOptions,
            String summary,
            int rowCount,
            boolean truncated,
            long durationMs,
            String failureStage,
            Instant createdAt,
            List<String> columns,
            List<List<Object>> rows) {}

    private final Map<UUID, RunRecord> runs = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration terminalRetention;

    public AnalysisRunStore() {
        this(DEFAULT_TERMINAL_RETENTION, Clock.systemUTC());
    }

    /** Retention is measured on the caller's clock so mixed clocks never expire fresh records. */
    public AnalysisRunStore(Clock clock) {
        this(DEFAULT_TERMINAL_RETENTION, clock);
    }

    public AnalysisRunStore(Duration terminalRetention, Clock clock) {
        if (terminalRetention == null || terminalRetention.isZero() || terminalRetention.isNegative()) {
            throw new IllegalArgumentException("terminalRetention must be positive");
        }
        this.terminalRetention = terminalRetention;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void put(RunRecord record) {
        evictExpiredTerminalRuns(clock.instant());
        runs.put(record.runId(), record);
    }

    public RunRecord get(UUID runId) {
        evictExpiredTerminalRuns(clock.instant());
        return runs.get(runId);
    }

    public boolean existsActive() {
        evictExpiredTerminalRuns(clock.instant());
        return runs.values().stream()
                .anyMatch(record -> "RUNNING".equals(record.status()));
    }

    private void evictExpiredTerminalRuns(Instant now) {
        for (var entry : runs.entrySet()) {
            RunRecord record = entry.getValue();
            if (isTerminal(record.status())
                    && !record.createdAt().plus(terminalRetention).isAfter(now)) {
                // A concurrent put of the same id re-registers itself; the map
                // entry removed here is always a stale terminal snapshot.
                runs.remove(entry.getKey(), record);
            }
        }
    }

    private static boolean isTerminal(String status) {
        return "COMPLETED".equals(status) || "FAILED".equals(status);
    }
}
