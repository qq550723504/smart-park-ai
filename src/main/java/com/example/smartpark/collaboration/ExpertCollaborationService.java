package com.example.smartpark.collaboration;

import com.example.smartpark.collaboration.model.CollaborationRun;
import com.example.smartpark.collaboration.model.FindingStatus;
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
        CountDownLatch admitted = new CountDownLatch(1);
        FutureTask<Void> task = new FutureTask<>(() -> {
            admitted.await();
            execute(id, question.trim());
            return null;
        });
        try {
            // Admission is decided by the bounded executor before the run is
            // made externally visible. The gate prevents an accepted worker
            // from observing an ID before its store/event registration commits.
            runExecutor.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.cancel(false);
            throw rejected;
        }
        CollaborationRun run;
        try {
            run = store.save(new CollaborationRun(id, question.trim(), CollaborationRun.RunStatus.RUNNING,
                    null, List.of(), null, null, Instant.now(clock)));
            publish(id, "Supervisor", ExecutionStage.INITIALIZATION, ExecutionEventType.RUN_STARTED,
                    ExecutionStatus.RUNNING, "Expert collaboration started");
        } catch (RuntimeException registrationFailure) {
            task.cancel(true);
            admitted.countDown();
            throw registrationFailure;
        }
        admitted.countDown();
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
            if (!findings.isEmpty() && findings.stream()
                    .allMatch(finding -> finding.status() == FindingStatus.FAILED)) {
                failIfRunningWithSynthesis(id, plan, findings,
                        new Synthesis(FindingStatus.FAILED, "所有专家分支均失败", List.of(), 0,
                                List.of("retry all selected expert branches")));
                return;
            }
            Synthesis synthesis = synthesizer.synthesize(plan, findings);
            completeIfRunning(id, question, plan, findings, synthesis);
        } catch (Exception ex) {
            failIfRunning(id, "expert collaboration failed");
        }
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
        // A synthesis that reports FAILED is a failed collaboration: storing it
        // as COMPLETED would present a rejected outcome as success to clients.
        if (synthesis.status() == com.example.smartpark.collaboration.model.FindingStatus.FAILED) {
            failIfRunningWithSynthesis(id, plan, findings, synthesis);
            return;
        }
        store.save(new CollaborationRun(id, question, CollaborationRun.RunStatus.COMPLETED, plan, findings, synthesis, null, Instant.now(clock)));
        publish(id, "Supervisor", ExecutionStage.COMPLETION, ExecutionEventType.COMPLETED, ExecutionStatus.SUCCEEDED, "Expert collaboration completed");
    }

    private synchronized void failIfRunningWithSynthesis(UUID id, SupervisorPlan plan,
            List<com.example.smartpark.collaboration.model.ExpertFinding> findings, Synthesis synthesis) {
        CollaborationRun current = store.get(id);
        if (current.status() != CollaborationRun.RunStatus.RUNNING) return;
        store.save(new CollaborationRun(id, current.question(), CollaborationRun.RunStatus.FAILED,
                plan, findings, synthesis, synthesis.conclusion(), Instant.now(clock)));
        publish(id, "Supervisor", ExecutionStage.FAILURE, ExecutionEventType.FAILED,
                ExecutionStatus.FAILED, "Expert collaboration failed: " + synthesis.conclusion());
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
