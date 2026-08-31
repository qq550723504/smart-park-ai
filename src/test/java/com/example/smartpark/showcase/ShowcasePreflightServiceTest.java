package com.example.smartpark.showcase;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShowcasePreflightServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-30T10:00:00Z");

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    @Test
    void runsProbesInScenarioIdentifierOrderAndRecordsSuccess() {
        var registry = new InMemoryScenarioVerificationRegistry();
        var service = service(registry, Duration.ofSeconds(1), List.of(
                probe(ShowcaseScenarioId.VOICE_ASSISTANT, ShowcaseProbeResult.PASSED),
                probe(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseProbeResult.PASSED)));

        ShowcasePreflightReport report = service.run();

        assertThat(report.results()).extracting(ShowcasePreflightResult::scenarioId)
                .containsExactly(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseScenarioId.VOICE_ASSISTANT);
        assertThat(report.results()).extracting(ShowcasePreflightResult::status)
                .containsOnly(ShowcasePreflightStatus.READY);
        assertThat(report.results()).extracting(ShowcasePreflightResult::verifiedAt)
                .containsOnly(NOW);
    }

    @Test
    void clearsOldReceiptAndMasksProbeException() {
        var registry = new InMemoryScenarioVerificationRegistry();
        registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, NOW.minusSeconds(1));
        ShowcasePreflightProbe failing = new ShowcasePreflightProbe() {
            @Override public ShowcaseScenarioId scenarioId() {
                return ShowcaseScenarioId.OPERATIONS_ANALYSIS;
            }

            @Override public ShowcaseProbeResult probe() {
                throw new IllegalStateException("vendor response must not leak");
            }
        };

        ShowcasePreflightResult result = service(registry, Duration.ofSeconds(1), List.of(failing))
                .run().results().get(0);

        assertThat(result.status()).isEqualTo(ShowcasePreflightStatus.NOT_READY);
        assertThat(result.reason()).isEqualTo("在线验证未通过");
        assertThat(result.verifiedAt()).isNull();
        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
                NOW, Duration.ofMinutes(15))).isEmpty();
    }

    @Test
    void cancelsAProbeThatExceedsTheConfiguredTimeout() throws InterruptedException {
        var interrupted = new AtomicBoolean();
        ShowcasePreflightProbe blocking = new ShowcasePreflightProbe() {
            @Override public ShowcaseScenarioId scenarioId() {
                return ShowcaseScenarioId.VOICE_ASSISTANT;
            }

            @Override public ShowcaseProbeResult probe() {
                try {
                    new CountDownLatch(1).await();
                    return ShowcaseProbeResult.PASSED;
                } catch (InterruptedException expected) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                    return ShowcaseProbeResult.FAILED;
                }
            }
        };

        ShowcasePreflightResult result = service(new InMemoryScenarioVerificationRegistry(),
                Duration.ofMillis(25), List.of(blocking)).run().results().get(0);

        assertThat(result.status()).isEqualTo(ShowcasePreflightStatus.NOT_READY);
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!interrupted.get() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(interrupted).isTrue();
    }

    @Test
    void rejectsDuplicateScenarioProbeIds() {
        ShowcasePreflightProbe first = probe(
                ShowcaseScenarioId.OPERATIONS_ANALYSIS, ShowcaseProbeResult.PASSED);
        ShowcasePreflightProbe duplicate = probe(
                ShowcaseScenarioId.OPERATIONS_ANALYSIS, ShowcaseProbeResult.FAILED);

        assertThatThrownBy(() -> service(new InMemoryScenarioVerificationRegistry(),
                Duration.ofSeconds(1), List.of(first, duplicate)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate preflight probe");
    }

    @Test
    void defaultsPreflightTimeoutToNinetySecondsAndRejectsNonPositiveValues() {
        ShowcaseProperties properties = new ShowcaseProperties();
        assertThat(properties.getVerificationTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.getPreflightTimeout()).isEqualTo(Duration.ofSeconds(90));

        properties.setPreflightTimeout(Duration.ZERO);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("smartpark.showcase.preflight-timeout must be positive");
    }

    @Test
    void preservesPositiveSubMillisecondTimeoutWithNanosecondPrecision() {
        var timeoutExecutor = new TimeoutCapturingExecutor();
        var service = new ShowcasePreflightService(new InMemoryScenarioVerificationRegistry(),
                Clock.fixed(NOW, ZoneOffset.UTC), Duration.ofNanos(1), timeoutExecutor,
                List.of(probe(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseProbeResult.PASSED)));

        ShowcasePreflightReport report = service.run();

        assertThat(report.results()).extracting(ShowcasePreflightResult::status)
                .containsExactly(ShowcasePreflightStatus.READY);
        assertThat(timeoutExecutor.timeout()).isEqualTo(1);
        assertThat(timeoutExecutor.unit()).isEqualTo(TimeUnit.NANOSECONDS);
    }

    @Test
    void masksCancelledProbeClearsReceiptAndContinuesLaterProbes() {
        var registry = new InMemoryScenarioVerificationRegistry();
        registry.recordSuccess(ShowcaseScenarioId.ALERT_WORKFLOW, NOW.minusSeconds(1));
        var service = new ShowcasePreflightService(registry, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(1), new CancellingFirstTaskExecutor(), List.of(
                probe(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseProbeResult.PASSED),
                probe(ShowcaseScenarioId.VOICE_ASSISTANT, ShowcaseProbeResult.PASSED)));

        ShowcasePreflightReport report = service.run();

        assertThat(report.results()).extracting(ShowcasePreflightResult::status)
                .containsExactly(ShowcasePreflightStatus.NOT_READY, ShowcasePreflightStatus.READY);
        assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.ALERT_WORKFLOW,
                NOW, Duration.ofMinutes(15))).isEmpty();
    }

    private ShowcasePreflightService service(
            ScenarioVerificationRegistry registry,
            Duration timeout,
            List<ShowcasePreflightProbe> probes) {
        return new ShowcasePreflightService(registry, Clock.fixed(NOW, ZoneOffset.UTC), timeout,
                executor, probes);
    }

    private static ShowcasePreflightProbe probe(
            ShowcaseScenarioId scenarioId,
            ShowcaseProbeResult result) {
        return new ShowcasePreflightProbe() {
            @Override public ShowcaseScenarioId scenarioId() {
                return scenarioId;
            }

            @Override public ShowcaseProbeResult probe() {
                return result;
            }
        };
    }

    private static final class TimeoutCapturingExecutor extends AbstractExecutorService {

        private final AtomicLong timeout = new AtomicLong(-1);
        private final AtomicReference<TimeUnit> unit = new AtomicReference<>();

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            try {
                return new Future<>() {
                    private final T value = task.call();

                    @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
                    @Override public boolean isCancelled() { return false; }
                    @Override public boolean isDone() { return true; }
                    @Override public T get() { return value; }

                    @Override
                    public T get(long timeout, TimeUnit unit) {
                        TimeoutCapturingExecutor.this.timeout.set(timeout);
                        TimeoutCapturingExecutor.this.unit.set(unit);
                        return value;
                    }
                };
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        long timeout() {
            return timeout.get();
        }

        TimeUnit unit() {
            return unit.get();
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
        @Override public void execute(Runnable command) { command.run(); }
    }

    private static final class CancellingFirstTaskExecutor extends AbstractExecutorService {

        private boolean firstTask = true;

        @Override
        public void execute(Runnable command) {
            if (firstTask) {
                firstTask = false;
                ((Future<?>) command).cancel(false);
                return;
            }
            command.run();
        }

        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
