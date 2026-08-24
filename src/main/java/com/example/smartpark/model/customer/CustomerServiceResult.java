package com.example.smartpark.model.customer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

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

    private static final Pattern SAFE_CITATION_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public CustomerServiceResult(String sessionId, String intent, String answer, List<String> knowledgeSources,
                                 List<KnowledgeCitation> knowledgeCitations, boolean needsHuman,
                                 CustomerTicket ticket) {
        this(sessionId, intent, answer, knowledgeSources, knowledgeCitations, needsHuman, ticket,
                needsHuman ? CustomerAnswer.Reason.POLICY_LIMIT : CustomerAnswer.Reason.SUPPORTED,
                needsHuman ? List.of() : knowledgeCitations.stream().map(KnowledgeCitation::documentId).toList());
    }

    public CustomerServiceResult(String sessionId, String intent, String answer, List<String> knowledgeSources,
                                 boolean needsHuman, CustomerTicket ticket) {
        this(sessionId, intent, answer, knowledgeSources, List.of(), needsHuman, ticket,
                needsHuman ? CustomerAnswer.Reason.POLICY_LIMIT : CustomerAnswer.Reason.SUPPORTED,
                requireExplicitCitationIds(needsHuman));
    }

    /** Compatibility bridge for callers that still provide the serialized reason name. */
    public CustomerServiceResult(String sessionId, String intent, String answer, List<String> knowledgeSources,
                                 boolean needsHuman, CustomerTicket ticket, String reason, List<String> citationIds) {
        this(sessionId, intent, answer, knowledgeSources, List.of(), needsHuman, ticket,
                parseReason(reason), citationIds);
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
            throw new IllegalArgumentException(reason + " results must require human handoff");
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
        citationIds.forEach(CustomerServiceResult::requireCitationId);
        Set<String> availableCitationIds = knowledgeCitations.stream()
                .map(KnowledgeCitation::documentId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!knowledgeCitations.isEmpty() && !availableCitationIds.containsAll(citationIds)) {
            throw new IllegalArgumentException("citationIds must reference knowledgeCitations");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private static void requireCitationId(String value) {
        if (value == null || !SAFE_CITATION_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("citationId must be a safe opaque identifier");
        }
    }

    private static CustomerAnswer.Reason parseReason(String value) {
        if ("HUMAN_HANDOFF".equals(value)) return CustomerAnswer.Reason.POLICY_LIMIT;
        try {
            return CustomerAnswer.Reason.valueOf(value);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("unsupported reason: " + value, exception);
        }
    }

    private static List<String> requireExplicitCitationIds(boolean needsHuman) {
        if (!needsHuman) {
            throw new IllegalArgumentException("supported results require explicit citationIds");
        }
        return List.of();
    }
}
