package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.ExpertDomain;
import com.example.smartpark.collaboration.model.ExpertFinding;
import com.example.smartpark.collaboration.model.FindingStatus;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.DisplayPayload;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/** Dynamic fan-out runtime. Only selected experts receive work and selected branches execute concurrently. */
public final class ExpertCollaborationGraph {
    private static final Logger log = LoggerFactory.getLogger(ExpertCollaborationGraph.class);
    private final Map<ExpertDomain, Expert> experts;
    private final Executor executor;
    private final Duration expertTimeout;
    private final ExecutionEventPublisher events;
    private final Clock clock;

    public ExpertCollaborationGraph(Map<ExpertDomain, Expert> experts, Executor executor) {
        this(experts, executor, Duration.ofSeconds(15));
    }

    public ExpertCollaborationGraph(Map<ExpertDomain, Expert> experts, Executor executor, Duration expertTimeout) {
        this(experts, executor, expertTimeout, null);
    }

    public ExpertCollaborationGraph(Map<ExpertDomain, Expert> experts, Executor executor,
                                    Duration expertTimeout, ExecutionEventPublisher events) {
        EnumMap<ExpertDomain, Expert> copy = new EnumMap<>(ExpertDomain.class);
        copy.putAll(Objects.requireNonNull(experts, "experts"));
        if (!copy.keySet().containsAll(java.util.Set.of(ExpertDomain.values()))) {
            throw new IllegalArgumentException("all expert implementations are required");
        }
        this.experts = Map.copyOf(copy);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.expertTimeout = Objects.requireNonNull(expertTimeout, "expertTimeout");
        if (expertTimeout.isZero() || expertTimeout.isNegative()) {
            throw new IllegalArgumentException("expertTimeout must be positive");
        }
        this.events = events;
        this.clock = Clock.systemUTC();
    }

    public List<ExpertFinding> execute(SupervisorPlan plan) {
        return execute(plan, null);
    }

    /** Runs the fan-out for one collaboration run; runId enables live branch handoff tracing. */
    public List<ExpertFinding> execute(SupervisorPlan plan, UUID runId) {
        // Every branch shares the same submission deadline so a hung branch is
        // canceled after its own configured lifetime — never after the sum of
        // the timeouts of the branches awaited before it.
        java.time.Instant submissionDeadline = Instant.now(clock).plus(expertTimeout);
        List<BranchTask> tasks = plan.selectedDomains().stream()
                .map(domain -> {
                    publishBranchHandoff(runId, domain, "supervisor -> " + domain.name().toLowerCase(), null);
                    Future<ExpertFinding> task = new FutureTask<>(
                            () -> invoke(domain, plan.assignments().get(domain), runId));
                    try {
                        executor.execute((Runnable) task);
                    } catch (RejectedExecutionException rejected) {
                        // Admission failure is local to this expert branch. Keep
                        // the other selected experts useful and expose the
                        // rejected branch as an explicit failed finding.
                        task = CompletableFuture.completedFuture(
                                failed(domain, "expert queue is full"));
                    }
                    return new BranchTask(domain, task);
                })
                .toList();
        List<ExpertFinding> findings = tasks.stream().map(task -> await(task, submissionDeadline))
                .sorted(Comparator.comparing(ExpertFinding::domain)).toList();
        for (ExpertFinding finding : findings) {
            publishBranchHandoff(runId, finding.domain(),
                    finding.domain().name().toLowerCase() + " -> supervisor", finding.status().name());
        }
        return findings;
    }

    private ExpertFinding await(BranchTask branch, java.time.Instant submissionDeadline) {
        long remainingMs = Math.max(1, Duration.between(Instant.now(clock), submissionDeadline).toMillis());
        try {
            return branch.task().get(remainingMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            branch.task().cancel(true);
            return failed(branch.domain(), "expert timed out or failed");
        } catch (InterruptedException interrupted) {
            branch.task().cancel(true);
            Thread.currentThread().interrupt();
            return failed(branch.domain(), "expert execution interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException failure) {
            return failed(branch.domain(), "expert timed out or failed");
        }
    }

    /** Typed branch-level handoff trace so the panel can render per-expert progress. */
    private void publishBranchHandoff(UUID runId, ExpertDomain domain, String direction, String findingStatus) {
        if (events == null || runId == null) {
            return;
        }
        try {
            events.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, Instant.now(clock),
                    ExecutionScenario.EXPERT_COLLABORATION, "supervisor",
                    ExecutionStage.PLANNING, ExecutionEventType.EXPERT_HANDOFF, ExecutionStatus.RUNNING,
                    direction, new DisplayPayload.ExpertHandoffPayload(domain.name(), direction,
                            findingStatus == null ? "RUNNING" : findingStatus)));
        } catch (IllegalStateException closedRun) {
            // The run already terminated; late branch events are dropped.
        }
    }

    private ExpertFinding failed(ExpertDomain domain, String message) {
        return new ExpertFinding(domain, FindingStatus.FAILED, message,
                List.of(), 0, List.of("retry " + domain.name().toLowerCase() + " expert"));
    }

    private ExpertFinding invoke(ExpertDomain domain, String assignment, UUID runId) {
        try { return experts.get(domain).analyze(assignment, runId); }
        catch (RuntimeException ex) {
            log.warn("Expert branch failed: runId={}, domain={}, exceptionType={}",
                    runId, domain, ex.getClass().getName());
            return failed(domain, "failed to analyze expert assignment");
        }
    }

    private record BranchTask(ExpertDomain domain, Future<ExpertFinding> task) { }

    @FunctionalInterface
    public interface Expert {
        ExpertFinding analyze(String assignment);

        /** Run-scoped overload used by audited tools; the old seam remains compatible for tests/callers. */
        default ExpertFinding analyze(String assignment, UUID runId) {
            return analyze(assignment);
        }
    }
}
