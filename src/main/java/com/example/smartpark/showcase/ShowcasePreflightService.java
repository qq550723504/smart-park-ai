package com.example.smartpark.showcase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ShowcasePreflightService {

    private static final Logger log = LoggerFactory.getLogger(ShowcasePreflightService.class);
    private static final String FAILURE_REASON = "在线验证未通过";

    private final ScenarioVerificationRegistry registry;
    private final Clock clock;
    private final Duration timeout;
    private final ExecutorService executor;
    private final EnumMap<ShowcaseScenarioId, ShowcasePreflightProbe> probes;

    public ShowcasePreflightService(
            ScenarioVerificationRegistry registry,
            Clock clock,
            Duration timeout,
            ExecutorService executor,
            List<ShowcasePreflightProbe> probes) {
        this.registry = Objects.requireNonNull(registry);
        this.clock = Objects.requireNonNull(clock);
        this.timeout = Objects.requireNonNull(timeout);
        this.executor = Objects.requireNonNull(executor);
        this.probes = new EnumMap<>(ShowcaseScenarioId.class);
        for (ShowcasePreflightProbe probe : Objects.requireNonNull(probes)) {
            ShowcasePreflightProbe nonNullProbe = Objects.requireNonNull(probe);
            ShowcaseScenarioId id = Objects.requireNonNull(nonNullProbe.scenarioId());
            if (this.probes.put(id, nonNullProbe) != null) {
                throw new IllegalArgumentException("duplicate preflight probe: " + id);
            }
        }
    }

    public synchronized ShowcasePreflightReport run() {
        Instant startedAt = clock.instant();
        List<ShowcasePreflightResult> results = new ArrayList<>();
        for (ShowcasePreflightProbe probe : probes.values()) {
            results.add(runProbe(probe));
        }
        return new ShowcasePreflightReport(startedAt, clock.instant(), results);
    }

    private ShowcasePreflightResult runProbe(ShowcasePreflightProbe probe) {
        ShowcaseScenarioId id = probe.scenarioId();
        long startedAt = System.nanoTime();
        Future<ShowcaseProbeResult> future = null;
        Class<? extends Exception> failureType = null;
        try {
            future = executor.submit(probe::probe);
            ShowcaseProbeResult result = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result == ShowcaseProbeResult.PASSED) {
                Instant verifiedAt = clock.instant();
                registry.recordSuccess(id, verifiedAt);
                return new ShowcasePreflightResult(id, ShowcasePreflightStatus.READY, null, verifiedAt);
            }
        } catch (TimeoutException | ExecutionException | RejectedExecutionException exception) {
            failureType = exception.getClass();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            failureType = exception.getClass();
        } finally {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }

        registry.recordFailure(id);
        if (failureType != null) {
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.warn("showcase preflight failed: scenarioId={}, elapsedMs={}, exception={}",
                    id, elapsedMillis, failureType.getName());
        }
        return new ShowcasePreflightResult(id, ShowcasePreflightStatus.NOT_READY, FAILURE_REASON, null);
    }
}
