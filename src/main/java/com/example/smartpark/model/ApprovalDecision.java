package com.example.smartpark.model;

import java.time.Instant;
import java.util.Objects;

public record ApprovalDecision(
        Decision decision,
        String reviewer,
        String comment,
        String idempotencyKey,
        Instant decidedAt) {

    public ApprovalDecision {
        decision = Objects.requireNonNull(decision, "decision");
        reviewer = Objects.requireNonNull(reviewer, "reviewer");
        comment = Objects.requireNonNull(comment, "comment");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        decidedAt = Objects.requireNonNull(decidedAt, "decidedAt");
    }

    public boolean hasSameRequestPayloadAs(ApprovalDecision other) {
        return other != null
                && decision == other.decision
                && reviewer.equals(other.reviewer)
                && comment.equals(other.comment)
                && idempotencyKey.equals(other.idempotencyKey);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public enum Decision {
        APPROVED,
        REJECTED
    }
}
