package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.example.smartpark.model.WorkflowStatus;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public interface WorkflowExecutionStore {

    WorkflowSnapshot save(WorkflowSnapshot snapshot);

    Optional<WorkflowSnapshot> get(String workflowId);

    Optional<WorkflowSnapshot> findRunningByAlertId(String alertId);

    Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            WorkflowSnapshot initialSnapshot);

    Optional<Execution> execution(String workflowId);

    static WorkflowExecutionStore inMemory() {
        return new InMemoryWorkflowExecutionStore();
    }

    final class Execution {
        private final String workflowId;
        private final String alertId;
        private final String graphThreadId;
        private final CompiledGraph compiledGraph;
        private final AtomicLong eventSequence = new AtomicLong();
        private volatile InterruptionMetadata interruption;

        Execution(String workflowId, String alertId, String graphThreadId, CompiledGraph compiledGraph) {
            this.workflowId = Objects.requireNonNull(workflowId, "workflowId");
            this.alertId = Objects.requireNonNull(alertId, "alertId");
            this.graphThreadId = Objects.requireNonNull(graphThreadId, "graphThreadId");
            this.compiledGraph = Objects.requireNonNull(compiledGraph, "compiledGraph");
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

        public long nextEventSequence() {
            return eventSequence.incrementAndGet();
        }

        public long eventSequence() {
            return eventSequence.get();
        }

        public Optional<InterruptionMetadata> interruption() {
            return Optional.ofNullable(interruption);
        }

        public void interruption(InterruptionMetadata interruption) {
            this.interruption = Objects.requireNonNull(interruption, "interruption");
        }
    }
}

final class InMemoryWorkflowExecutionStore implements WorkflowExecutionStore {

    private final Map<String, WorkflowSnapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, Execution> executions = new ConcurrentHashMap<>();

    @Override
    public WorkflowSnapshot save(WorkflowSnapshot snapshot) {
        snapshots.put(snapshot.workflowId(), snapshot);
        return snapshot;
    }

    @Override
    public Optional<WorkflowSnapshot> get(String workflowId) {
        return Optional.ofNullable(snapshots.get(workflowId));
    }

    @Override
    public Optional<WorkflowSnapshot> findRunningByAlertId(String alertId) {
        return snapshots.values().stream()
                .filter(snapshot -> snapshot.alertId().equals(alertId))
                .filter(snapshot -> isRunning(snapshot.status()))
                .findFirst();
    }

    @Override
    public synchronized Execution register(
            String workflowId,
            String alertId,
            String graphThreadId,
            CompiledGraph compiledGraph,
            WorkflowSnapshot initialSnapshot) {
        Optional<WorkflowSnapshot> running = findRunningByAlertId(alertId);
        if (running.isPresent()) {
            Execution existing = executions.get(running.get().workflowId());
            if (existing == null) {
                throw new IllegalStateException(
                        "Running snapshot has no Graph execution: " + running.get().workflowId());
            }
            return existing;
        }
        if (executions.containsKey(workflowId)) {
            throw new IllegalStateException("Workflow already exists: " + workflowId);
        }
        Execution execution = new Execution(workflowId, alertId, graphThreadId, compiledGraph);
        executions.put(workflowId, execution);
        snapshots.put(workflowId, initialSnapshot);
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
