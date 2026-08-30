package com.example.smartpark.showcase;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryScenarioVerificationRegistryTest {

    private ScenarioVerificationRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InMemoryScenarioVerificationRegistry();
    }

    @Test
    void makesOnlyAnUnexpiredSuccessAvailable() {
        Instant verifiedAt = Instant.parse("2026-08-30T10:00:00Z");
        registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, verifiedAt);

        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.EXPERT_COLLABORATION,
                verifiedAt.plus(Duration.ofMinutes(14)), Duration.ofMinutes(15))).contains(verifiedAt);
        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.EXPERT_COLLABORATION,
                verifiedAt.plus(Duration.ofMinutes(15)), Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    void doesNotExposeAFutureSuccessAtCurrentTime() {
        Instant now = Instant.parse("2026-08-30T10:00:00Z");
        registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, now.plusSeconds(1));

        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.EXPERT_COLLABORATION,
                now, Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    void failureInvalidatesAnEarlierSuccess() {
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                Instant.parse("2026-08-30T10:00:00Z"));
        registry.recordFailure(ShowcaseScenarioId.OPERATIONS_ANALYSIS);

        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                Instant.parse("2026-08-30T10:01:00Z"), Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    void laterSuccessReplacesAnEarlierReceipt() {
        Instant earlier = Instant.parse("2026-08-30T10:00:00Z");
        Instant later = Instant.parse("2026-08-30T10:05:00Z");
        registry.recordSuccess(ShowcaseScenarioId.ALERT_WORKFLOW, earlier);
        registry.recordSuccess(ShowcaseScenarioId.ALERT_WORKFLOW, later);

        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.ALERT_WORKFLOW,
                Instant.parse("2026-08-30T10:06:00Z"), Duration.ofMinutes(15))).contains(later);
    }
}
