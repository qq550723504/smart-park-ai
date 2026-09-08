package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.example.smartpark.model.common.WorkflowStatus;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

public interface WorkflowExecutionStore {

    Optional<WorkflowSnapshot> get(String workflowId);

    Optional<WorkflowSnapshot> findRunningByAlertId(String alertId);

    Optional<WorkflowSnapshot> findByAlertId(String alertId);

    List<WorkflowSnapshot> snapshots();

    Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState);

    Execution registerExclusive(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState);

    Optional<Execution> execution(String workflowId);

    /**
     * Marks an execution terminal and evicts the oldest terminal exclusive executions above the
     * configured retention bound. Cleanup runs before removal so a failed cleanup remains retryable.
     */
    void markTerminalAndCompact(Execution execution, Consumer<Execution> cleanup);

    static WorkflowExecutionStore inMemory() {
        return inMemory(200);
    }

    static WorkflowExecutionStore inMemory(int maxRetainedExclusiveExecutions) {
        return new InMemoryWorkflowExecutionStore(maxRetainedExclusiveExecutions);
    }

    final class Execution {
        private final String workflowId;
        private final String alertId;
        private final String graphThreadId;
        private final CompiledGraph compiledGraph;
        private final AlertWorkflowState initialState;
        private final boolean reusableByAlert;
        private volatile InterruptionMetadata interruption;
        private volatile Throwable failureCause;
        private volatile long terminalSequence;
        private final Map<UUID, Instant> pendingApprovalAttempts = new ConcurrentHashMap<>();

        Execution(
                String workflowId,
                String alertId,
                String graphThreadId,
                CompiledGraph compiledGraph,
                AlertWorkflowState initialState,
                boolean reusableByAlert) {
            this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
            this.alertId = Objects.requireNonNull(alertId, "alertId");
            this.graphThreadId = Objects.requireNonNull(graphThreadId, "graphThreadId");
            this.compiledGraph = Objects.requireNonNull(compiledGraph, "compiledGraph");
            this.initialState = Objects.requireNonNull(initialState, "initialState");
            this.reusableByAlert = reusableByAlert;
        }

        public String workflowId() {
            return workflowId;
        }

        public String alertId() {
            return alertId;
        }

        public String graphThreadId() {
            return graphThreadId;
        }

        public CompiledGraph compiledGraph() {
            return compiledGraph;
        }

        boolean reusableByAlert() {
            return reusableByAlert;
        }

        boolean terminal() {
            return terminalSequence > 0;
        }

        long terminalSequence() {
            return terminalSequence;
        }

        void markTerminal(long terminalSequence) {
            if (this.terminalSequence == 0) {
                this.terminalSequence = terminalSequence;
            }
        }

        public Optional<InterruptionMetadata> interruption() {
            return Optional.ofNullable(interruption);
        }

        public void interruption(InterruptionMetadata interruption) {
            this.interruption = Objects.requireNonNull(interruption, "interruption");
        }

        public Optional<Throwable> failureCause() {
            return Optional.ofNullable(failureCause);
        }

        public void failureCause(Throwable failureCause) {
            this.failureCause = Objects.requireNonNull(failureCause, "failureCause");
        }

        void beginApprovalAttempt(UUID attemptId, Instant receivedAt) {
            pendingApprovalAttempts.put(attemptId, receivedAt);
        }

        void endApprovalAttempt(UUID attemptId) {
            pendingApprovalAttempts.remove(attemptId);
        }

        boolean hasApprovalAttemptBefore(Instant deadline) {
            return pendingApprovalAttempts.values().stream().anyMatch(receivedAt -> receivedAt.isBefore(deadline));
        }

        AlertWorkflowState currentState() {
            RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
            return compiledGraph.stateOf(config)
                    .map(snapshot -> AlertWorkflowState.from(snapshot.state()))
                    .orElse(initialState);
        }

        WorkflowSnapshot snapshot() {
            return WorkflowSnapshot.from(currentState());
        }
    }
}

final class InMemoryWorkflowExecutionStore implements WorkflowExecutionStore {

