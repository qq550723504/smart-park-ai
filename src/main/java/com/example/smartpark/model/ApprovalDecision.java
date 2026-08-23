package com.example.smartpark.model;

import java.time.Instant;
import java.util.Objects;

public record ApprovalDecision(
        Decision decision,
        String reviewer,
        String comment,
        Instant decidedAt) {

    public ApprovalDecision {
        decision = Objects.requireNonNull(decision, "decision");
        reviewer = Objects.requireNonNull(reviewer, "reviewer");
        comment = Objects.requireNonNull(comment, "comment");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }
}
