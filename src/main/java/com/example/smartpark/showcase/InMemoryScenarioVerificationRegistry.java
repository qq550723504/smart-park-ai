package com.example.smartpark.showcase;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryScenarioVerificationRegistry implements ScenarioVerificationRegistry {

    private final ConcurrentMap<ShowcaseScenarioId, Instant> successes = new ConcurrentHashMap<>();

    @Override
    public void recordSuccess(ShowcaseScenarioId id, Instant verifiedAt) {
        successes.put(Objects.requireNonNull(id), Objects.requireNonNull(verifiedAt));
    }

    @Override
    public void recordFailure(ShowcaseScenarioId id) {
        successes.remove(Objects.requireNonNull(id));
    }

    @Override
    public Optional<Instant> lastSuccessfulAt(ShowcaseScenarioId id, Instant now, Duration ttl) {
        Instant verifiedAt = successes.get(Objects.requireNonNull(id));
        return verifiedAt != null && !verifiedAt.isAfter(now) && verifiedAt.plus(ttl).isAfter(now)
                ? Optional.of(verifiedAt)
                : Optional.empty();
    }
}
