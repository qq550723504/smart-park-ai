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

    /** Mirrors the analytics question bound: protects heap and model token budget. */
    private static final int MAX_QUESTION_LENGTH = 500;

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
        if (question.trim().length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("question must not exceed " + MAX_QUESTION_LENGTH + " characters");
        }
        UUID id = UUID.randomUUID();
        CollaborationRun run = store.save(new CollaborationRun(id, question.trim(), CollaborationRun.RunStatus.RUNNING,
                null, List.of(), null, null, Instant.now(clock)));
        publish(id, "Supervisor", ExecutionStage.INITIALIZATION, ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "Expert collaboration started");
        FutureTask<Void> task = new FutureTask<>(() -> {
            execute(id, question.trim());
            return null;
        });
        try {
            runExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            failIfRunning(id, "collaboration run could not be scheduled");
            throw rejected;
        }
        CompletableFuture.delayedExecutor(runTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .execute(() -> timeoutIfRunning(id, task));
        return run;
    }

    public CollaborationRun get(UUID id) { return store.get(id); }

    private void execute(UUID id, String question) {
        try {
            publish(id, "Supervisor", ExecutionStage.PLANNING, ExecutionEventType.NODE_STARTED, ExecutionStatus.RUNNING, "Selecting expert domains");
            SupervisorPlan plan = planner.plan(question);
            if (!savePlanIfRunning(id, question, plan)) return;
            publish(id, "Supervisor", ExecutionStage.PLANNING, ExecutionEventType.EXPERT_HANDOFF, ExecutionStatus.RUNNING, "Selected " + plan.selectedDomains());
            var findings = graph.execute(plan, id);
            // Persist completed expert work immediately: if synthesis later
            // hangs, times out or throws, the failure path keeps the partial
            // findings instead of discarding them with an empty list.
            if (!saveFindingsIfRunning(id, question, plan, findings)) return;
            Synthesis synthesis = synthesizer.synthesize(plan, findings);
            completeIfRunning(id, question, plan, findings, synthesis);
        } catch (Exception ex) { failIfRunning(id, "expert collaboration failed"); }
    }

    private synchronized boolean savePlanIfRunning(UUID id, String question, SupervisorPlan plan) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return false;
        store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.RUNNING,
                plan, List.of(), null, null, Instant.now(clock)));
        return true;
    }

    private synchronized boolean saveFindingsIfRunning(UUID id, String question, SupervisorPlan plan,
            List<com.example.smartpark.collaboration.model.ExpertFinding> findings) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return false;
        store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.RUNNING,
                plan, findings, null, null, Instant.now(clock)));
        return true;
    }

    private synchronized void completeIfRunning(UUID id, String question, SupervisorPlan plan,
            List<com.example.smartpark.collaboration.model.ExpertFinding> findings, Synthesis synthesis) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return;
        store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.COMPLETED, plan, findings, synthesis, null, Instant.now(clock)));
        publish(id, "Supervisor", ExecutionStage.COMPLETION, ExecutionEventType.COMPLETED, ExecutionStatus.SUCCEEDED, "Expert collaboration completed");
    }

    private synchronized boolean failIfRunning(UUID id, String message) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return false;
        store.save(new CollaborationRun(id, current.question(), CollaborationRun.RunStatus.FAILED, current.plan(), current.findings(), null, message, Instant.now(clock)));
        try { publish(id, "Supervisor", ExecutionStage.FAILURE, ExecutionEventType.FAILED, ExecutionStatus.FAILED, message); }
        catch (IllegalStateException ignored) { }
        return true;
    }

    private synchronized void timeoutIfRunning(UUID id, FutureTask<?> task) {
        if (task.isDone()) return;
        // Persist the terminal state before interrupting. A dependency that
        // ignores interruption can then return only into guarded no-op writes.
        if (failIfRunning(id, "collaboration run timed out or failed")) {
            task.cancel(true);
        }
    }

    private void publish(UUID id, String actor, ExecutionStage stage, ExecutionEventType type, ExecutionStatus status, String summary) {
        events.publish(new ExecutionEvent(UUID.randomUUID(), id, 0, Instant.now(clock), ExecutionScenario.EXPERT_COLLABORATION,
                actor, stage, type, status, summary, null));
    }

    @FunctionalInterface public interface Planner { SupervisorPlan plan(String question); }
    @FunctionalInterface public interface Synthesizer { Synthesis synthesize(SupervisorPlan plan, List<com.example.smartpark.collaboration.model.ExpertFinding> findings); }
}
