package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.serializer.StateSerializer;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertModelFailureStage;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

public final class AlertWorkflow {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertWorkflow.class);
    private static final double CONFIDENCE_THRESHOLD = 0.75;
    private static final String APPROVAL_DEADLINE_EXPIRED = "Approval deadline expired";
    private static final String CANCELLED_BY_ORCHESTRATION = "Workflow cancelled by orchestration";

    private final WorkflowExecutionStore executionStore;
    private final WorkflowEventPublisher eventPublisher;
    private final AlertWorkflowNodes nodes;
    private final MemorySaver checkpointSaver;
    private final CompiledGraph compiledGraph;
    private final Supplier<String> workflowIds;
    private final Clock clock;

    public AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher) {
        this(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                workOrderPort,
                knowledgePort,
                executionStore,
                eventPublisher,
                Clock.systemUTC(),
                () -> UUID.randomUUID().toString(),
                null,
                null);
    }

    public AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            EnergyPort energyPort,
            SecurityPort securityPort) {
        this(triageAgent, diagnosisAgent, devicePort, alertPort, workOrderPort, knowledgePort,
                executionStore, eventPublisher, Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                energyPort, securityPort);
    }

    public AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            EnergyPort energyPort,
            SecurityPort securityPort,
            Consumer<AlertModelFailureStage> failureObserver) {
        this(triageAgent, diagnosisAgent, devicePort, alertPort, workOrderPort, knowledgePort,
                executionStore, eventPublisher, Clock.systemUTC(), () -> UUID.randomUUID().toString(),
                energyPort, securityPort, Objects.requireNonNull(failureObserver, "failureObserver"));
    }

    AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            Clock clock,
            Supplier<String> workflowIds) {
        this(triageAgent, diagnosisAgent, devicePort, alertPort, workOrderPort, knowledgePort,
                executionStore, eventPublisher, clock, workflowIds, null, null);
    }

    AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            Clock clock,
            Supplier<String> workflowIds,
            EnergyPort energyPort,
            SecurityPort securityPort) {
        this(triageAgent, diagnosisAgent, devicePort, alertPort, workOrderPort, knowledgePort,
                executionStore, eventPublisher, clock, workflowIds, energyPort, securityPort, null);
    }

    AlertWorkflow(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowExecutionStore executionStore,
            WorkflowEventPublisher eventPublisher,
            Clock clock,
            Supplier<String> workflowIds,
            EnergyPort energyPort,
            SecurityPort securityPort,
            Consumer<AlertModelFailureStage> failureObserver) {
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.workflowIds = Objects.requireNonNull(workflowIds, "workflowIds");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.nodes = new AlertWorkflowNodes(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                workOrderPort,
                knowledgePort,
                eventPublisher,
                clock,
                CONFIDENCE_THRESHOLD,
                energyPort,
                securityPort,
                failureObserver);
        this.checkpointSaver = MemorySaver.builder().build();
        this.compiledGraph = compileGraph();
    }

    public WorkflowSnapshot start(String alertId) {
        return start(alertId, null);
    }

    public WorkflowSnapshot start(String alertId, Instant approvalExpiresAt) {
        return start(alertId, approvalExpiresAt, false);
    }

    public WorkflowSnapshot startExclusive(String alertId, Instant approvalExpiresAt) {
        return start(alertId, approvalExpiresAt, true);
    }

    /** Releases an owned execution only after its parent persisted the terminal child outcome. */
    public void releaseExclusiveRetention(String workflowId) {
        try {
            executionStore.releaseExclusiveRetention(
                    requireIdentifier(workflowId, "workflowId"), this::releaseWorkflowResources);
        } catch (RuntimeException cleanupFailure) {
            // Parent state is already durable. Keep cleanup retryable without
            // turning a successful child reconciliation into an API failure.
            LOGGER.warn("Unable to release terminal exclusive workflow retention", cleanupFailure);
        }
    }

    private WorkflowSnapshot start(String alertId, Instant approvalExpiresAt, boolean exclusive) {
        String requiredAlertId = requireIdentifier(alertId, "alertId");
        Instant now = Instant.now(clock);
        if (approvalExpiresAt != null && !approvalExpiresAt.isAfter(now)) {
            throw new IllegalArgumentException("approvalExpiresAt must be in the future");
        }
        if (!exclusive) {
            Optional<WorkflowSnapshot> existing = executionStore.findByAlertId(requiredAlertId);
            if (existing.filter(snapshot -> !isRetryable(snapshot.status())).isPresent()) {
                WorkflowSnapshot snapshot = existing.get();
                return approvalExpiresAt == null || snapshot.status() != WorkflowStatus.WAITING_APPROVAL
                        ? snapshot : bindApprovalDeadline(snapshot.workflowId(), approvalExpiresAt);
            }
        }

        String workflowId = requireIdentifier(workflowIds.get(), "workflowId");
        String graphThreadId = workflowId;
        AlertWorkflowState initialState = AlertWorkflowState.initial(
                workflowId, requiredAlertId, now, approvalExpiresAt);
        WorkflowExecutionStore.Execution execution = exclusive
                ? executionStore.registerExclusive(workflowId, requiredAlertId, graphThreadId,
                    compiledGraph, initialState)
                : executionStore.register(workflowId, requiredAlertId, graphThreadId,
                    compiledGraph, initialState);
        if (!exclusive && !execution.workflowId().equals(workflowId)) {
            return executionStore.get(execution.workflowId()).orElseThrow();
        }

        long startedSequence;
        try {
            startedSequence = nodes.publish(
                    workflowId,
                    WorkflowEvent.EventType.STARTED,
                    "workflow",
                    "alert workflow started");
        }
        catch (RuntimeException admissionFailure) {
            try {
                executionStore.discardUnstarted(execution, this::releaseWorkflowResources);
            }
            catch (RuntimeException cleanupFailure) {
                admissionFailure.addSuppressed(cleanupFailure);
            }
            throw admissionFailure;
        }

        RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
        try {
            Map<String, Object> graphInput = new LinkedHashMap<>(initialState.data());
            graphInput.put(AlertWorkflowState.EVENT_SEQUENCE, startedSequence);
            NodeOutput output = compiledGraph.invokeAndGetOutput(graphInput, config)
                    .orElseThrow(() -> new IllegalStateException("Graph produced no workflow output"));
            if (output instanceof InterruptionMetadata interruption) {
                execution.interruption(interruption);
                long pausedSequence = interruption.metadata(AlertWorkflowState.EVENT_SEQUENCE)
                        .filter(Number.class::isInstance)
                        .map(Number.class::cast)
                        .map(Number::longValue)
                        .orElseThrow(() -> new IllegalStateException("Approval interruption has no event sequence"));
                updateGraphState(execution, Map.of(
                        AlertWorkflowState.STATUS, WorkflowStatus.WAITING_APPROVAL.name(),
                        AlertWorkflowState.ERRORS, List.of(),
                        AlertWorkflowState.EVENT_SEQUENCE, pausedSequence,
                        AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
                return status(workflowId);
            }
            return completeFromState(execution, AlertWorkflowState.from(output.state()));
        }
        catch (RuntimeException exception) {
            return fail(execution, requiredAlertId, exception);
        }
    }

    public WorkflowSnapshot approve(String workflowId, ApprovalDecision decision) {
        String requiredWorkflowId = requireIdentifier(workflowId, "workflowId");
        ApprovalDecision requiredDecision = Objects.requireNonNull(decision, "decision");
        WorkflowExecutionStore.Execution execution = executionStore.execution(requiredWorkflowId)
                .orElseThrow(() -> new NoSuchElementException("Unknown workflow: " + requiredWorkflowId));

        UUID approvalAttemptId = UUID.randomUUID();
        Instant receivedAt;
        synchronized (execution) {
            receivedAt = Instant.now(clock);
            execution.beginApprovalAttempt(approvalAttemptId, receivedAt);
        }
        try {
            synchronized (execution) {
                WorkflowSnapshot current = status(requiredWorkflowId);
                if (current.approval().isPresent()) {
                    ApprovalDecision recorded = current.approval().orElseThrow();
                    if (recorded.idempotencyKey().equals(requiredDecision.idempotencyKey())) {
                        if (recorded.hasSameRequestPayloadAs(requiredDecision)) {
                            return current;
                        }
                        throw new IllegalArgumentException(
                                "idempotencyKey was already used for a different approval decision");
                    }
                }
                Optional<Instant> deadline = current.approvalExpiresAt();
                if (deadline.isPresent() && !receivedAt.isBefore(deadline.orElseThrow())) {
                    if (current.status() == WorkflowStatus.WAITING_APPROVAL) {
                        expireApprovalLocked(execution, current);
                    }
                    throw new IllegalStateException(APPROVAL_DEADLINE_EXPIRED);
                }
                if (current.status() != WorkflowStatus.WAITING_APPROVAL) {
                    throw new IllegalStateException(
                            "Workflow must be WAITING_APPROVAL before approval: " + current.status());
                }
                InterruptionMetadata interruption = execution.interruption()
                        .orElseThrow(() -> new IllegalStateException("Workflow has no approval interruption"));
                long resumedSequence = nodes.publish(
                        requiredWorkflowId,
                        WorkflowEvent.EventType.RESUMED,
                        AlertWorkflowNodes.HUMAN_APPROVAL,
                        "operator approval resumed workflow");
                try {
                    updateGraphState(execution, Map.of(
                            AlertWorkflowState.APPROVAL, AlertWorkflowState.serializable(requiredDecision),
                            AlertWorkflowState.STATUS, WorkflowStatus.RUNNING.name(),
                            AlertWorkflowState.EVENT_SEQUENCE, resumedSequence,
                            AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
                    InterruptionMetadata feedback = InterruptionMetadata.builder(interruption)
                            .addMetadata("approvalDecision", requiredDecision)
                            .build();
                    RunnableConfig resumeConfig = RunnableConfig.builder()
                            .threadId(execution.graphThreadId())
                            .addHumanFeedback(feedback)
                            .build();
                    NodeOutput output = execution.compiledGraph()
                            .invokeAndGetOutput(Map.of(), resumeConfig)
                            .orElseThrow(() -> new IllegalStateException("Graph produced no output after approval"));
                    if (output instanceof InterruptionMetadata) {
                        throw new IllegalStateException("Workflow interrupted again after approval");
                    }
                    return completeFromState(execution, AlertWorkflowState.from(output.state()));
                }
                catch (RuntimeException exception) {
                    return fail(execution, current.alertId(), exception);
                }
            }
        } finally {
            synchronized (execution) {
                execution.endApprovalAttempt(approvalAttemptId);
            }
        }
    }

    public WorkflowSnapshot expireApproval(String workflowId, Instant approvalExpiresAt) {
        String requiredWorkflowId = requireIdentifier(workflowId, "workflowId");
        Instant requiredDeadline = Objects.requireNonNull(approvalExpiresAt, "approvalExpiresAt");
        WorkflowExecutionStore.Execution execution = executionStore.execution(requiredWorkflowId)
                .orElseThrow(() -> new NoSuchElementException("Unknown workflow: " + requiredWorkflowId));
        synchronized (execution) {
            WorkflowSnapshot current = bindApprovalDeadlineLocked(execution, requiredDeadline);
            Instant effectiveDeadline = current.approvalExpiresAt().orElse(requiredDeadline);
            if (current.status() != WorkflowStatus.WAITING_APPROVAL
                    || Instant.now(clock).isBefore(effectiveDeadline)
                    || execution.hasApprovalAttemptBefore(effectiveDeadline)) {
                return current;
            }
            return expireApprovalLocked(execution, current);
        }
    }

    public WorkflowSnapshot cancel(String workflowId) {
        String requiredWorkflowId = requireIdentifier(workflowId, "workflowId");
        WorkflowExecutionStore.Execution execution = executionStore.execution(requiredWorkflowId)
                .orElseThrow(() -> new NoSuchElementException("Unknown workflow: " + requiredWorkflowId));
        synchronized (execution) {
            WorkflowSnapshot current = execution.snapshot();
            if (current.status() != WorkflowStatus.WAITING_APPROVAL) return current;
            return terminalizeWaitingApproval(execution, CANCELLED_BY_ORCHESTRATION);
        }
    }

    private WorkflowSnapshot bindApprovalDeadline(String workflowId, Instant requestedDeadline) {
        WorkflowExecutionStore.Execution execution = executionStore.execution(workflowId)
                .orElseThrow(() -> new NoSuchElementException("Unknown workflow: " + workflowId));
        synchronized (execution) {
            return bindApprovalDeadlineLocked(execution, requestedDeadline);
        }
    }

    private WorkflowSnapshot bindApprovalDeadlineLocked(WorkflowExecutionStore.Execution execution,
                                                         Instant requestedDeadline) {
        WorkflowSnapshot current = execution.snapshot();
        Instant effective = current.approvalExpiresAt()
                .filter(existing -> existing.isBefore(requestedDeadline))
                .orElse(requestedDeadline);
        if (current.approvalExpiresAt().filter(effective::equals).isPresent()) return current;
        updateGraphState(execution, Map.of(
                AlertWorkflowState.APPROVAL_EXPIRES_AT, effective.toString(),
                AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
        return execution.snapshot();
    }

    private WorkflowSnapshot expireApprovalLocked(WorkflowExecutionStore.Execution execution,
                                                    WorkflowSnapshot current) {
        return terminalizeWaitingApproval(execution, APPROVAL_DEADLINE_EXPIRED);
    }

    private WorkflowSnapshot terminalizeWaitingApproval(WorkflowExecutionStore.Execution execution,
                                                         String reason) {
        long sequence = nodes.publish(execution.workflowId(), WorkflowEvent.EventType.FAILED,
                AlertWorkflowNodes.HUMAN_APPROVAL, reason);
        updateGraphState(execution, Map.of(
                AlertWorkflowState.STATUS, WorkflowStatus.FAILED.name(),
                AlertWorkflowState.ERRORS, List.of(reason),
                AlertWorkflowState.EVENT_SEQUENCE, sequence,
                AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
        execution.failureCause(new IllegalStateException(reason));
        eventPublisher.complete(execution.workflowId());
        WorkflowSnapshot snapshot = execution.snapshot();
        retireTerminalExecution(execution);
        return snapshot;
    }

    public WorkflowSnapshot status(String workflowId) {
        return executionStore.get(requireIdentifier(workflowId, "workflowId"))
                .orElseThrow(() -> new NoSuchElementException("Unknown workflow: " + workflowId));
    }

    private CompiledGraph compileGraph() {
        try {
            ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
            StateSerializer stateSerializer = new SpringAIJacksonStateSerializer(OverAllState::new, objectMapper);
            StateGraph graph = new StateGraph(
                    "smart-park-alert-workflow",
                    AlertWorkflowState::keyStrategies,
                    stateSerializer);
            graph.addNode(AlertWorkflowNodes.CLASSIFY_ALERT, nodes.classifyAlert())
                    .addNode(AlertWorkflowNodes.COLLECT_PARK_CONTEXT, nodes.collectParkContext())
                    .addNode(AlertWorkflowNodes.RETRIEVE_KNOWLEDGE, nodes.retrieveKnowledge())
                    .addNode(AlertWorkflowNodes.ENERGY_ANALYSIS, nodes.energyAnalysis())
                    .addNode(AlertWorkflowNodes.SECURITY_REVIEW, nodes.securityReview())
                    .addNode(AlertWorkflowNodes.DIAGNOSE_ALERT, nodes.diagnoseAlert())
                    .addNode(AlertWorkflowNodes.RISK_GATE, nodes.riskGate())
                    .addNode(AlertWorkflowNodes.HUMAN_APPROVAL, nodes.humanApproval())
                    .addNode(AlertWorkflowNodes.CREATE_WORK_ORDER, nodes.createWorkOrder())
                    .addNode(AlertWorkflowNodes.SUMMARIZE_RESULT, nodes.summarizeResult())
                    .addEdge(StateGraph.START, AlertWorkflowNodes.CLASSIFY_ALERT)
                    .addEdge(AlertWorkflowNodes.CLASSIFY_ALERT, AlertWorkflowNodes.COLLECT_PARK_CONTEXT)
                    .addConditionalEdges(
                            AlertWorkflowNodes.COLLECT_PARK_CONTEXT,
                            edge_async(nodes::scenarioRoute),
                            Map.of(
                                    AlertWorkflowNodes.ENERGY_ANALYSIS, AlertWorkflowNodes.ENERGY_ANALYSIS,
                                    AlertWorkflowNodes.SECURITY_REVIEW, AlertWorkflowNodes.SECURITY_REVIEW,
                                    AlertWorkflowNodes.RETRIEVE_KNOWLEDGE, AlertWorkflowNodes.RETRIEVE_KNOWLEDGE))
                    .addEdge(AlertWorkflowNodes.ENERGY_ANALYSIS, AlertWorkflowNodes.RETRIEVE_KNOWLEDGE)
                    .addEdge(AlertWorkflowNodes.SECURITY_REVIEW, AlertWorkflowNodes.RETRIEVE_KNOWLEDGE)
                    .addEdge(AlertWorkflowNodes.RETRIEVE_KNOWLEDGE, AlertWorkflowNodes.DIAGNOSE_ALERT)
                    .addEdge(AlertWorkflowNodes.DIAGNOSE_ALERT, AlertWorkflowNodes.RISK_GATE)
                    .addConditionalEdges(
                            AlertWorkflowNodes.RISK_GATE,
                            edge_async(nodes::route),
                            Map.of(
                                    Route.CREATE_WORK_ORDER.name(), AlertWorkflowNodes.CREATE_WORK_ORDER,
                                    Route.WAIT_FOR_APPROVAL.name(), AlertWorkflowNodes.HUMAN_APPROVAL,
                                    Route.REJECT.name(), AlertWorkflowNodes.SUMMARIZE_RESULT))
                    .addConditionalEdges(
                            AlertWorkflowNodes.HUMAN_APPROVAL,
                            edge_async(nodes::route),
                            Map.of(
                                    Route.CREATE_WORK_ORDER.name(), AlertWorkflowNodes.CREATE_WORK_ORDER,
                                    Route.REJECT.name(), AlertWorkflowNodes.SUMMARIZE_RESULT))
                    .addEdge(AlertWorkflowNodes.CREATE_WORK_ORDER, AlertWorkflowNodes.SUMMARIZE_RESULT)
                    .addEdge(AlertWorkflowNodes.SUMMARIZE_RESULT, StateGraph.END);
            return graph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(checkpointSaver).build())
                    .build());
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to compile alert workflow graph", exception);
        }
    }

    private WorkflowSnapshot completeFromState(
            WorkflowExecutionStore.Execution execution,
            AlertWorkflowState state) {
        WorkflowStatus status = state.status();
        long completedSequence = nodes.publish(
                execution.workflowId(),
                WorkflowEvent.EventType.COMPLETED,
                "workflow",
                status == WorkflowStatus.REJECTED ? "workflow rejected" : "workflow completed");
        updateGraphState(execution, Map.of(
                AlertWorkflowState.STATUS, status.name(),
                AlertWorkflowState.ERRORS, state.errors(),
                AlertWorkflowState.EVENT_SEQUENCE, completedSequence,
                AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
        WorkflowSnapshot snapshot = execution.snapshot();
        eventPublisher.complete(execution.workflowId());
        retireTerminalExecution(execution);
        return snapshot;
    }

    private WorkflowSnapshot fail(
            WorkflowExecutionStore.Execution execution,
            String alertId,
            RuntimeException exception) {
        WorkflowFailure failure = findFailure(exception).orElseGet(() -> new WorkflowFailure(
                WorkflowFailure.Code.WORKFLOW_FAILED,
                "Workflow execution failed",
                "workflow",
                exception));
        Throwable detailedCause = failure.getCause() == null ? exception : failure.getCause();
        execution.failureCause(detailedCause);
        long sequence = nodes.publish(
                execution.workflowId(),
                WorkflowEvent.EventType.FAILED,
                failure.node(),
                failure.code().name());
        WorkflowStatus failedStatus = failure.code() == WorkflowFailure.Code.WORK_ORDER_FAILED
                ? WorkflowStatus.WORK_ORDER_FAILED
                : WorkflowStatus.FAILED;
        updateGraphState(execution, Map.of(
                AlertWorkflowState.STATUS, failedStatus.name(),
                AlertWorkflowState.ERRORS, List.of(failure.publicError()),
                AlertWorkflowState.EVENT_SEQUENCE, sequence,
                AlertWorkflowState.UPDATED_AT, Instant.now(clock).toString()));
        WorkflowSnapshot failed = execution.snapshot();
        eventPublisher.complete(execution.workflowId());
        retireTerminalExecution(execution);
        return failed;
    }

    private void retireTerminalExecution(WorkflowExecutionStore.Execution execution) {
        try {
            executionStore.markTerminalAndCompact(execution, this::releaseWorkflowResources);
        }
        catch (RuntimeException cleanupFailure) {
            // The terminal result remains authoritative. The store deliberately keeps an entry
            // whose cleanup failed so a later terminal transition can retry the reclamation.
            LOGGER.warn("Unable to compact terminal exclusive workflow executions", cleanupFailure);
        }
    }

    private void releaseWorkflowResources(WorkflowExecutionStore.Execution execution) {
        try {
            checkpointSaver.release(RunnableConfig.builder()
                    .threadId(execution.graphThreadId())
                    .build());
            eventPublisher.remove(execution.workflowId());
        }
        catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to release workflow resources: " + execution.workflowId(), exception);
        }
    }

    private static void updateGraphState(
            WorkflowExecutionStore.Execution execution,
            Map<String, Object> delta) {
        try {
            execution.compiledGraph().updateState(
                    RunnableConfig.builder().threadId(execution.graphThreadId()).build(),
                    delta);
        }
        catch (Exception exception) {
            throw new IllegalStateException("Unable to update workflow checkpoint", exception);
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static boolean isRetryable(WorkflowStatus status) {
        return status == WorkflowStatus.FAILED || status == WorkflowStatus.WORK_ORDER_FAILED;
    }

    private static Optional<WorkflowFailure> findFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof WorkflowFailure failure) {
                return Optional.of(failure);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
