package com.example.smartpark.analytics;

import com.example.smartpark.analytics.agent.AnalyticsModelClient;
import com.example.smartpark.analytics.agent.OperationsAnalysisGraph;
import com.example.smartpark.analytics.catalog.MetricCatalog;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;

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
    private final ExecutionEventPublisher events;

    /** Pending ambiguity per run: one candidate metric set per clarification question. */
    private final java.util.concurrent.ConcurrentHashMap<UUID, List<java.util.Set<String>>> pendingClarifications
            = new java.util.concurrent.ConcurrentHashMap<>();

    public OperationsAnalysisService(MetricCatalog catalog,
                                     GraphRunner runner,
                                     Executor analyticsExecutor,
                                     Duration timeout,
                                     Clock clock) {
        this(catalog, runner, analyticsExecutor, timeout, clock, null);
    }

    public OperationsAnalysisService(MetricCatalog catalog,
                                     GraphRunner runner,
                                     Executor analyticsExecutor,
                                     Duration timeout,
                                     Clock clock,
                                     ExecutionEventPublisher events) {
        this.catalog = catalog;
        this.runner = runner;
        this.executor = analyticsExecutor;
        this.timeout = timeout;
        this.clock = clock;
        this.events = events;
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
        // The 202 response must return immediately: schedule the blocking graph
        // execution on the analytics executor and hand back the RUNNING record.
        CompletableFuture.runAsync(() -> execute(runId, question, null), executor);
        return store.get(runId);
    }

    public AnalysisRunStore.RunRecord submitClarification(UUID runId, List<MetricSelection> selections) {
        var current = get(runId);
        if (!"NEEDS_CLARIFICATION".equals(current.status())) {
            throw new IllegalStateException("该运行不处于待澄清状态");
        }
        List<MetricSelection> safeSelections = validateSelections(selections);
        validateAgainstPendingClarification(runId, safeSelections);
        String pinnedTerms = safeSelections.stream()
                .map(MetricSelection::metric)
                .reduce((a, b) -> a + ", " + b)
                .orElse("");
        var pinned = new AnalyticsModelClient.QuestionUnderstanding(
                current.question(), safeSelections.stream().map(MetricSelection::metric).toList(), List.of());
        store.put(rerunningRecord(runId));
        pendingClarifications.remove(runId);
        CompletableFuture.runAsync(
                () -> execute(runId, current.question() + "（已明确指标: " + pinnedTerms + "）", pinned), executor);
        return store.get(runId);
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

    private void validateAgainstPendingClarification(UUID runId, List<MetricSelection> selections) {
        var pending = pendingClarifications.get(runId);
        if (pending == null || pending.size() != selections.size()) {
            throw new IllegalArgumentException("澄清选择数量与待澄清问题不一致");
        }
        for (int i = 0; i < selections.size(); i++) {
            if (!pending.get(i).contains(selections.get(i).metric())) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个选择的指标不在该问题的候选范围内");
            }
        }
    }

    private AnalysisRunStore.RunRecord execute(UUID runId, String question,
                                               AnalyticsModelClient.QuestionUnderstanding pinned) {
        long start = System.currentTimeMillis();
        // FutureTask (not CompletableFuture): cancel(true) actually interrupts
        // the graph thread, so a hung model or query call cannot keep holding
        // one of the two analytics executor threads after the deadline.
        var task = new java.util.concurrent.FutureTask<>(
                () -> runner.execute(runId, question, pinned));
        executor.execute(task);
        try {
            OperationsAnalysisGraph.AnalysisRunResult outcome = task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return persistOutcome(runId, question, outcome, System.currentTimeMillis() - start);
        } catch (TimeoutException timedOut) {
            return terminate(runId, question, "ANALYSIS_TIMEOUT", System.currentTimeMillis() - start, task);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            task.cancel(true);
            return terminate(runId, question, "ANALYSIS_INTERRUPTED", System.currentTimeMillis() - start, null);
        } catch (java.util.concurrent.ExecutionException failedExecution) {
            return persistFailure(runId, question, "ANALYSIS_ABORTED",
                    System.currentTimeMillis() - start);
        } catch (java.util.concurrent.CancellationException cancelled) {
            // Timeout path already persisted and terminated the trace.
            return store.get(runId);
        }
    }

    /** Cancels the work, persists the failure and closes the execution trace with FAILED. */
    private AnalysisRunStore.RunRecord terminate(UUID runId, String question, String stage,
                                                 long durationMs, java.util.concurrent.FutureTask<?> task) {
        if (task != null) {
            task.cancel(true);
        }
        AnalysisRunStore.RunRecord record = persistFailure(runId, question, stage, durationMs);
        if (events != null) {
            try {
                events.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(clock),
                        ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.FAILURE,
                        ExecutionEventType.FAILED, ExecutionStatus.FAILED, "分析超时，已终止",
                        DisplayPayload.error(ExecutionStage.FAILURE, stage, true,
                                stage.equals("ANALYSIS_TIMEOUT") ? "分析超过时间上限，已取消执行" : "分析执行被中断")));
            } catch (IllegalStateException alreadyClosed) {
                // Trace already terminal; nothing more to do.
            }
        }
        return record;
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
                    durationMs, null, Instant.now(clock),
                    outcome.result() == null ? List.of() : outcome.result().columnNames(),
                    outcome.result() == null ? List.of() : outcome.result().rows());
            case NEEDS_CLARIFICATION -> {
                pendingClarifications.put(runId, outcome.clarificationOptions().stream()
                        .map(java.util.Set::copyOf)
                        .toList());
                yield new AnalysisRunStore.RunRecord(runId, question, "NEEDS_CLARIFICATION",
                        List.copyOf(outcome.clarificationQuestions()), "", 0, false, durationMs, null,
                        Instant.now(clock), List.of(), List.of());
            }
            case FAILED -> {
                pendingClarifications.remove(runId);
                yield new AnalysisRunStore.RunRecord(runId, question, "FAILED",
                        List.of(), "", 0, false, durationMs,
                        outcome.failureStage() == null ? "UNKNOWN" : outcome.failureStage(),
                        Instant.now(clock), List.of(), List.of());
            }
        };
        store.put(record);
        return record;
    }

    private AnalysisRunStore.RunRecord persistFailure(UUID runId, String question,
                                                      String stage, long durationMs) {
        var record = new AnalysisRunStore.RunRecord(runId, question, "FAILED", List.of(),
                "", 0, false, durationMs, stage, Instant.now(clock), List.of(), List.of());
        store.put(record);
        return record;
    }

    private AnalysisRunStore.RunRecord rerunningRecord(UUID runId) {
        var previous = get(runId);
        return new AnalysisRunStore.RunRecord(runId, previous.question(), "RUNNING",
                List.of(), "", 0, false, 0, null, Instant.now(clock), List.of(), List.of());
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
                    List.of(), "", 0, false, 0, null, Instant.now(Clock.systemUTC()), List.of(), List.of());
        }
    }
}
