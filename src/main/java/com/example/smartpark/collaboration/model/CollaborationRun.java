package com.example.smartpark.collaboration.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CollaborationRun(UUID runId, String question, RunStatus status, SupervisorPlan plan,
                               List<ExpertFinding> findings, Synthesis synthesis, String error, Instant updatedAt) {
    public CollaborationRun {
        findings = List.copyOf(findings == null ? List.of() : findings);
    }

    public enum RunStatus { RUNNING, COMPLETED, FAILED, NEEDS_CLARIFICATION }
}
