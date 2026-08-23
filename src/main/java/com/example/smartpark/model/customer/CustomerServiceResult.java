package com.example.smartpark.model.customer;

import java.util.List;

public record CustomerServiceResult(
        String sessionId,
        String intent,
        String answer,
        List<String> knowledgeSources,
        boolean needsHuman,
        CustomerTicket ticket) {

    public CustomerServiceResult {
        sessionId = requireText(sessionId, "sessionId");
        intent = requireText(intent, "intent");
        answer = requireText(answer, "answer");
        knowledgeSources = List.copyOf(knowledgeSources);
        if (needsHuman != (ticket != null)) {
            throw new IllegalArgumentException("needsHuman must match ticket presence");
        }
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
