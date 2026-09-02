package com.example.smartpark.analytics;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsAnalysisServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Test
    void startRunRecordsTerminalOutcomeAndIsIdempotentAfterwards() {
        AtomicReference<String> askedQuestion = new AtomicReference<>();
        OperationsAnalysisService service = service((runId, question, pinned) -> {
            askedQuestion.set(question);
            return completed(runId);
        }, directExecutor());

        var run = service.start("上周能耗");
        assertThat(run.status()).isEqualTo("COMPLETED");
        assertThat(askedQuestion.get()).isEqualTo("上周能耗");

        // Terminal runs reject further lifecycle actions on the same run.
        assertThatThrownBy(() -> service.submitClarification(
                run.runId(), List.of(new MetricSelection("告警", "alert_count"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("待澄清");
    }

    @Test
    void startAndAwaitCompletesForASynchronousTerminalRun() throws Exception {
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> completed(runId), directExecutor());

        CompletableFuture<AnalysisRunStore.RunRecord> future =
                service.startAndAwait("过去5天各楼宇能耗基线偏差");

        assertThat(future.get(1, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
    }

    @Test
    void terminalAwaiterCanStartNextRunAfterPreviousRunReleasesActiveSlot() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<AnalysisRunStore.RunRecord> secondAccepted = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try {
            OperationsAnalysisService service = service((runId, question, pinned) -> {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                } else {
                    secondStarted.countDown();
                }
                return completed(runId);
            }, executor);

            CompletableFuture<AnalysisRunStore.RunRecord> first =
                    service.startAndAwait("第一个分析");
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            first.thenAccept(ignored -> {
                try {
                    secondAccepted.set(service.start("第二个分析"));
                } catch (Throwable failure) {
                    callbackFailure.set(failure);
                } finally {
                    callbackFinished.countDown();
                }
            });

            releaseFirst.countDown();

            assertThat(first.get(1, TimeUnit.SECONDS).status()).isEqualTo("COMPLETED");
            assertThat(callbackFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackFailure).hasValue(null);
            assertThat(secondAccepted.get()).isNotNull();
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void startAndAwaitReturnsFailedAndClarificationTerminalStates() throws Exception {
        OperationsAnalysisService failedService = service(
                (runId, question, pinned) -> new OperationsAnalysisGraph.AnalysisRunResult(
                        runId, OperationsAnalysisGraph.RunOutcome.FAILED, List.of(), List.of(),
                        null, null, null, "report"), directExecutor());
        assertThat(failedService.startAndAwait("失败章节").get(1, TimeUnit.SECONDS).status())
                .isEqualTo("FAILED");

        OperationsAnalysisService clarificationService = service(
                (runId, question, pinned) -> clarifying(runId), directExecutor());
        var paused = clarificationService.startAndAwait("需要澄清章节").get(1, TimeUnit.SECONDS);
        assertThat(paused.status())
                .isEqualTo("NEEDS_CLARIFICATION");
        clarificationService.abort(paused.runId());
        assertThat(clarificationService.start("后续问题").status()).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test
    void persistsTimeResolutionMetadataOnTerminalOutcome() {
        var metadata = com.example.smartpark.analytics.agent.TimeResolutionMetadata.explicit(
                Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z"), "昨天");
        OperationsAnalysisService service = service((runId, question, pinned) ->
                new OperationsAnalysisGraph.AnalysisRunResult(runId,
                        OperationsAnalysisGraph.RunOutcome.COMPLETED, List.of(), List.of(), null, null,
                        "完成", null, null, metadata), directExecutor());

        var run = service.start("昨天能耗");

        assertThat(run.timeResolution()).isEqualTo(metadata);
    }

    @Test
    void activeRunBlocksStartingAnotherOneUntilItReachesTerminalState() {
        OperationsAnalysisService serializingService = service((runId, question, pinned) -> {
            var paused = clarifying(runId);
            return paused;
        }, directExecutor());
        var paused = serializingService.start("进行中的问题");
        assertThat(paused.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThatThrownBy(() -> serializingService.start("并发问题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已有正在进行的分析");
    }

    @Test
    void abortReleasesAClarificationRunForTheNextAnalysis() {
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> clarifying(runId), directExecutor());

        var paused = service.start("预检问题");
        var aborted = service.abort(paused.runId());

        assertThat(aborted.status()).isEqualTo("FAILED");
        assertThat(aborted.failureStage()).isEqualTo("PREFLIGHT_ABORTED");
        assertThat(service.start("新的分析问题").status()).isEqualTo("NEEDS_CLARIFICATION");
    }

    @Test
    void abortCancelsTheWorkerSoTheNextAnalysisCanStartImmediately() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        try {
            OperationsAnalysisService service = service((runId, question, pinned) -> {
                if (calls.incrementAndGet() == 1) {
                    firstStarted.countDown();
                    try {
                        new CountDownLatch(1).await();
                    } catch (InterruptedException cancelled) {
                        Thread.currentThread().interrupt();
                        return completed(runId);
                    }
                }
                secondStarted.countDown();
                return completed(runId);
            }, executor, Duration.ofSeconds(10));

            var first = service.start("预检分析");
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

            var aborted = service.abort(first.runId());
            assertThat(aborted.failureStage()).isEqualTo("PREFLIGHT_ABORTED");

            var second = service.start("新的分析");
            assertThat(secondStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitTerminal(() -> "COMPLETED".equals(service.get(second.runId()).status()));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void runsGraphOnTheConfiguredExecutorWithoutNestedSubmissionDeadlock() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            OperationsAnalysisService service = service(
                    (runId, question, pinned) -> completed(runId), executor, java.time.Duration.ofMillis(500));

            var run = service.start("单线程执行");

            awaitTerminal(() -> "COMPLETED".equals(service.get(run.runId()).status()));
            assertThat(service.get(run.runId()).status()).isEqualTo("COMPLETED");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ambiguityPausesRunAndStructuredClarificationResumesWithPinnedUnderstanding() {
        OperationsAnalysisGraph.AnalysisRunResult clarification = new OperationsAnalysisGraph.AnalysisRunResult(
                UUID.randomUUID(), OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION,
                List.of("“告警”可以指: 告警数量(alert_count) / 高风险告警数量(high_risk_alert_count)"),
                List.of(List.of("alert_count", "high_risk_alert_count")), null, null, null, null);
        AtomicReference<AnalyticsModelClient.QuestionUnderstanding> pinned = new AtomicReference<>();
        OperationsAnalysisService service = service((runId, question, pinnedUnderstanding) -> {
            if (pinnedUnderstanding == null) {
                return clarification;
            }
            pinned.set(pinnedUnderstanding);
            return completed(runId);
        }, directExecutor());

        var paused = service.start("告警情况");
        assertThat(paused.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(paused.clarificationQuestions()).hasSize(1);

        var resumed = service.submitClarification(paused.runId(),
                List.of(new MetricSelection("告警", "alert_count")));

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(pinned.get().metricTerms()).containsExactly("alert_count");
        assertThat(pinned.get().needsClarification()).isFalse();
    }

    @Test
    void clarificationResumeRetainsTheOriginalRequestedTimeRangeAndDimensions() {
        var requested = new AnalyticsModelClient.RequestedTimeRange(
                Instant.parse("2026-08-23T00:00:00Z"), Instant.parse("2026-08-24T00:00:00Z"));
        OperationsAnalysisGraph.AnalysisRunResult clarification = new OperationsAnalysisGraph.AnalysisRunResult(
                UUID.randomUUID(), OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION,
                List.of("请明确告警口径"), List.of(List.of("alert_count")), null, null, null, requestedUnderstanding(requested), null);
        AtomicReference<AnalyticsModelClient.QuestionUnderstanding> pinned = new AtomicReference<>();
        OperationsAnalysisService service = service((runId, question, pinnedUnderstanding) -> {
            if (pinnedUnderstanding == null) return clarification;
            pinned.set(pinnedUnderstanding);
            return completed(runId);
        }, directExecutor());

        var paused = service.start("昨天的告警");
        service.submitClarification(paused.runId(), List.of(new MetricSelection("告警", "alert_count")));

        assertThat(pinned.get().requestedTimeRange()).isEqualTo(requested);
        assertThat(pinned.get().requestedDimensions()).containsExactly("building_id");
    }

    @Test
    void clarificationResumeUsesTheValidatedSnapshotWhenTheDeadlinePassesDuringResume() {
        AdvancingClock clock = new AdvancingClock(NOW, 12);
        AtomicReference<AnalyticsModelClient.QuestionUnderstanding> pinned = new AtomicReference<>();
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (runId, question, pinnedUnderstanding) -> {
                    if (pinnedUnderstanding == null) return clarifying(runId);
                    pinned.set(pinnedUnderstanding);
                    return completed(runId);
                }, directExecutor(), DEFAULT_TIMEOUT, Duration.ofMinutes(5), clock, null);

        var paused = service.start("告警情况");
        var resumed = service.submitClarification(paused.runId(),
                List.of(new MetricSelection("告警", "energy_kwh")));

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(pinned.get()).isNotNull();
    }

    @Test
    void clarificationResumePreservesMetricsThatWereAlreadyResolved() {
        var partialUnderstanding = new AnalyticsModelClient.QuestionUnderstanding(
                "能耗和告警", List.of("energy_kwh", "告警"), List.of(), null, List.of("building_id"));
        OperationsAnalysisGraph.AnalysisRunResult clarification = new OperationsAnalysisGraph.AnalysisRunResult(
                UUID.randomUUID(), OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION,
                List.of("请明确告警口径"), List.of(List.of("alert_count", "high_risk_alert_count")),
                null, null, null, partialUnderstanding, null);
        AtomicReference<AnalyticsModelClient.QuestionUnderstanding> pinned = new AtomicReference<>();
        OperationsAnalysisService service = service((runId, question, pinnedUnderstanding) -> {
            if (pinnedUnderstanding == null) return clarification;
            pinned.set(pinnedUnderstanding);
            return completed(runId);
        }, directExecutor());

        var paused = service.start("能耗和告警");
        service.submitClarification(paused.runId(),
                List.of(new MetricSelection("告警", "alert_count")));

        assertThat(pinned.get().metricTerms()).containsExactly("energy_kwh", "alert_count");
    }

    private static AnalyticsModelClient.QuestionUnderstanding requestedUnderstanding(
            AnalyticsModelClient.RequestedTimeRange requested) {
        return new AnalyticsModelClient.QuestionUnderstanding("昨天的告警", List.of("告警"),
                List.of("请明确告警口径"), requested, List.of("building_id"));
    }

    @Test
    void pausedClarificationRecordCarriesThePendingCandidateOptions() {
        // The status DTO can only expose candidates if the stored record keeps them.
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> clarifying(runId), directExecutor());

        var paused = service.start("告警情况");

        assertThat(paused.status()).isEqualTo("NEEDS_CLARIFICATION");
        assertThat(paused.clarificationOptions()).containsExactly(List.of("energy_kwh"));
    }

    @Test
    void preservesOriginalCreationTimestampAcrossLifecycleTransitions() {
        // createdAt must always reflect when start() created the run — never
        // the time of a later transition (clarification, resume, terminal).
        MutableClock clock = new MutableClock(NOW);
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (runId, question, pinnedUnderstanding) -> {
                    clock.advance(Duration.ofMinutes(1));
                    if (pinnedUnderstanding == null) return clarifying(runId);
                    return completed(runId);
                }, directExecutor(), DEFAULT_TIMEOUT, Duration.ofMinutes(5), clock, null);

        var paused = service.start("告警情况");
        assertThat(paused.createdAt()).isEqualTo(NOW);

        clock.advance(Duration.ofMinutes(2));
        var resumed = service.submitClarification(paused.runId(),
                List.of(new MetricSelection("告警", "energy_kwh")));

        assertThat(resumed.status()).isEqualTo("COMPLETED");
        assertThat(resumed.createdAt()).as("creation time survives transitions").isEqualTo(NOW);
        assertThat(resumed.updatedAt()).isAfter(NOW);
    }

    @Test
    void registersTheExecutionTraceSynchronouslyBeforeReturningTheRunId() {
        // An executor that never runs the task simulates a busy queue: the
        // /executions/{runId}/events trace must already exist when start()
        // hands out the run ID.
        var publisher = new com.example.smartpark.execution.InMemoryExecutionEventPublisher();
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (id, q, p) -> completed(id), task -> { }, DEFAULT_TIMEOUT,
                Clock.fixed(NOW, ZoneOffset.UTC), publisher);

        var run = service.start("上周能耗");
        assertThat(publisher.history(run.runId())).extracting(
                        com.example.smartpark.execution.model.ExecutionEvent::eventType)
                .containsExactly(com.example.smartpark.execution.model.ExecutionEventType.RUN_STARTED);
    }

    @Test
    void rejectsOverloadBeforePersistingAnUnreachableRunOrTrace() {
        AtomicBoolean reject = new AtomicBoolean(true);
        var published = new java.util.ArrayList<com.example.smartpark.execution.model.ExecutionEvent>();
        var publisher = new com.example.smartpark.execution.InMemoryExecutionEventPublisher() {
            @Override
            public com.example.smartpark.execution.model.ExecutionEvent publish(
                    com.example.smartpark.execution.model.ExecutionEvent event) {
                published.add(event);
                return super.publish(event);
            }

            @Override
            public void remove(UUID runId) {
                super.remove(runId);
                published.clear();
            }
        };
        Executor admission = command -> {
            if (reject.get()) throw new java.util.concurrent.RejectedExecutionException("queue full");
            command.run();
        };
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (id, question, pinned) -> completed(id), admission, DEFAULT_TIMEOUT,
                Clock.fixed(NOW, ZoneOffset.UTC), publisher);

        assertThatThrownBy(() -> service.start("过载问题"))
                .isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
        assertThat(published).isEmpty();

        reject.set(false);
        assertThat(service.start("恢复后问题").status()).isEqualTo("COMPLETED");
    }

    @Test
    void boundsAdmissionWhenAWorkerIgnoresThePreviousTimeout() throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        try {
            OperationsAnalysisService service = service((runId, question, pinned) -> {
                firstStarted.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException ignored) {
                    // Simulate a provider call that ignores interruption.
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException ignoredAgain) {
                        Thread.currentThread().interrupt();
                    }
                }
                return completed(runId);
            }, executor, Duration.ofMillis(100));

            var first = service.start("第一个分析");
            assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();
            awaitTerminal(() -> "FAILED".equals(service.get(first.runId()).status()));

            long started = System.nanoTime();
            assertThatThrownBy(() -> service.start("第二个分析"))
                    .isInstanceOf(RejectedExecutionException.class)
                    .hasMessageContaining("admission");
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .isLessThan(Duration.ofSeconds(1));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void statusReadsExpireAnAbandonedClarificationPause() {
        // A run paused past clarificationTimeout must not keep offering a
        // doomed clarification: reading the status applies the expiry
        // transition and closes the trace with a terminal failure event.
        MutableClock clock = new MutableClock(NOW);
        var publisher = new com.example.smartpark.execution.InMemoryExecutionEventPublisher();
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (id, q, p) -> clarifying(id), directExecutor(), DEFAULT_TIMEOUT,
                Duration.ofMinutes(5), clock, publisher);
        var paused = service.start("告警情况");
        assertThat(paused.status()).isEqualTo("NEEDS_CLARIFICATION");

        clock.advance(Duration.ofMinutes(6));
        var polled = service.get(paused.runId());

        assertThat(polled.status()).isEqualTo("FAILED");
        assertThat(polled.failureStage()).isEqualTo("CLARIFICATION_TIMEOUT");
        assertThat(publisher.history(paused.runId())).extracting(
                        com.example.smartpark.execution.model.ExecutionEvent::eventType)
                .contains(com.example.smartpark.execution.model.ExecutionEventType.FAILED);
    }

    @Test
    void unknownMetricInClarificationIsRejectedByCatalog() {
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> clarifying(runId), directExecutor());
        var paused = service.start("告警情况");

        assertThatThrownBy(() -> service.submitClarification(paused.runId(),
                List.of(new MetricSelection("告警", "not_a_metric"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("指标目录");
    }

    @Test
    void timeoutMarksRunFailedWithoutSilentFallback() {
        // A real dedicated executor (as in production wiring) so the timeout
        // fires while the graph execution is still running.
        Executor slowExecutor = java.util.concurrent.Executors.newFixedThreadPool(2);
        OperationsAnalysisService service = service((runId, question, pinned) -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return completed(runId);
        }, slowExecutor, java.time.Duration.ofMillis(50));

        var run = service.start("上周能耗");
        // start returns the RUNNING record immediately; execution is scheduled.
        assertThat(run.status()).isEqualTo("RUNNING");
        awaitTerminal(() -> "FAILED".equals(service.get(run.runId()).status()));
        assertThat(service.get(run.runId()).failureStage()).isEqualTo("ANALYSIS_TIMEOUT");
    }

    @Test
    void failedRunsExposeTheirStageAndRemainTerminal() {
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> new OperationsAnalysisGraph.AnalysisRunResult(runId,
                        OperationsAnalysisGraph.RunOutcome.FAILED, List.of(), List.of(),
                        null, null, null, "validateSqlAst"),
                directExecutor());

        var run = service.start("上周能耗");
        assertThat(run.status()).isEqualTo("FAILED");
        assertThat(run.failureStage()).isEqualTo("validateSqlAst");
        assertThat(service.get(run.runId()).status()).isEqualTo("FAILED");
    }

    @Test
    void terminalRunsCanBeStartedAgainAfterThePreviousRunReleasesItsSlot() {
        OperationsAnalysisService service = service(
                (runId, question, pinned) -> completed(runId), directExecutor());
        var runA = service.start("问题 A");
        var runB = service.start("问题 B");

        assertThat(runA.runId()).isNotEqualTo(runB.runId());
        assertThat(service.get(runA.runId()).question()).isEqualTo("问题 A");
        assertThat(service.get(runB.runId()).question()).isEqualTo("问题 B");
        assertThat(service.get(runA.runId()).status()).isEqualTo("COMPLETED");
        assertThat(service.get(runB.runId()).status()).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentClarificationsResumeThePausedRunExactlyOnce() throws Exception {
        int callers = 8;
        CountDownLatch allValidated = new CountDownLatch(callers);
        MetricCatalog slowCatalog = new MetricCatalog() {
            @Override
            public Optional<com.example.smartpark.analytics.catalog.MetricDefinition> findByName(String name) {
                allValidated.countDown();
                try {
                    assertThat(allValidated.await(2, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return super.findByName(name);
            }
        };
        AtomicInteger resumeCalls = new AtomicInteger();
        OperationsAnalysisService service = new OperationsAnalysisService(slowCatalog,
                (runId, question, pinned) -> {
                    if (pinned == null) return clarifying(runId);
                    resumeCalls.incrementAndGet();
                    return completed(runId);
                }, directExecutor(), DEFAULT_TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC));
        var paused = service.start("告警情况");

        ExecutorService callersExecutor = Executors.newFixedThreadPool(callers);
        try {
            CountDownLatch start = new CountDownLatch(1);
            var attempts = java.util.stream.IntStream.range(0, callers)
                    .mapToObj(ignored -> callersExecutor.submit(() -> {
                        start.await();
                        try {
                            service.submitClarification(paused.runId(),
                                    List.of(new MetricSelection("告警", "energy_kwh")));
                            return true;
                        } catch (IllegalStateException | IllegalArgumentException rejected) {
                            return false;
                        }
                    })).toList();
            start.countDown();

            long successes = 0;
            for (var attempt : attempts) {
                if (attempt.get(3, TimeUnit.SECONDS)) successes++;
            }
            assertThat(successes).isEqualTo(1);
            assertThat(resumeCalls).hasValue(1);
        } finally {
            callersExecutor.shutdownNow();
        }
    }

    @Test
    void expiredClarificationReleasesTheOnlyActiveSlot() {
        MutableClock clock = new MutableClock(NOW);
        OperationsAnalysisService service = new OperationsAnalysisService(new MetricCatalog(),
                (runId, question, pinned) -> clarifying(runId), directExecutor(),
                DEFAULT_TIMEOUT, Duration.ofMinutes(5), clock, null);
        var abandoned = service.start("需要澄清的旧问题");

        clock.advance(Duration.ofMinutes(6));
        var replacement = service.start("新的分析问题");

        assertThat(service.get(abandoned.runId()).status()).isEqualTo("FAILED");
        assertThat(service.get(abandoned.runId()).failureStage()).isEqualTo("CLARIFICATION_TIMEOUT");
        assertThat(replacement.runId()).isNotEqualTo(abandoned.runId());
    }

    // ---- fixtures ----------------------------------------------------------

    private static final java.time.Duration DEFAULT_TIMEOUT = java.time.Duration.ofSeconds(30);

    private OperationsAnalysisService service(OperationsAnalysisService.GraphRunner runner,
                                              Executor executor) {
        return new OperationsAnalysisService(new MetricCatalog(), runner,
                executor, DEFAULT_TIMEOUT, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private OperationsAnalysisService service(OperationsAnalysisService.GraphRunner runner,
                                              Executor executor, java.time.Duration timeout) {
        return new OperationsAnalysisService(new MetricCatalog(), runner,
                executor, timeout, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Executor directExecutor() {
        return Runnable::run;
    }

    private static void awaitTerminal(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5_000;
        try {
            while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
                Thread.sleep(10);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for a terminal analysis run", interrupted);
        }
        org.assertj.core.api.Assertions.assertThat(condition.getAsBoolean()).isTrue();
    }

    private static OperationsAnalysisGraph.AnalysisRunResult completed(UUID runId) {
        return new OperationsAnalysisGraph.AnalysisRunResult(runId,
                OperationsAnalysisGraph.RunOutcome.COMPLETED, List.of(), List.of(),
                null, null, "共 3 行结果。", null);
    }

    private static OperationsAnalysisGraph.AnalysisRunResult clarifying(UUID runId) {
        return new OperationsAnalysisGraph.AnalysisRunResult(runId,
                OperationsAnalysisGraph.RunOutcome.NEEDS_CLARIFICATION,
                List.of("请明确指标口径"), List.of(List.of("energy_kwh")), null, null, null, null);
    }

    private static class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }

    private static final class AdvancingClock extends MutableClock {
        private final int advanceOnCall;
        private int calls;

        private AdvancingClock(Instant instant, int advanceOnCall) {
            super(instant);
            this.advanceOnCall = advanceOnCall;
        }

        @Override public Instant instant() {
            calls++;
            if (calls == advanceOnCall) advance(Duration.ofMinutes(6));
            return super.instant();
        }
    }
}
