package com.example.smartpark.web;

import com.example.smartpark.orchestration.OrchestrationInput;
import com.example.smartpark.orchestration.OrchestrationResult;
import com.example.smartpark.orchestration.OrchestrationRun;
import com.example.smartpark.orchestration.OrchestrationStep;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class OrchestrationDtos {
    private OrchestrationDtos() {
    }

    record RunResponse(UUID runId, String definitionId, String status, Instant createdAt,
                       Instant startedAt, Instant completedAt, String role,
                       OrchestrationInput input, String summary, List<OrchestrationStep> steps,
                       List<String> evidence, UUID traceId, String failureReason,
                       OrchestrationResult result, boolean cancelRequested, long revision) {
        static RunResponse from(OrchestrationRun run) {
            return new RunResponse(run.id(), run.definitionId(), run.status().name(), run.createdAt(),
                    run.startedAt(), run.completedAt(), run.role(), run.input(), run.summary(), run.steps(),
                    run.evidence(), run.traceId(), run.failureReason(), run.result(),
                    run.cancelRequested(), run.revision());
        }
    }
}
