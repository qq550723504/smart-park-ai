package com.example.smartpark.orchestration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/** Test adapter; production wiring always uses the durable file store. */
public final class InMemoryOrchestrationRunStore implements OrchestrationRunStore {
    private final OrchestrationStoreLimits limits;
    private final Map<UUID, OrchestrationRun> runs = new LinkedHashMap<>();
    private final Map<String, UUID> keys = new LinkedHashMap<>();

    public InMemoryOrchestrationRunStore() {
        this(OrchestrationStoreLimits.defaults());
    }

    InMemoryOrchestrationRunStore(OrchestrationStoreLimits limits) {
        this.limits = java.util.Objects.requireNonNull(limits, "limits");
    }

    @Override
    public synchronized StartResult createOrGet(String key, String fingerprint,
                                                Supplier<OrchestrationRun> factory) {
        UUID existingId = keys.get(key);
        if (existingId != null) {
            OrchestrationRun existing = runs.get(existingId);
            if (!existing.requestFingerprint().equals(fingerprint)) {
                throw new IllegalStateException("Idempotency-Key was already used for another orchestration request");
            }
            return new StartResult(existing, false);
        }
        Map<UUID, OrchestrationRun> nextRuns = limits.prepareForAdmission(runs);
        OrchestrationRun created = factory.get();
        if (!key.equals(created.idempotencyKey()) || !fingerprint.equals(created.requestFingerprint())) {
            throw new IllegalArgumentException("created run does not match its idempotency request");
        }
        nextRuns.put(created.id(), created);
        runs.clear();
        runs.putAll(nextRuns);
        keys.entrySet().removeIf(entry -> !runs.containsKey(entry.getValue()));
        keys.put(key, created.id());
        return new StartResult(created, true);
    }

    @Override
    public synchronized Optional<OrchestrationRun> find(UUID runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public synchronized OrchestrationRun update(UUID runId, UnaryOperator<OrchestrationRun> transition) {
        OrchestrationRun current = Optional.ofNullable(runs.get(runId))
                .orElseThrow(() -> new NoSuchElementException("Unknown orchestration run"));
        OrchestrationRun updated = transition.apply(current);
        runs.put(runId, updated);
        return updated;
    }

    @Override
    public synchronized List<OrchestrationRun> nonTerminalRuns() {
        return runs.values().stream().filter(run -> !run.status().isTerminal()).toList();
    }
}
