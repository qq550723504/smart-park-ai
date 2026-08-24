package com.example.smartpark.model.customer;

import java.util.List;

public record CustomerAnswer(String answer, boolean needsHuman, Reason reason, List<String> citationIds) {
    public enum Reason { SUPPORTED, INSUFFICIENT_EVIDENCE, POLICY_LIMIT, RETRIEVAL_UNAVAILABLE }

    public CustomerAnswer {
        if (answer == null || answer.isBlank() || answer.trim().length() > 2_000) {
            throw new IllegalArgumentException("answer must be non-empty and no longer than 2000 characters");
        }
        answer = answer.trim();
        if (reason == null) throw new IllegalArgumentException("reason must be present");
        citationIds = List.copyOf(citationIds == null ? List.of() : citationIds);
        if (needsHuman && reason == Reason.SUPPORTED) throw new IllegalArgumentException("human transfer cannot be supported");
        if (!needsHuman && reason != Reason.SUPPORTED) throw new IllegalArgumentException("non-human answer must be supported");
    }
}