    private final Map<String, Execution> executions = new ConcurrentHashMap<>();
    private final int maxRetainedExclusiveExecutions;
    private long terminalSequence;

    InMemoryWorkflowExecutionStore(int maxRetainedExclusiveExecutions) {
        if (maxRetainedExclusiveExecutions < 1) {
            throw new IllegalArgumentException("maxRetainedExclusiveExecutions must be positive");
        }
        this.maxRetainedExclusiveExecutions = maxRetainedExclusiveExecutions;
    }

    @Override
    public Optional<WorkflowSnapshot> get(String workflowId) {
        return Optional.ofNullable(executions.get(workflowId)).map(Execution::snapshot);
    }

    @Override
    public Optional<WorkflowSnapshot> findRunningByAlertId(String alertId) {
        return findByAlertId(alertId)
                .filter(snapshot -> isRunning(snapshot.status()));
    }

    @Override
    public Optional<WorkflowSnapshot> findByAlertId(String alertId) {
        return executions.values().stream()
                .filter(execution -> execution.reusableByAlert() && execution.alertId().equals(alertId))
                // Prefer a reusable execution over an old failed attempt. This keeps
                // retries idempotent once a new attempt is running or completed.
                .sorted(Comparator.comparing(execution -> isRetryable(execution.snapshot().status())))
                .findFirst()
                .map(Execution::snapshot);
    }

    @Override
    public List<WorkflowSnapshot> snapshots() {
        return executions.values().stream().map(Execution::snapshot).toList();
    }

    @Override
    public synchronized Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState) {
        Optional<WorkflowSnapshot> existingSnapshot = findByAlertId(alertId);
        if (existingSnapshot.filter(snapshot -> !isRetryable(snapshot.status())).isPresent()) {
            Execution existing = executions.get(existingSnapshot.get().workflowId());
            if (existing == null) {
                throw new IllegalStateException(
                        "Alert snapshot has no Graph execution: " + existingSnapshot.get().workflowId());
            }
            return existing;
        }
        if (executions.containsKey(workflowId)) {
            throw new IllegalStateException("Workflow already exists: " + workflowId);
        }
        Execution execution = new Execution(
                workflowId, alertId, graphThreadId, compiledGraph, initialState, true);
        executions.put(workflowId, execution);
        return execution;
    }

    @Override
    public synchronized Execution registerExclusive(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState) {
        if (executions.containsKey(workflowId)) {
            throw new IllegalStateException("Workflow already exists: " + workflowId);
        }
        Execution execution = new Execution(
                workflowId, alertId, graphThreadId, compiledGraph, initialState, false);
        executions.put(workflowId, execution);
        return execution;
    }

    @Override
    public Optional<Execution> execution(String workflowId) {
        return Optional.ofNullable(executions.get(workflowId));
    }

    @Override
    public synchronized void markTerminalAndCompact(
            Execution execution,
            Consumer<Execution> cleanup) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(cleanup, "cleanup");
        if (executions.get(execution.workflowId()) != execution) {
            throw new IllegalStateException("Workflow execution is not registered: " + execution.workflowId());
        }
        execution.markTerminal(++terminalSequence);
        List<Execution> terminalExclusive = executions.values().stream()
                .filter(candidate -> !candidate.reusableByAlert() && candidate.terminal())
                .sorted(Comparator.comparingLong(Execution::terminalSequence))
                .toList();
        int excess = terminalExclusive.size() - maxRetainedExclusiveExecutions;
        for (int index = 0; index < excess; index++) {
            Execution evicted = terminalExclusive.get(index);
            cleanup.accept(evicted);
            executions.remove(evicted.workflowId(), evicted);
        }
    }

    private static boolean isRunning(WorkflowStatus status) {
        return status == WorkflowStatus.RUNNING || status == WorkflowStatus.WAITING_APPROVAL;
    }

    private static boolean isRetryable(WorkflowStatus status) {
        return status == WorkflowStatus.FAILED || status == WorkflowStatus.WORK_ORDER_FAILED;
    }
}
