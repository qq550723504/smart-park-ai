package com.example.smartpark.workflow;

import java.util.List;
import java.util.Objects;

public record RiskAssessment(Route route, List<String> reasons, double confidenceThreshold) {
    public RiskAssessment {
        route = Objects.requireNonNull(route, "route");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (confidenceThreshold < 0 || confidenceThreshold > 1) {
            throw new IllegalArgumentException("confidenceThreshold must be between 0 and 1");
        }
    }
}
