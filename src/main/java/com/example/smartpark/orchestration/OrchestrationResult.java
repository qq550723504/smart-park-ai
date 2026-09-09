package com.example.smartpark.orchestration;

import java.util.List;
import java.util.Map;

public record OrchestrationResult(
        String conclusion,
        List<String> recommendations,
        List<String> evidenceReferences,
        List<String> sourceReferences,
        Map<String, String> childRuns,
        List<String> skippedSteps,
        List<String> partialReasons,
        String humanApprovalResult) {

    public OrchestrationResult {
        recommendations = List.copyOf(recommendations == null ? List.of() : recommendations);
        evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
        sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
        childRuns = Map.copyOf(childRuns == null ? Map.of() : childRuns);
        skippedSteps = List.copyOf(skippedSteps == null ? List.of() : skippedSteps);
        partialReasons = List.copyOf(partialReasons == null ? List.of() : partialReasons);
    }
}
