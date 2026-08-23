package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.example.smartpark.model.WorkflowStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public interface WorkflowExecutionStore {

    Optional<WorkflowSnapshot> get(String workflowId);

    Optional<WorkflowSnapshot> findRunningByAlertId(String alertId);

    Optional<WorkflowSnapshot> findByAlertId(String alertId);

    Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState);

    Optional<Execution> execution(String workflowId);

    static WorkflowExecutionStore inMemory() {
        return new InMemoryWorkflowExecutionStore();
    }

    final class Execution {
        private final String workflowId;
        private final String alertId;
        private final String graphThreadId;
        private final CompiledGraph compiledGraph;
        private final AlertWorkflowState initialState;
        private volatile InterruptionMetadata interruption;
        private volatile Throwable failureCause;

        Execution(
                String workflowId,
                String alertId,
                String graphThreadId,
                CompiledGraph compiledGraph,
                AlertWorkflowState initialState) {
            this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
            this.alertId = Objects.requireNonNull(alertId, "alertId");
            this.graphThreadId = Objects.requireNonNull(graphThreadId, "graphThreadId");
            this.compiledGraph = Objects.requireNonNull(compiledGraph, "compiledGraph");
            this.initialState = Objects.requireNonNull(initialState, "initialState");
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
                .filter(execution -> execution.alertId().equals(alertId))
                .findFirst()
                .map(Execution::snapshot);
    }

    @Override
    public synchronized Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            AlertWorkflowState initialState) {
        Optional<WorkflowSnapshot> existingSnapshot = findByAlertId(alertId);
        if (existingSnapshot.isPresent()) {
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
        Execution execution = new Execution(workflowId, alertId, graphThreadId, compiledGraph, initialState);
        executions.put(workflowId, execution);
        return execution;
    }

    @Override
    public Optional<Execution> execution(String workflowId) {
        return Optional.ofNullable(executions.get(workflowId));
    }

    private static boolean isRunning(WorkflowStatus status) {
        return status == WorkflowStatus.RUNNING || status == WorkflowStatus.WAITING_APPROVAL;
    }
}
