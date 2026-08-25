package com.example.smartpark.analytics;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
}
