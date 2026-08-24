package com.example.smartpark.model.customer;

import java.util.LinkedHashSet;
import java.util.List;

public record CustomerServiceResult(
        String sessionId,
        String intent,
        String answer,
        List<String> knowledgeSources,
        boolean needsHuman,
        CustomerTicket ticket,
        String reason,
        List<String> citationIds) {

    public CustomerServiceResult(String sessionId, String intent, String answer,
                                 List<String> knowledgeSources, boolean needsHuman,
                                 CustomerTicket ticket) {
        this(sessionId, intent, answer, knowledgeSources, needsHuman, ticket,
                needsHuman ? "HUMAN_HANDOFF" : "SUPPORTED",
                needsHuman ? List.of() : knowledgeSources);
    }

    public CustomerServiceResult {
        sessionId = requireText(sessionId, "sessionId");
        intent = requireText(intent, "intent");
        answer = requireText(answer, "answer");
        knowledgeSources = List.copyOf(knowledgeSources);
        reason = requireText(reason, "reason");
        citationIds = List.copyOf(citationIds);
        if (needsHuman != (ticket != null)) {
            throw new IllegalArgumentException("needsHuman must match ticket presence");
        }
        if ("SUPPORTED".equals(reason)) {
            if (needsHuman) throw new IllegalArgumentException("SUPPORTED results must not need human handoff");
            if (citationIds.isEmpty()) throw new IllegalArgumentException("SUPPORTED results require at least one citationId");
        }
        if (("INSUFFICIENT_EVIDENCE".equals(reason) || "POLICY_LIMIT".equals(reason)) && !needsHuman) {
            throw new IllegalArgumentException(reason + " results must require human handoff");
        }
        if (new LinkedHashSet<>(citationIds).size() != citationIds.size()) {
            throw new IllegalArgumentException("citationIds must not contain duplicates");
        }
        citationIds.forEach(id -> requireText(id, "citationId"));
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
