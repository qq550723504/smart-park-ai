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
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.ApprovalDecision;
import com.example.smartpark.model.WorkflowStatus;
import com.example.smartpark.park.AlertPort;
import com.example.smartpark.park.DevicePort;
import com.example.smartpark.park.KnowledgePort;
import com.example.smartpark.park.WorkOrderPort;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;

public final class AlertWorkflow {

    private static final double CONFIDENCE_THRESHOLD = 0.75;

    private final WorkflowExecutionStore executionStore;
    private final WorkflowEventPublisher eventPublisher;
    private final AlertWorkflowNodes nodes;
    private final CompiledGraph compiledGraph;
    private final Supplier<String> workflowIds;

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
                () -> UUID.randomUUID().toString());
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
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.workflowIds = Objects.requireNonNull(workflowIds, "workflowIds");
        this.nodes = new AlertWorkflowNodes(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                workOrderPort,
                knowledgePort,
                executionStore,
                eventPublisher,
                clock,
                CONFIDENCE_THRESHOLD);
        this.compiledGraph = compileGraph();
    }

    public WorkflowSnapshot start(String alertId) {
        String requiredAlertId = requireIdentifier(alertId, "alertId");
        Optional<WorkflowSnapshot> running = executionStore.findRunningByAlertId(requiredAlertId);
        if (running.isPresent()) {
            return running.get();
        }

        String workflowId = requireIdentifier(workflowIds.get(), "workflowId");
        String graphThreadId = workflowId;
        AlertWorkflowState initialState = AlertWorkflowState.initial(workflowId, requiredAlertId);
        WorkflowSnapshot initialSnapshot = snapshot(
                initialState,
                WorkflowStatus.RUNNING,
                List.of(),
                0L);
        WorkflowExecutionStore.Execution execution = executionStore.register(
                workflowId,
                requiredAlertId,
                graphThreadId,
                compiledGraph,
                initialSnapshot);
        if (!execution.workflowId().equals(workflowId)) {
            return executionStore.get(execution.workflowId()).orElseThrow();
        }

        long startedSequence = nodes.publish(
                workflowId,
                WorkflowEvent.EventType.STARTED,
                "workflow",
                "alert workflow started");
        executionStore.save(snapshot(initialState, WorkflowStatus.RUNNING, List.of(), startedSequence));

        RunnableConfig config = RunnableConfig.builder().threadId(graphThreadId).build();
        try {
            NodeOutput output = compiledGraph.invokeAndGetOutput(initialState.data(), config)
                    .orElseThrow(() -> new IllegalStateException("Graph produced no workflow output"));
            if (output instanceof InterruptionMetadata interruption) {
                execution.interruption(interruption);
                WorkflowSnapshot waiting = snapshot(
                        AlertWorkflowState.from(interruption.state()),
                        WorkflowStatus.WAITING_APPROVAL,
                        List.of(),
                        execution.eventSequence());
                return executionStore.save(waiting);
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

        synchronized (execution) {
            WorkflowSnapshot current = status(requiredWorkflowId);
            if (current.status() != WorkflowStatus.WAITING_APPROVAL) {
                throw new IllegalStateException(
                        "Workflow must be WAITING_APPROVAL before approval: " + current.status());
            }
            InterruptionMetadata interruption = execution.interruption()
                    .orElseThrow(() -> new IllegalStateException("Workflow has no approval interruption"));
            nodes.publish(
                    requiredWorkflowId,
                    WorkflowEvent.EventType.RESUMED,
                    AlertWorkflowNodes.HUMAN_APPROVAL,
                    "operator approval resumed workflow");
            InterruptionMetadata feedback = InterruptionMetadata.builder(interruption)
                    .addMetadata("approvalDecision", requiredDecision)
                    .build();
            RunnableConfig resumeConfig = RunnableConfig.builder()
                    .threadId(execution.graphThreadId())
                    .addHumanFeedback(feedback)
                    .build();
            try {
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
                    .addNode(AlertWorkflowNodes.DIAGNOSE_ALERT, nodes.diagnoseAlert())
                    .addNode(AlertWorkflowNodes.RISK_GATE, nodes.riskGate())
                    .addNode(AlertWorkflowNodes.HUMAN_APPROVAL, nodes.humanApproval())
                    .addNode(AlertWorkflowNodes.CREATE_WORK_ORDER, nodes.createWorkOrder())
                    .addNode(AlertWorkflowNodes.SUMMARIZE_RESULT, nodes.summarizeResult())
                    .addEdge(StateGraph.START, AlertWorkflowNodes.CLASSIFY_ALERT)
                    .addEdge(AlertWorkflowNodes.CLASSIFY_ALERT, AlertWorkflowNodes.COLLECT_PARK_CONTEXT)
                    .addEdge(AlertWorkflowNodes.COLLECT_PARK_CONTEXT, AlertWorkflowNodes.RETRIEVE_KNOWLEDGE)
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
            MemorySaver memorySaver = MemorySaver.builder().build();
            return graph.compile(CompileConfig.builder()
                    .saverConfig(SaverConfig.builder().register(memorySaver).build())
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
        WorkflowSnapshot snapshot = snapshot(state, status, state.errors(), completedSequence);
        executionStore.save(snapshot);
        eventPublisher.complete(execution.workflowId());
        return snapshot;
    }

    private WorkflowSnapshot fail(
            WorkflowExecutionStore.Execution execution,
            String alertId,
            RuntimeException exception) {
        String error = rootMessage(exception);
        long sequence = nodes.publish(
                execution.workflowId(),
                WorkflowEvent.EventType.FAILED,
                "workflow",
                "workflow failed");
        AlertWorkflowState state = execution.compiledGraph()
                .stateOf(RunnableConfig.builder().threadId(execution.graphThreadId()).build())
                .map(snapshot -> AlertWorkflowState.from(snapshot.state()))
                .orElseGet(() -> AlertWorkflowState.initial(execution.workflowId(), alertId));
        WorkflowSnapshot failed = snapshot(state, WorkflowStatus.FAILED, List.of(error), sequence);
        executionStore.save(failed);
        eventPublisher.complete(execution.workflowId());
        return failed;
    }

    private static WorkflowSnapshot snapshot(
            AlertWorkflowState state,
            WorkflowStatus status,
            List<String> errors,
            long eventSequence) {
        return new WorkflowSnapshot(
                state.workflowId(),
                state.alertId(),
                status,
                state.snapshotPayload(status, errors, eventSequence),
                state.diagnosis().orElse(null),
                state.approval(),
                state.workOrder().orElse(null),
                errors,
                eventSequence);
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable root = throwable;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
