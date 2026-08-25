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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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
    private final Duration clarificationTimeout;
    private final Clock clock;
    private final AnalysisRunStore store = new AnalysisRunStore();
    private final ExecutionEventPublisher events;
    private final Object lifecycleLock = new Object();
    private UUID activeRunId;

    /** Pending ambiguity per run: one candidate metric set per clarification question. */
    private final Map<UUID, PendingClarification> pendingClarifications = new java.util.HashMap<>();

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
        this(catalog, runner, analyticsExecutor, timeout, Duration.ofMinutes(5), clock, events);
    }

    public OperationsAnalysisService(MetricCatalog catalog,
                                     GraphRunner runner,
                                     Executor analyticsExecutor,
                                     Duration timeout,
                                     Duration clarificationTimeout,
                                     Clock clock,
                                     ExecutionEventPublisher events) {
        this.catalog = catalog;
        this.runner = runner;
        this.executor = analyticsExecutor;
        this.timeout = timeout;
        if (clarificationTimeout == null || clarificationTimeout.isZero() || clarificationTimeout.isNegative()) {
            throw new IllegalArgumentException("clarificationTimeout must be positive");
        }
        this.clarificationTimeout = clarificationTimeout;
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
        UUID runId = UUID.randomUUID();
        AnalysisRunStore.RunRecord expired;
        synchronized (lifecycleLock) {
            expired = expireAbandonedClarificationLocked(Instant.now(clock));
            if (activeRunId != null) {
                throw new IllegalStateException("已有正在进行的分析，请等待完成后再启动");
            }
            activeRunId = runId;
            store.put(new RecordBuilder(runId, question, clock).running());
        }
        publishExpiredClarification(expired);
        launch(runId, question, null);
        return store.get(runId);
    }

    public AnalysisRunStore.RunRecord submitClarification(UUID runId, List<MetricSelection> selections) {
        List<MetricSelection> safeSelections = validateSelections(selections);
        AnalysisRunStore.RunRecord current;
        AnalysisRunStore.RunRecord expired;
        AnalyticsModelClient.QuestionUnderstanding pinned = null;
        String pinnedTerms = "";
        synchronized (lifecycleLock) {
            expired = expireAbandonedClarificationLocked(Instant.now(clock));
            current = store.get(runId);
            if (expired == null || !runId.equals(expired.runId())) {
                if (current == null) {
                    throw new java.util.NoSuchElementException("Unknown analysis run: " + runId);
                }
                if (!"NEEDS_CLARIFICATION".equals(current.status())) {
                    throw new IllegalStateException("该运行不处于待澄清状态");
                }
                PendingClarification pending = pendingClarifications.get(runId);
                validateAgainstPendingClarification(pending, safeSelections);
                pinnedTerms = safeSelections.stream()
                        .map(MetricSelection::metric)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                AnalyticsModelClient.RequestedTimeRange requestedTimeRange = pending.understanding() == null
                        ? null : pending.understanding().requestedTimeRange();
                pinned = new AnalyticsModelClient.QuestionUnderstanding(
                        current.question(), safeSelections.stream().map(MetricSelection::metric).toList(),
                        List.of(), requestedTimeRange);
                store.put(rerunningRecord(runId));
                pendingClarifications.remove(runId);
            }
        }
        publishExpiredClarification(expired);
        if (pinned == null) {
            throw new IllegalStateException("该运行的澄清等待已超时");
        }
        launch(runId, current.question() + "（已明确指标: " + pinnedTerms + "）", pinned);
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

    private void validateAgainstPendingClarification(PendingClarification pending,
                                                     List<MetricSelection> selections) {
        if (pending == null || pending.candidates().size() != selections.size()) {
            throw new IllegalArgumentException("澄清选择数量与待澄清问题不一致");
        }
        for (int i = 0; i < selections.size(); i++) {
            if (!pending.candidates().get(i).contains(selections.get(i).metric())) {
                throw new IllegalArgumentException("第 " + (i + 1) + " 个选择的指标不在该问题的候选范围内");
            }
        }
    }

    private void launch(UUID runId, String question,
                        AnalyticsModelClient.QuestionUnderstanding pinned) {
        long startedAt = System.currentTimeMillis();
        FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task = new FutureTask<>(
                () -> runner.execute(runId, question, pinned)) {
            @Override
            protected void done() {
                completeTask(runId, question, startedAt, this);
            }
        };
        try {
            // The graph runs directly on the configured executor. Submitting an
            // inner task and waiting on it would deadlock a bounded executor.
            executor.execute(task);
        } catch (RuntimeException rejected) {
            persistFailure(runId, question, "ANALYSIS_ABORTED", System.currentTimeMillis() - startedAt);
            throw rejected;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> task.cancel(true));
    }

    private void completeTask(UUID runId, String question, long startedAt,
                              FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task) {
        long durationMs = System.currentTimeMillis() - startedAt;
        if (task.isCancelled()) {
            terminate(runId, question, "ANALYSIS_TIMEOUT", durationMs);
            return;
        }
        try {
            persistOutcome(runId, question, task.get(), durationMs);
        } catch (java.util.concurrent.ExecutionException failedExecution) {
            persistFailure(runId, question, "ANALYSIS_ABORTED", durationMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            persistFailure(runId, question, "ANALYSIS_INTERRUPTED", durationMs);
        } catch (java.util.concurrent.CancellationException cancelled) {
            terminate(runId, question, "ANALYSIS_TIMEOUT", durationMs);
        }
    }

    /** Cancels the work, persists the failure and closes the execution trace with FAILED. */
    private AnalysisRunStore.RunRecord terminate(UUID runId, String question, String stage,
                                                 long durationMs) {
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
        synchronized (lifecycleLock) {
            // A timed-out run was already marked FAILED; late completions never overwrite terminal states.
            var existing = store.get(runId);
            if (existing != null && ("FAILED".equals(existing.status()) || "COMPLETED".equals(existing.status()))) {
                return existing;
            }
            Instant now = Instant.now(clock);
            AnalysisRunStore.RunRecord record = switch (outcome.outcome()) {
                case COMPLETED -> new AnalysisRunStore.RunRecord(runId, question, "COMPLETED",
                        List.of(), List.of(), outcome.summary() == null ? "" : outcome.summary(),
                        outcome.result() == null ? 0 : outcome.result().rowCount(),
                        outcome.result() != null && outcome.result().truncated(),
                        durationMs, null, now,
                        outcome.result() == null ? List.of() : outcome.result().columnNames(),
                        outcome.result() == null ? List.of() : outcome.result().rows());
                case NEEDS_CLARIFICATION -> {
                    pendingClarifications.put(runId, new PendingClarification(
                            List.copyOf(outcome.clarificationQuestions()),
                            outcome.clarificationOptions().stream().map(Set::copyOf).toList(),
                            outcome.understanding(),
                            now.plus(clarificationTimeout)));
                    yield new AnalysisRunStore.RunRecord(runId, question, "NEEDS_CLARIFICATION",
                            List.copyOf(outcome.clarificationQuestions()),
                            outcome.clarificationOptions().stream().map(List::copyOf).toList(),
                            "", 0, false, durationMs, null,
                            now, List.of(), List.of());
                }
                case FAILED -> {
                    pendingClarifications.remove(runId);
                    yield new AnalysisRunStore.RunRecord(runId, question, "FAILED",
                            List.of(), List.of(), "", 0, false, durationMs,
                            outcome.failureStage() == null ? "UNKNOWN" : outcome.failureStage(),
                            now, List.of(), List.of());
                }
            };
            store.put(record);
            if (!"NEEDS_CLARIFICATION".equals(record.status())) {
                releaseActiveLocked(runId);
            }
            return record;
        }
    }

    private AnalysisRunStore.RunRecord persistFailure(UUID runId, String question,
                                                      String stage, long durationMs) {
        synchronized (lifecycleLock) {
            var record = new AnalysisRunStore.RunRecord(runId, question, "FAILED", List.of(), List.of(),
                    "", 0, false, durationMs, stage, Instant.now(clock), List.of(), List.of());
            store.put(record);
            pendingClarifications.remove(runId);
            releaseActiveLocked(runId);
            return record;
        }
    }

    private void releaseActiveLocked(UUID runId) {
        if (runId.equals(activeRunId)) {
            activeRunId = null;
        }
    }

    private AnalysisRunStore.RunRecord expireAbandonedClarificationLocked(Instant now) {
        if (activeRunId == null) return null;
        PendingClarification pending = pendingClarifications.get(activeRunId);
        if (pending == null || now.isBefore(pending.expiresAt())) return null;
        AnalysisRunStore.RunRecord previous = store.get(activeRunId);
        AnalysisRunStore.RunRecord expired = new AnalysisRunStore.RunRecord(
                previous.runId(), previous.question(), "FAILED", List.of(), List.of(), "", 0, false,
                previous.durationMs(), "CLARIFICATION_TIMEOUT", now, List.of(), List.of());
        store.put(expired);
        pendingClarifications.remove(activeRunId);
        activeRunId = null;
        return expired;
    }

    private void publishExpiredClarification(AnalysisRunStore.RunRecord expired) {
        if (expired == null || events == null) return;
        try {
            events.publish(new ExecutionEvent(UUID.randomUUID(), expired.runId(), 0, Instant.now(clock),
                    ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.FAILURE,
                    ExecutionEventType.FAILED, ExecutionStatus.FAILED, "澄清等待超时，已终止",
                    DisplayPayload.error(ExecutionStage.FAILURE, "CLARIFICATION_TIMEOUT", true,
                            "澄清等待超过时间上限，请重新发起分析")));
        } catch (IllegalStateException alreadyClosed) {
            // Another terminal path won before the lazy expiry was observed.
        }
    }

    private AnalysisRunStore.RunRecord rerunningRecord(UUID runId) {
        var previous = get(runId);
        return new AnalysisRunStore.RunRecord(runId, previous.question(), "RUNNING",
                List.of(), List.of(), "", 0, false, 0, null, Instant.now(clock), List.of(), List.of());
    }

    private static final class RecordBuilder {
        private final UUID runId;
        private final String question;

        private final Clock clock;

        RecordBuilder(UUID runId, String question, Clock clock) {
            this.runId = runId;
            this.question = question;
            this.clock = clock;
        }

        AnalysisRunStore.RunRecord running() {
            return new AnalysisRunStore.RunRecord(runId, question, "RUNNING",
                    List.of(), List.of(), "", 0, false, 0, null, Instant.now(clock), List.of(), List.of());
        }
    }

    private record PendingClarification(List<String> questions,
                                        List<Set<String>> candidates,
                                        AnalyticsModelClient.QuestionUnderstanding understanding,
                                        Instant expiresAt) {
        PendingClarification {
            questions = List.copyOf(questions);
            candidates = candidates.stream().map(Set::copyOf).toList();
        }
    }
}
