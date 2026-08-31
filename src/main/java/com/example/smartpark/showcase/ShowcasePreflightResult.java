package com.example.smartpark.showcase;

import java.time.Instant;

public record ShowcasePreflightResult(
        ShowcaseScenarioId scenarioId,
        ShowcasePreflightStatus status,
        String reason,
        Instant verifiedAt) {
}
