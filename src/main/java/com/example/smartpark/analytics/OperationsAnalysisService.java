package com.example.smartpark.analytics;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Run lifecycle for natural-language operations analysis: start, ambiguity
 * pause, structured clarification resume, terminal idempotency and timeout.
 * All state transitions go through the run store; failures terminate runs
 * explicitly — there is no silent fallback result.
 */
public class OperationsAnalysisService {

    /** Boundary over the compiled graph so tests can script outcomes. */
    public interface GraphRunner {
        OperationsAnalysisGraph.AnalysisRunResult execute(
                UUID runId, String question, AnalyticsModelClient.QuestionUnderstanding pinnedUnderstanding);
    }

    private static final int MAX_QUESTION_LENGTH = 500;
    private static final int MAX_SELECTIONS = 5;

    private final MetricCatalog catalog;
    private final GraphRunner runner;
    private final Executor executor;
    private final Duration timeout;
    private final Clock clock;
    private final AnalysisRunStore store = new AnalysisRunStore();

    public OperationsAnalysisService(MetricCatalog catalog,
                                     GraphRunner runner,
                                     Executor analyticsExecutor,
                                     Duration timeout,
                                     Clock clock) {
        this.catalog = catalog;
        this.runner = runner;
        this.executor = analyticsExecutor;
        this.timeout = timeout;
        this.clock = clock;
    }

    public AnalysisRunStore.RunRecord get(UUID runId) {
        var record = store.get(runId);
        if (record == null) {
            throw new java.util.NoSuchElementException("Unknown analysis run: " + runId);
        }
        return record;
    }

    public AnalysisRunStore.RunRecord start(String question) {
        requireValidQuestion(question);
        ensureNoActiveRun();
        UUID runId = UUID.randomUUID();
        store.put(new RecordBuilder(runId, question).running());
        return execute(runId, question, null);
    }

    public AnalysisRunStore.RunRecord submitClarification(UUID runId, List<MetricSelection> selections) {
        var current = get(runId);
        if (!"NEEDS_CLARIFICATION".equals(current.status())) {
            throw new IllegalStateException("该运行不处于待澄清状态");
        }
        List<MetricSelection> safeSelections = validateSelections(selections);
        String pinnedTerms = safeSelections.stream()
                .map(MetricSelection::metric)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        var pinned = new AnalyticsModelClient.QuestionUnderstanding(
                current.question(), safeSelections.stream().map(MetricSelection::metric).toList(), List.of());
        store.put(rerunningRecord(runId));
        return executePinned(runId, current.question() + "（已明确指标: " + pinnedTerms + "）", pinned);
    }

    // ---- internals ---------------------------------------------------------

    private void requireValidQuestion(String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("分析问题不能为空");
        }
        if (question.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("分析问题长度不能超过 " + MAX_QUESTION_LENGTH);
        }
    }

    private void ensureNoActiveRun() {
        if (store.existsActive()) {
            throw new IllegalStateException("已有正在进行的分析，请等待完成后再启动");
        }
    }

    private List<MetricSelection> validateSelections(List<MetricSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new IllegalArgumentException("澄清必须至少包含一个指标选择");
        }
        if (selections.size() > MAX_SELECTIONS) {
            throw new IllegalArgumentException("澄清选择数量不能超过 " + MAX_SELECTIONS);
        }
        selections.forEach(selection -> selection.validateAgainst(catalog));
        return List.copyOf(selections);
    }

    private AnalysisRunStore.RunRecord execute(UUID runId, String question,
                                               AnalyticsModelClient.QuestionUnderstanding pinned) {
        long start = System.currentTimeMillis();
        try {
            OperationsAnalysisGraph.AnalysisRunResult outcome = CompletableFuture
                    .<OperationsAnalysisGraph.AnalysisRunResult>supplyAsync(
                            () -> runner.execute(runId, question, pinned), executor)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return persistOutcome(runId, question, outcome, System.currentTimeMillis() - start);
        } catch (TimeoutException timedOut) {
            return persistFailure(runId, question, "ANALYSIS_TIMEOUT",
                    System.currentTimeMillis() - start);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return persistFailure(runId, question, "ANALYSIS_INTERRUPTED",
                    System.currentTimeMillis() - start);
        } catch (java.util.concurrent.ExecutionException failedExecution) {
            return persistFailure(runId, question, "ANALYSIS_ABORTED",
                    System.currentTimeMillis() - start);
        }
    }

    private AnalysisRunStore.RunRecord executePinned(UUID runId, String enrichedQuestion,
                                                     AnalyticsModelClient.QuestionUnderstanding pinned) {
        return execute(runId, enrichedQuestion, pinned);
    }

    private AnalysisRunStore.RunRecord persistOutcome(UUID runId, String question,
                                                      OperationsAnalysisGraph.AnalysisRunResult outcome,
                                                      long durationMs) {
        // A timed-out run was already marked FAILED; late completions never overwrite terminal states.
        var existing = store.get(runId);
        if (existing != null && ("FAILED".equals(existing.status()) || "COMPLETED".equals(existing.status()))) {
            return existing;
        }
        AnalysisRunStore.RunRecord record = switch (outcome.outcome()) {
            case COMPLETED -> new AnalysisRunStore.RunRecord(runId, question, "COMPLETED",
                    List.of(), outcome.summary() == null ? "" : outcome.summary(),
                    outcome.result() == null ? 0 : outcome.result().rowCount(),
                    outcome.result() != null && outcome.result().truncated(),
                    durationMs, null, Instant.now(clock));
            case NEEDS_CLARIFICATION -> new AnalysisRunStore.RunRecord(runId, question, "NEEDS_CLARIFICATION",
                    List.copyOf(outcome.clarificationQuestions()), "", 0, false, durationMs, null,
                    Instant.now(clock));
            case FAILED -> new AnalysisRunStore.RunRecord(runId, question, "FAILED",
                    List.of(), "", 0, false, durationMs,
                    outcome.failureStage() == null ? "UNKNOWN" : outcome.failureStage(),
                    Instant.now(clock));
        };
        store.put(record);
        return record;
    }

    private AnalysisRunStore.RunRecord persistFailure(UUID runId, String question,
                                                      String stage, long durationMs) {
        var record = new AnalysisRunStore.RunRecord(runId, question, "FAILED", List.of(),
                "", 0, false, durationMs, stage, Instant.now(clock));
        store.put(record);
        return record;
    }

    private AnalysisRunStore.RunRecord rerunningRecord(UUID runId) {
        var previous = get(runId);
        return new AnalysisRunStore.RunRecord(runId, previous.question(), "RUNNING",
                List.of(), "", 0, false, 0, null, Instant.now(clock));
    }

    private static final class RecordBuilder {
        private final UUID runId;
        private final String question;

        RecordBuilder(UUID runId, String question) {
            this.runId = runId;
            this.question = question;
        }

        AnalysisRunStore.RunRecord running() {
            return new AnalysisRunStore.RunRecord(runId, question, "RUNNING",
                    List.of(), "", 0, false, 0, null, Instant.now(Clock.systemUTC()));
        }
    }
}
