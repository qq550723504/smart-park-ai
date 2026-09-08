package com.example.smartpark.orchestration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

record OrchestrationStoreLimits(int maxRetainedRuns, int maxActiveRuns) {
    static final int DEFAULT_MAX_RETAINED_RUNS = 200;
    static final int DEFAULT_MAX_ACTIVE_RUNS = 8;

    OrchestrationStoreLimits {
        if (maxRetainedRuns < 1) throw new IllegalArgumentException("maxRetainedRuns must be positive");
        if (maxActiveRuns < 1 || maxActiveRuns > maxRetainedRuns) {
            throw new IllegalArgumentException("maxActiveRuns must be positive and no greater than maxRetainedRuns");
        }
    }

    static OrchestrationStoreLimits defaults() {
        return new OrchestrationStoreLimits(DEFAULT_MAX_RETAINED_RUNS, DEFAULT_MAX_ACTIVE_RUNS);
    }

    LinkedHashMap<UUID, OrchestrationRun> prepareForAdmission(Map<UUID, OrchestrationRun> current) {
        long activeRuns = current.values().stream().filter(run -> !run.status().isTerminal()).count();
        if (activeRuns >= maxActiveRuns) {
            throw new OrchestrationCapacityException("orchestration active-run capacity is exhausted");
        }
        LinkedHashMap<UUID, OrchestrationRun> compacted = compactTerminals(current, maxRetainedRuns - 1);
        if (compacted.size() >= maxRetainedRuns) {
            throw new OrchestrationCapacityException("orchestration retained-run capacity is exhausted");
        }
        return compacted;
    }

    LinkedHashMap<UUID, OrchestrationRun> compactRetained(Map<UUID, OrchestrationRun> current) {
        return compactTerminals(current, maxRetainedRuns);
    }

    private static LinkedHashMap<UUID, OrchestrationRun> compactTerminals(
            Map<UUID, OrchestrationRun> current, int targetSize) {
        LinkedHashMap<UUID, OrchestrationRun> compacted = new LinkedHashMap<>(current);
        var iterator = compacted.entrySet().iterator();
        while (compacted.size() > targetSize && iterator.hasNext()) {
            if (iterator.next().getValue().status().isTerminal()) iterator.remove();
        }
        return compacted;
    }
}
