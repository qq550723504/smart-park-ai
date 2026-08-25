package com.example.smartpark.analytics;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe in-memory store of analysis runs; state changes are atomic per run. */
public class AnalysisRunStore {

    /** Public snapshot of a run's lifecycle; never carries SQL credentials or raw vendor errors. */
    public record RunRecord(
            UUID runId,
            String question,
            String status,
            List<String> clarificationQuestions,
            String summary,
            int rowCount,
            boolean truncated,
            long durationMs,
            String failureStage,
            Instant createdAt,
            List<String> columns,
            List<List<Object>> rows) {}

    private final Map<UUID, RunRecord> runs = new ConcurrentHashMap<>();

    public void put(RunRecord record) {
        runs.put(record.runId(), record);
    }

    public RunRecord get(UUID runId) {
        return runs.get(runId);
    }

    public boolean existsActive() {
        return runs.values().stream()
                .anyMatch(record -> "RUNNING".equals(record.status()));
    }
}
