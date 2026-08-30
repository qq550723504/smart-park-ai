package com.example.smartpark.showcase;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface ScenarioVerificationRegistry {

    void recordSuccess(ShowcaseScenarioId scenarioId, Instant verifiedAt);

    void recordFailure(ShowcaseScenarioId scenarioId);

    Optional<Instant> lastSuccessfulAt(ShowcaseScenarioId scenarioId, Instant now, Duration ttl);
}
