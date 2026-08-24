package com.example.smartpark.model.customer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record CustomerServiceResult(
        String sessionId,
        String intent,
        String answer,
        List<String> knowledgeSources,
        List<KnowledgeCitation> knowledgeCitations,
        boolean needsHuman,
        CustomerTicket ticket,
        CustomerAnswer.Reason reason,
        List<String> citationIds) {

    public CustomerServiceResult(String sessionId, String intent, String answer, List<String> knowledgeSources,
                                 List<KnowledgeCitation> knowledgeCitations, boolean needsHuman,
                                 CustomerTicket ticket) {
        this(sessionId, intent, answer, knowledgeSources, knowledgeCitations, needsHuman, ticket,
                needsHuman ? CustomerAnswer.Reason.POLICY_LIMIT : CustomerAnswer.Reason.SUPPORTED,
                needsHuman ? List.of() : knowledgeCitations.stream().map(KnowledgeCitation::documentId).toList());
    }

    public CustomerServiceResult(String sessionId, String intent, String answer, List<String> knowledgeSources,
                                 boolean needsHuman, CustomerTicket ticket) {
        this(sessionId, intent, answer, knowledgeSources, List.of(), needsHuman, ticket);
    }

    public CustomerServiceResult {
        sessionId = requireText(sessionId, "sessionId");
        intent = requireText(intent, "intent");
        answer = requireText(answer, "answer");
        knowledgeSources = List.copyOf(knowledgeSources);
        knowledgeCitations = List.copyOf(knowledgeCitations);
        if (reason == null) throw new IllegalArgumentException("reason must be present");
        citationIds = List.copyOf(citationIds == null ? List.of() : citationIds);
        if (needsHuman != (ticket != null)) {
            throw new IllegalArgumentException("needsHuman must match ticket presence");
        }
        if (needsHuman && reason == CustomerAnswer.Reason.SUPPORTED) {
            throw new IllegalArgumentException("human transfer cannot be supported");
        }
        if (!needsHuman && reason != CustomerAnswer.Reason.SUPPORTED) {
            throw new IllegalArgumentException("non-human answer must be supported");
        }
        if (needsHuman && !citationIds.isEmpty()) {
            throw new IllegalArgumentException("human handoff must not include citationIds");
        }
        if (!needsHuman && citationIds.isEmpty()) {
            throw new IllegalArgumentException("supported results require at least one citationId");
        }
        if (new LinkedHashSet<>(citationIds).size() != citationIds.size()) {
            throw new IllegalArgumentException("citationIds must not contain duplicates");
        }
        citationIds.forEach(id -> requireText(id, "citationId"));
        Set<String> availableCitationIds = knowledgeCitations.stream()
                .map(KnowledgeCitation::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!availableCitationIds.containsAll(citationIds)) {
            throw new IllegalArgumentException("citationIds must reference knowledgeCitations");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
