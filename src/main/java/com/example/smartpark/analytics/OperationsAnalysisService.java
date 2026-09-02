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
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
    private static final Duration MAX_ADMISSION_WAIT = Duration.ofSeconds(1);

    private final MetricCatalog catalog;
    private final GraphRunner runner;
    private final Executor executor;
    private final Duration timeout;
    private final Duration admissionTimeout;
    private final Duration clarificationTimeout;
    private final Clock clock;
    private final AnalysisRunStore store;
    // ... store is created with the service clock so terminal-record retention
    // is measured on the same timeline as record timestamps.
    private final ExecutionEventPublisher events;
    private final Object lifecycleLock = new Object();
    private UUID activeRunId;
    /** Activity handles are guarded by lifecycleLock so abort can cancel queued work too. */
    private final Map<UUID, FutureTask<OperationsAnalysisGraph.AnalysisRunResult>> activeTasks =
            new java.util.HashMap<>();
    /** Futures used by internal orchestrators; guarded by lifecycleLock. */
    private final Map<UUID, CompletableFuture<AnalysisRunStore.RunRecord>> completionWaiters =
            new java.util.HashMap<>();

    /** Pending ambiguity per run: one candidate metric set per clarification question. */
    private final Map<UUID, PendingClarification> pendingClarifications = new java.util.HashMap<>();
    /** Prevents two callers from reserving the same paused run before admission completes. */
    private final Set<UUID> admittingClarifications = new java.util.HashSet<>();

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
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        this.timeout = timeout;
        this.admissionTimeout = timeout.compareTo(MAX_ADMISSION_WAIT) < 0
                ? timeout : MAX_ADMISSION_WAIT;
        if (clarificationTimeout == null || clarificationTimeout.isZero() || clarificationTimeout.isNegative()) {
            throw new IllegalArgumentException("clarificationTimeout must be positive");
        }
        this.clarificationTimeout = clarificationTimeout;
        this.clock = clock;
        this.events = events;
        this.store = new AnalysisRunStore(clock);
    }

    public AnalysisRunStore.RunRecord get(UUID runId) {
        // Lazy expiry: an abandoned clarification pause must not keep the SSE
        // trace open indefinitely while still offering a doomed clarification.
        synchronized (lifecycleLock) {
            if (runId != null && runId.equals(activeRunId)) {
                publishExpiredClarification(expireAbandonedClarificationLocked(Instant.now(clock)));
            }
        }
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
        }
        publishExpiredClarification(expired);
        try {
            launch(runId, question, null, false,
                    () -> store.put(new RecordBuilder(runId, question, clock).running()),
                    () -> { }, true);
        } catch (RuntimeException rejected) {
            synchronized (lifecycleLock) {
                releaseActiveLocked(runId);
            }
            throw rejected;
        }
        return store.get(runId);
    }

    /**
     * Starts one normal analysis run and completes when it reaches a terminal
     * state or a clarification pause. This is an application-layer seam for
     * bounded orchestrators; it does not add a second execution path.
     */
    public CompletableFuture<AnalysisRunStore.RunRecord> startAndAwait(String question) {
        CompletableFuture<AnalysisRunStore.RunRecord> future = new CompletableFuture<>();
        AnalysisRunStore.RunRecord accepted;
        try {
            accepted = start(question);
        } catch (RuntimeException failure) {
            future.completeExceptionally(failure);
            return future;
        }
        synchronized (lifecycleLock) {
            AnalysisRunStore.RunRecord current = store.get(accepted.runId());
            if (current != null && isAwaitableState(current.status())) {
                future.complete(current);
            } else {
                completionWaiters.put(accepted.runId(), future);
            }
        }
        return future;
    }

    /** Terminates an active run that was created by a preflight or other owner. */
    public AnalysisRunStore.RunRecord abort(UUID runId) {
        if (runId == null) return null;
        synchronized (lifecycleLock) {
            AnalysisRunStore.RunRecord current = store.get(runId);
            if (current == null || !runId.equals(activeRunId)
                    || "COMPLETED".equals(current.status()) || "FAILED".equals(current.status())) {
                return current;
            }
            Instant now = Instant.now(clock);
            Instant createdAt = current.createdAt() == null ? now : current.createdAt();
            AnalysisRunStore.RunRecord aborted = new AnalysisRunStore.RunRecord(
                    current.runId(), current.question(), "FAILED", List.of(), List.of(), "", 0, false,
                    Math.max(0, java.time.Duration.between(createdAt, now).toMillis()),
                    "PREFLIGHT_ABORTED", createdAt, now, List.of(), List.of(), current.timeResolution());
            store.put(aborted);
            completeWaiterLocked(aborted);
            pendingClarifications.remove(runId);
            admittingClarifications.remove(runId);
            FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task = activeTasks.remove(runId);
            releaseActiveLocked(runId);
            publishTerminalLocked(aborted);
            if (task != null) task.cancel(true);
            return aborted;
        }
    }

    public AnalysisRunStore.RunRecord submitClarification(UUID runId, List<MetricSelection> selections) {
        List<MetricSelection> safeSelections = validateSelections(selections);
        AnalysisRunStore.RunRecord current;
        AnalysisRunStore.RunRecord expired;
        AnalyticsModelClient.QuestionUnderstanding pinned = null;
        String pinnedTerms = "";
        PendingClarification pendingForRollback = null;
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
                pendingForRollback = pending;
                validateAgainstPendingClarification(pending, safeSelections);
                if (!admittingClarifications.add(runId)) {
                    throw new IllegalStateException("该运行正在提交澄清，请勿重复提交");
                }
                pinnedTerms = safeSelections.stream()
                        .map(MetricSelection::metric)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("");
                AnalyticsModelClient.RequestedTimeRange requestedTimeRange = pending.understanding() == null
                        ? null : pending.understanding().requestedTimeRange();
                AnalyticsModelClient.RequestedTimeRange serverResolvedTimeRange = pending.understanding() == null
                        ? null : pending.understanding().serverResolvedTimeRange();
                List<String> requestedDimensions = pending.understanding() == null
                        ? List.of() : pending.understanding().requestedDimensions();
                Map<String, String> requestedFilters = pending.understanding() == null
                        ? Map.of() : pending.understanding().requestedFilters();
                List<String> requestedTimeMentions = pending.understanding() == null
                        ? List.of() : pending.understanding().requestedTimeMentions();
                Instant serverReferenceInstant = pending.understanding() == null
                        ? null : pending.understanding().serverReferenceInstant();
                LinkedHashSet<String> metricTerms = new LinkedHashSet<>();
                if (pending.understanding() != null) {
                    for (String term : pending.understanding().metricTerms()) {
                        var resolution = catalog.resolve(term);
                        if (resolution instanceof com.example.smartpark.analytics.catalog.MetricResolution.Resolved resolved) {
                            metricTerms.add(resolved.metric().name());
                        }
                    }
                }
                safeSelections.stream().map(MetricSelection::metric).forEach(metricTerms::add);
                String normalizedQuestion = pending.understanding() == null
                        ? current.question() : pending.understanding().normalizedQuestion();
                pinned = new AnalyticsModelClient.QuestionUnderstanding(
                        normalizedQuestion, List.copyOf(metricTerms),
                        List.of(), requestedTimeRange, requestedDimensions, requestedFilters,
                        requestedTimeMentions, serverResolvedTimeRange, serverReferenceInstant);
                // Use the snapshot already checked above. Calling the public
                // getter here would perform lazy clarification expiry again;
                // a clock crossing the deadline during resume could therefore
                // replace the validated RUNNING transition with a timeout.
            }
        }
        publishExpiredClarification(expired);
        if (pinned == null) {
            throw new IllegalStateException("该运行的澄清等待已超时");
        }
        AnalysisRunStore.RunRecord rerunning = rerunningRecord(current);
        PendingClarification rollbackPending = pendingForRollback;
        launch(runId, current.question() + "（已明确指标: " + pinnedTerms + "）", pinned, true,
                () -> {
                    synchronized (lifecycleLock) {
                        store.put(rerunning);
                        pendingClarifications.remove(runId);
                        admittingClarifications.remove(runId);
                    }
                },
                () -> {
                    synchronized (lifecycleLock) {
                        store.put(current);
                        pendingClarifications.put(runId, rollbackPending);
                        admittingClarifications.remove(runId);
                    }
                }, false);
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
                        AnalyticsModelClient.QuestionUnderstanding pinned, boolean resumed,
                        Runnable registration, Runnable rollback, boolean removeRunOnReject) {
        if (executor instanceof ThreadPoolExecutor) {
            launchWithAdmissionGate(runId, question, pinned, resumed, registration, rollback);
            return;
        }
        launchWithCleanupFallback(runId, question, pinned, resumed, registration, rollback, removeRunOnReject);
    }

    /**
     * Production analyticsExecutor is a bounded ThreadPoolExecutor. Its
     * admission task waits behind the executor boundary, so rejection happens
     * before a run record or trace is created.
     */
    private void launchWithAdmissionGate(UUID runId, String question,
                                         AnalyticsModelClient.QuestionUnderstanding pinned,
                                         boolean resumed, Runnable registration, Runnable rollback) {
        long startedAt = System.currentTimeMillis();
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch admissionDecision = new CountDownLatch(1);
        AtomicBoolean registeredSuccessfully = new AtomicBoolean();
        AtomicBoolean admitted = new AtomicBoolean();
        AtomicReference<RuntimeException> registrationFailure = new AtomicReference<>();
        FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task = new FutureTask<>(
                () -> {
                    try {
                        // Admission is decided by executor.execute before any
                        // run record or trace is published. The latch also
                        // keeps direct/synchronous test executors compatible.
                        registerTrace(runId, resumed);
                        registration.run();
                        registeredSuccessfully.set(true);
                    } catch (RuntimeException failure) {
                        registrationFailure.set(failure);
                    } finally {
                        registered.countDown();
                    }
                    RuntimeException failure = registrationFailure.get();
                    if (failure != null) throw failure;
                    try {
                        // Do not start the graph until the submitting thread has
                        // accepted this worker. This closes the race where an
                        // admission timeout rolls back state while a late
                        // worker would otherwise continue into the graph.
                        admissionDecision.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    if (!admitted.get()) return null;
                    return runner.execute(runId, question, pinned);
                }) {
            @Override
            protected void done() {
                if (registeredSuccessfully.get() && admitted.get()) {
                    completeTask(runId, question, startedAt, this);
                }
            }
        };
        trackTask(runId, task);
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            untrackTask(runId, task);
            task.cancel(false);
            rollback.run();
            throw rejected;
        }
        try {
            if (!registered.await(admissionTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                admitted.set(false);
                admissionDecision.countDown();
                task.cancel(false);
                rollback.run();
                closeRejectedTrace(runId);
                throw new java.util.concurrent.RejectedExecutionException(
                        "analysis admission timed out before a worker became available");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            admitted.set(false);
            admissionDecision.countDown();
            task.cancel(true);
            rollback.run();
            closeRejectedTrace(runId);
            throw new IllegalStateException("analysis admission interrupted", interrupted);
        }
        RuntimeException failure = registrationFailure.get();
        if (failure != null) {
            admitted.set(false);
            admissionDecision.countDown();
            rollback.run();
            closeRejectedTrace(runId);
            throw failure;
        }
        admitted.set(true);
        admissionDecision.countDown();
        java.util.concurrent.CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> task.cancel(true));
    }

    /**
     * Executor is intentionally injected as the small test seam and may be a
     * synchronous or non-running implementation. Preserve that contract while
     * removing every rejected run/trace immediately, so no unreachable state
     * survives even outside the production executor type.
     */
    private void launchWithCleanupFallback(UUID runId, String question,
                                           AnalyticsModelClient.QuestionUnderstanding pinned,
                                           boolean resumed, Runnable registration,
                                           Runnable rollback, boolean removeRunOnReject) {
        long startedAt = System.currentTimeMillis();
        registerTrace(runId, resumed);
        registration.run();
        AtomicBoolean taskStarted = new AtomicBoolean();
        FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task = new FutureTask<>(
                () -> {
                    taskStarted.set(true);
                    return runner.execute(runId, question, pinned);
                }) {
            @Override
            protected void done() {
                if (taskStarted.get()) completeTask(runId, question, startedAt, this);
            }
        };
        trackTask(runId, task);
        try {
            executor.execute(task);
        } catch (RuntimeException rejected) {
            untrackTask(runId, task);
            task.cancel(false);
            rollback.run();
            closeRejectedTrace(runId);
            if (removeRunOnReject) store.remove(runId);
            throw rejected;
        }
        java.util.concurrent.CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> task.cancel(true));
    }

    private void closeRejectedTrace(UUID runId) {
        if (events == null || events.history(runId).isEmpty()) return;
        try {
            events.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(clock),
                    ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.FAILURE,
                    ExecutionEventType.FAILED, ExecutionStatus.FAILED, "分析未获准执行，未创建运行记录",
                    DisplayPayload.error(ExecutionStage.FAILURE, "ANALYSIS_REJECTED", true,
                            "系统繁忙，请稍后重试")));
            events.remove(runId);
        } catch (IllegalStateException alreadyClosed) {
            // Cleanup is best effort after admission rejection.
        }
    }

    private void completeTask(UUID runId, String question, long startedAt,
                              FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task) {
        long durationMs = System.currentTimeMillis() - startedAt;
        if (task.isCancelled()) {
            synchronized (lifecycleLock) {
                AnalysisRunStore.RunRecord current = store.get(runId);
                if (current == null || !"RUNNING".equals(current.status())) return;
            }
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

    private void trackTask(UUID runId, FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task) {
        synchronized (lifecycleLock) {
            activeTasks.put(runId, task);
        }
    }

    private void untrackTask(UUID runId, FutureTask<OperationsAnalysisGraph.AnalysisRunResult> task) {
        synchronized (lifecycleLock) {
            activeTasks.remove(runId, task);
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
            // createdAt always carries the original start(); updatedAt moves on
            // every transition so clients can tell both apart.
            Instant createdAt = existing != null ? existing.createdAt() : now;
            AnalysisRunStore.RunRecord record = switch (outcome.outcome()) {
                case COMPLETED -> new AnalysisRunStore.RunRecord(runId, question, "COMPLETED",
                        List.of(), List.of(), outcome.summary() == null ? "" : outcome.summary(),
                        outcome.result() == null ? 0 : outcome.result().rowCount(),
                        outcome.result() != null && outcome.result().truncated(),
                        durationMs, null, createdAt, now,
                        outcome.result() == null ? List.of() : outcome.result().columnNames(),
                        outcome.result() == null ? List.of() : outcome.result().rows(),
                        outcome.timeResolution());
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
                            createdAt, now, List.of(), List.of(), outcome.timeResolution());
                }
                case FAILED -> {
                    pendingClarifications.remove(runId);
                    yield new AnalysisRunStore.RunRecord(runId, question, "FAILED",
                            List.of(), List.of(), "", 0, false, durationMs,
                            outcome.failureStage() == null ? "UNKNOWN" : outcome.failureStage(),
                            createdAt, now, List.of(), List.of());
                }
            };
            store.put(record);
            completeWaiterLocked(record);
            if (!"NEEDS_CLARIFICATION".equals(record.status())) {
                releaseActiveLocked(runId);
                // Terminal events are published strictly after persistence so
                // a racing timeout can never pair a completed trace with a
                // failed status record (or vice versa).
                publishTerminalLocked(record);
            }
            return record;
        }
    }

    private AnalysisRunStore.RunRecord persistFailure(UUID runId, String question,
                                                      String stage, long durationMs) {
        synchronized (lifecycleLock) {
            var previous = store.get(runId);
            // Terminal states are final: a raced timeout never overwrites an
            // already-persisted outcome.
            if (previous != null && ("FAILED".equals(previous.status()) || "COMPLETED".equals(previous.status()))) {
                return previous;
            }
            Instant createdAt = previous != null ? previous.createdAt() : Instant.now(clock);
            var record = new AnalysisRunStore.RunRecord(runId, question, "FAILED", List.of(), List.of(),
                    "", 0, false, durationMs, stage, createdAt, Instant.now(clock), List.of(), List.of());
            store.put(record);
            completeWaiterLocked(record);
            pendingClarifications.remove(runId);
            releaseActiveLocked(runId);
            publishTerminalLocked(record);
            return record;
        }
    }

    /**
     * Publishes terminal events only after persistence has won the lifecycle
     * transition, so the trace and the stored status can never disagree.
     */
    private void publishTerminalLocked(AnalysisRunStore.RunRecord record) {
        if (events == null) return;
        try {
            if ("COMPLETED".equals(record.status())) {
                events.publish(new ExecutionEvent(UUID.randomUUID(), record.runId(), 0, Instant.now(clock),
                        ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.COMPLETION,
                        ExecutionEventType.COMPLETED, ExecutionStatus.SUCCEEDED, "运营分析完成", null));
            } else if ("FAILED".equals(record.status())) {
                String stage = record.failureStage() == null ? "ANALYSIS_ABORTED" : record.failureStage();
                events.publish(new ExecutionEvent(UUID.randomUUID(), record.runId(), 0, Instant.now(clock),
                        ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.FAILURE,
                        ExecutionEventType.FAILED, ExecutionStatus.FAILED, "分析执行失败，已终止",
                        DisplayPayload.error(ExecutionStage.FAILURE, stage, true,
                                "分析在执行过程中被终止，请调整问题后重试")));
            }
        } catch (IllegalStateException alreadyClosed) {
            // The trace was closed by another terminal path; nothing more to do.
        }
    }

    private void completeWaiterLocked(AnalysisRunStore.RunRecord record) {
        if (!isAwaitableState(record.status())) return;
        CompletableFuture<AnalysisRunStore.RunRecord> waiter = completionWaiters.remove(record.runId());
        if (waiter != null) waiter.complete(record);
    }

    private static boolean isAwaitableState(String status) {
        return "COMPLETED".equals(status)
                || "FAILED".equals(status)
                || "NEEDS_CLARIFICATION".equals(status);
    }

    /** Registers RUN_STARTED/RESUMED synchronously so the trace resolves before polling starts. */
    private void registerTrace(UUID runId, boolean resumed) {
        if (events == null) return;
        try {
            events.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(clock),
                    ExecutionScenario.OPERATIONS_ANALYSIS, "analytics", ExecutionStage.INITIALIZATION,
                    resumed ? ExecutionEventType.RESUMED : ExecutionEventType.RUN_STARTED,
                    ExecutionStatus.RUNNING,
                    resumed ? "澄清已提交，继续运营分析" : "运营分析已启动", null));
        } catch (IllegalStateException alreadyRegistered) {
            // Trace already open for this run; nothing more to do.
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
                previous.durationMs(), "CLARIFICATION_TIMEOUT", previous.createdAt(), now, List.of(), List.of(),
                previous.timeResolution());
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

    private AnalysisRunStore.RunRecord rerunningRecord(AnalysisRunStore.RunRecord previous) {
        // The original creation time survives the resume; only updatedAt moves.
        return new AnalysisRunStore.RunRecord(previous.runId(), previous.question(), "RUNNING",
                List.of(), List.of(), "", 0, false, 0, null,
                previous.createdAt(), Instant.now(clock), List.of(), List.of(), previous.timeResolution());
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
            Instant now = Instant.now(clock);
            return new AnalysisRunStore.RunRecord(runId, question, "RUNNING",
                    List.of(), List.of(), "", 0, false, 0, null, now, now, List.of(), List.of());
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
