package com.example.smartpark.showcase;

/** Server-owned input used by both preflight and the one-click guided launch. */
public record ShowcaseLaunchInput(String alertId, String question) {

    private static final String COLLABORATION_QUESTION =
            "电表 DEV-ENERGY-001、设备 DEV-POWER-001 与安防事件 SEC-ACCESS-001 是否存在关联";
    private static final String ANALYTICS_QUESTION = "过去5天各楼宇能耗";

    public ShowcaseLaunchInput {
        alertId = normalize(alertId);
        question = normalize(question);
    }

    public static ShowcaseLaunchInput forScenario(ShowcaseScenarioId id) {
        return switch (java.util.Objects.requireNonNull(id, "id")) {
            case ALERT_WORKFLOW -> new ShowcaseLaunchInput("ALT-POWER-001", null);
            case EXPERT_COLLABORATION -> new ShowcaseLaunchInput(null, COLLABORATION_QUESTION);
            case OPERATIONS_ANALYSIS -> new ShowcaseLaunchInput(null, ANALYTICS_QUESTION);
            case VOICE_ASSISTANT -> new ShowcaseLaunchInput(null, null);
        };
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
