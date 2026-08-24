package com.example.smartpark.model.customer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

public record CustomerServiceResult(
        String sessionId,
        String intent,
        String answer,
        List<String> knowledgeSources,
        boolean needsHuman,
        CustomerTicket ticket,
        String reason,
        List<String> citationIds) {

    private static final Pattern SAFE_CITATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

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
        citationIds.forEach(CustomerServiceResult::requireCitationId);
    }

    private static void requireCitationId(String value) {
        if (value == null || !SAFE_CITATION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("citationId must be a safe opaque identifier");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
