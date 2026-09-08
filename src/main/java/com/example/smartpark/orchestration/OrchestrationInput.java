package com.example.smartpark.orchestration;

import java.util.LinkedHashSet;
import java.util.List;

public record OrchestrationInput(
        String question,
        String alertId,
        List<String> buildingIds,
        boolean energyRelated,
        boolean crossDomain,
        boolean securityRelated,
        boolean requestAction) {

    public static final int MAX_QUESTION_LENGTH = 500;

    public OrchestrationInput {
        if (question == null || question.isBlank() || question.trim().length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must contain 1..500 characters");
        }
        question = question.trim();
        alertId = normalizeOptional(alertId);
        LinkedHashSet<String> normalizedBuildings = new LinkedHashSet<>();
        for (String buildingId : buildingIds == null ? List.<String>of() : buildingIds) {
            if (buildingId == null || !buildingId.trim().toUpperCase().matches("B[0-9]{1,10}")) {
                throw new IllegalArgumentException("invalid buildingId");
            }
            normalizedBuildings.add(buildingId.trim().toUpperCase());
        }
        if (normalizedBuildings.size() > 20) {
            throw new IllegalArgumentException("buildingIds must contain at most 20 values");
        }
        buildingIds = List.copyOf(normalizedBuildings);
        if (energyRelated && buildingIds.isEmpty()) {
            throw new IllegalArgumentException("energy analysis requires buildingIds");
        }
        if (securityRelated && buildingIds.isEmpty() && alertId == null) {
            throw new IllegalArgumentException("security analysis requires an alertId or buildingIds");
        }
        if (requestAction && alertId == null) {
            throw new IllegalArgumentException("requestAction requires alertId");
        }
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
