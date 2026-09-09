package com.example.smartpark.orchestration;

import java.util.List;

/** One intentionally small, typed scenario definition. This is not a BPMN/DSL engine. */
public final class OrchestrationDefinition {
    public static final String JOINT_ANOMALY_ASSESSMENT = "JOINT_ANOMALY_ASSESSMENT";

    private static final List<Step> STEPS = List.of(
            new Step("collect-context", OrchestrationStepType.COLLECT_CONTEXT, "park-context", true),
            new Step("operations-analysis", OrchestrationStepType.OPERATIONS_ANALYSIS, "analytics", true),
            new Step("energy-time-series", OrchestrationStepType.ENERGY_TIME_SERIES, "energy-time-series", false),
            new Step("expert-collaboration", OrchestrationStepType.EXPERT_COLLABORATION, "expert-collaboration", false),
            new Step("security-review", OrchestrationStepType.SECURITY_REVIEW, "security-incident", false),
            new Step("alert-workflow", OrchestrationStepType.ALERT_WORKFLOW, "alert-workflow", false),
            new Step("final-summary", OrchestrationStepType.FINAL_SUMMARY, "orchestration-summary", true));

    private OrchestrationDefinition() {
    }

    public static List<Step> steps(String definitionId) {
        if (!JOINT_ANOMALY_ASSESSMENT.equals(definitionId)) {
            throw new IllegalArgumentException("unsupported orchestration definition");
        }
        return STEPS;
    }

    public record Step(String id, OrchestrationStepType type, String capability, boolean required) {
    }
}
