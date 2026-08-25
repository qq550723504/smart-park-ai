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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Dynamic fan-out runtime. Only selected experts receive work and selected branches execute concurrently. */
public final class ExpertCollaborationGraph {
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
        List<CompletableFuture<ExpertFinding>> futures = plan.selectedDomains().stream()
                .map(domain -> {
                    publishBranchHandoff(runId, domain, "supervisor -> " + domain.name().toLowerCase(), null);
                    return CompletableFuture.supplyAsync(() -> invoke(domain, plan.assignments().get(domain)), executor)
                            .orTimeout(expertTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                            .exceptionally(error -> failed(domain, "expert timed out or failed"));
                })
                .toList();
        List<ExpertFinding> findings = futures.stream().map(CompletableFuture::join)
                .sorted(Comparator.comparing(ExpertFinding::domain)).toList();
        for (ExpertFinding finding : findings) {
            publishBranchHandoff(runId, finding.domain(),
                    finding.domain().name().toLowerCase() + " -> supervisor", finding.status().name());
        }
        return findings;
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

    private ExpertFinding invoke(ExpertDomain domain, String assignment) {
        try { return experts.get(domain).analyze(assignment); }
        catch (RuntimeException ex) {
            return failed(domain, "failed to analyze expert assignment");
        }
    }

    @FunctionalInterface
    public interface Expert {
        ExpertFinding analyze(String assignment);
    }
}
