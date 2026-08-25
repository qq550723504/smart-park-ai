package com.example.smartpark.collaboration.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public record SupervisorPlan(
        String normalizedQuestion,
        Set<ExpertDomain> selectedDomains,
        Map<ExpertDomain, String> assignments,
        String selectionReason) {

    public SupervisorPlan {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            throw new IllegalArgumentException("normalizedQuestion must not be blank");
        }
        Objects.requireNonNull(selectedDomains, "selectedDomains");
        if (selectedDomains.isEmpty() || selectedDomains.size() > ExpertDomain.values().length) {
            throw new IllegalArgumentException("selectedDomains must contain 1 to 3 domains");
        }
        selectedDomains = Collections.unmodifiableSet(EnumSet.copyOf(selectedDomains));
        Objects.requireNonNull(assignments, "assignments");
        if (!assignments.keySet().equals(selectedDomains)) {
            throw new IllegalArgumentException("assignments must exactly cover selectedDomains");
        }
        EnumMap<ExpertDomain, String> assignmentCopy = new EnumMap<>(ExpertDomain.class);
        assignments.forEach((domain, assignment) -> {
            Objects.requireNonNull(domain, "assignment domain");
            if (assignment == null || assignment.isBlank()) {
                throw new IllegalArgumentException("assignments must not be blank");
            }
            assignmentCopy.put(domain, assignment);
        });
        assignments = Collections.unmodifiableMap(assignmentCopy);
        if (selectionReason == null || selectionReason.isBlank()) {
            throw new IllegalArgumentException("selectionReason must not be blank");
        }
    }
}
