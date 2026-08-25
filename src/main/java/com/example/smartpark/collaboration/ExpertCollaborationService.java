package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.SupervisorPlan;
import com.example.smartpark.collaboration.model.Synthesis;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public final class ExpertCollaborationService {
    private final Planner planner;
    private final ExpertCollaborationGraph graph;
    private final Synthesizer synthesizer;
    private final CollaborationRunStore store;
    private final ExecutionEventPublisher events;
    private final ExecutorService runExecutor;
    private final Duration runTimeout;
    private final Clock clock;

    public ExpertCollaborationService(Planner planner, ExpertCollaborationGraph graph, Synthesizer synthesizer,
            CollaborationRunStore store, ExecutionEventPublisher events, ExecutorService runExecutor,
            Duration runTimeout, Clock clock) {
        this.planner = planner; this.graph = graph; this.synthesizer = synthesizer; this.store = store;
        this.events = events; this.runExecutor = runExecutor; this.runTimeout = runTimeout; this.clock = clock;
    }

    public CollaborationRun start(String question) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question must not be blank");
        UUID id = UUID.randomUUID();
        CollaborationRun run = store.save(new CollaborationRun(id, question.trim(), CollaborationRun.RunStatus.RUNNING,
                null, List.of(), null, null, Instant.now(clock)));
        publish(id, "Supervisor", ExecutionStage.INITIALIZATION, ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "Expert collaboration started");
        CompletableFuture.runAsync(() -> execute(id, question.trim()), runExecutor)
                .orTimeout(runTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .exceptionally(ex -> { failIfRunning(id, "collaboration run timed out or failed"); return null; });
        return run;
    }

    public CollaborationRun get(UUID id) { return store.get(id); }

    private void execute(UUID id, String question) {
        try {
            publish(id, "Supervisor", ExecutionStage.PLANNING, ExecutionEventType.NODE_STARTED, ExecutionStatus.RUNNING, "Selecting expert domains");
            SupervisorPlan plan = planner.plan(question);
            store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.RUNNING, plan, List.of(), null, null, Instant.now(clock)));
            publish(id, "Supervisor", ExecutionStage.PLANNING, ExecutionEventType.EXPERT_HANDOFF, ExecutionStatus.RUNNING, "Selected " + plan.selectedDomains());
            var findings = graph.execute(plan);
            Synthesis synthesis = synthesizer.synthesize(plan, findings);
            store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.COMPLETED, plan, findings, synthesis, null, Instant.now(clock)));
            publish(id, "Supervisor", ExecutionStage.COMPLETION, ExecutionEventType.COMPLETED, ExecutionStatus.SUCCEEDED, "Expert collaboration completed");
        } catch (Exception ex) { failIfRunning(id, "expert collaboration failed"); }
    }

    private synchronized void failIfRunning(UUID id, String message) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return;
        store.save(new CollaborationRun(id, current.question(), CollaborationRun.RunStatus.FAILED, current.plan(), current.findings(), null, message, Instant.now(clock)));
        try { publish(id, "Supervisor", ExecutionStage.FAILURE, ExecutionEventType.FAILED, ExecutionStatus.FAILED, message); }
        catch (IllegalStateException ignored) { }
    }

    private void publish(UUID id, String actor, ExecutionStage stage, ExecutionEventType type, ExecutionStatus status, String summary) {
        events.publish(new ExecutionEvent(UUID.randomUUID(), id, 0, Instant.now(clock), ExecutionScenario.EXPERT_COLLABORATION,
                actor, stage, type, status, summary, null));
    }

    @FunctionalInterface public interface Planner { SupervisorPlan plan(String question); }
    @FunctionalInterface public interface Synthesizer { Synthesis synthesize(SupervisorPlan plan, List<com.example.smartpark.collaboration.model.ExpertFinding> findings); }
}
