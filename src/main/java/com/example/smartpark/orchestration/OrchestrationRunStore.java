package com.example.smartpark.orchestration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public interface OrchestrationRunStore {
    StartResult createOrGet(String idempotencyKey, String requestFingerprint,
                            Supplier<OrchestrationRun> factory);

    Optional<OrchestrationRun> find(UUID runId);

    OrchestrationRun update(UUID runId, UnaryOperator<OrchestrationRun> transition);

    List<OrchestrationRun> nonTerminalRuns();

    record StartResult(OrchestrationRun run, boolean created) {
    }
}
