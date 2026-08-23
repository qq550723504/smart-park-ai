package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeActionWithConfig;
import com.alibaba.cloud.ai.graph.action.InterruptableAction;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.model.Alert;
import com.example.smartpark.model.ApprovalDecision;
import com.example.smartpark.model.Diagnosis;
import com.example.smartpark.model.KnowledgeDocument;
import com.example.smartpark.model.ParkContext;
import com.example.smartpark.model.RiskLevel;
import com.example.smartpark.model.WorkOrder;
import com.example.smartpark.model.WorkflowStatus;
import com.example.smartpark.park.AlertPort;
import com.example.smartpark.park.DevicePort;
import com.example.smartpark.park.KnowledgePort;
import com.example.smartpark.park.WorkOrderPort;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

public final class AlertWorkflowNodes {

    public static final String CLASSIFY_ALERT = "classifyAlert";
    public static final String COLLECT_PARK_CONTEXT = "collectParkContext";
    public static final String RETRIEVE_KNOWLEDGE = "retrieveKnowledge";
    public static final String DIAGNOSE_ALERT = "diagnoseAlert";
    public static final String RISK_GATE = "riskGate";
    public static final String HUMAN_APPROVAL = "humanApproval";
    public static final String CREATE_WORK_ORDER = "createWorkOrder";
    public static final String SUMMARIZE_RESULT = "summarizeResult";

    private final AlertTriageAgent triageAgent;
    private final AlertDiagnosisAgent diagnosisAgent;
    private final DevicePort devicePort;
    private final AlertPort alertPort;
    private final WorkOrderPort workOrderPort;
    private final KnowledgePort knowledgePort;
    private final WorkflowEventPublisher eventPublisher;
    private final Clock clock;
    private final RiskGate riskGate;

    public AlertWorkflowNodes(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            WorkflowEventPublisher eventPublisher,
            Clock clock,
            double confidenceThreshold) {
        this.triageAgent = Objects.requireNonNull(triageAgent, "triageAgent");
        this.diagnosisAgent = Objects.requireNonNull(diagnosisAgent, "diagnosisAgent");
        this.devicePort = Objects.requireNonNull(devicePort, "devicePort");
        this.alertPort = Objects.requireNonNull(alertPort, "alertPort");
        this.workOrderPort = Objects.requireNonNull(workOrderPort, "workOrderPort");
        this.knowledgePort = Objects.requireNonNull(knowledgePort, "knowledgePort");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.riskGate = new RiskGate(confidenceThreshold);
    }

    public AsyncNodeAction classifyAlert() {
        return observed(CLASSIFY_ALERT, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            toolCall(workflowState.workflowId(), CLASSIFY_ALERT, "AlertPort.getAlert");
            Alert alert = guarded(
                    WorkflowFailure.Code.ALERT_LOOKUP_FAILED,
                    "Unable to load alert",
                    CLASSIFY_ALERT,
                    () -> alertPort.getAlert(workflowState.alertId()));
            AlertTriageAgent.AlertClassificationResult classification = guarded(
                    WorkflowFailure.Code.CLASSIFICATION_FAILED,
                    "Unable to classify alert",
                    CLASSIFY_ALERT,
                    () -> triageAgent.classify(alert));
            return delta(
                    AlertWorkflowState.ALERT, AlertWorkflowState.serializable(alert),
                    AlertWorkflowState.CLASSIFICATION, AlertWorkflowState.serializable(classification),
                    AlertWorkflowState.RISK_LEVEL, AlertWorkflowState.serializable(classification.riskLevel()));
        });
    }

    public AsyncNodeAction collectParkContext() {
        return observed(COLLECT_PARK_CONTEXT, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            Alert alert = workflowState.alert();
            toolCall(workflowState.workflowId(), COLLECT_PARK_CONTEXT, "DevicePort.getDevice");
            var device = guarded(
                    WorkflowFailure.Code.PARK_CONTEXT_FAILED,
                    "Unable to collect park context",
                    COLLECT_PARK_CONTEXT,
                    () -> devicePort.getDevice(alert.deviceId()));
            toolCall(workflowState.workflowId(), COLLECT_PARK_CONTEXT, "AlertPort.findHistory");
            var alertHistory = guarded(
                    WorkflowFailure.Code.PARK_CONTEXT_FAILED,
                    "Unable to collect park context",
                    COLLECT_PARK_CONTEXT,
                    () -> alertPort.findHistory(alert.deviceId()));
            toolCall(workflowState.workflowId(), COLLECT_PARK_CONTEXT, "WorkOrderPort.findByWorkflowId");
            var workOrders = guarded(
                    WorkflowFailure.Code.PARK_CONTEXT_FAILED,
                    "Unable to collect park context",
                    COLLECT_PARK_CONTEXT,
                    () -> workOrderPort.findByWorkflowId(workflowState.workflowId()));
            ParkContext context = new ParkContext(
                    alert.parkId(),
                    alert.buildingId(),
                    device,
                    alertHistory,
                    workOrders);
            return Map.of(AlertWorkflowState.PARK_CONTEXT, AlertWorkflowState.serializable(context));
        });
    }

    public AsyncNodeAction retrieveKnowledge() {
        return observed(RETRIEVE_KNOWLEDGE, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            String query = workflowState.classification().category().name().toLowerCase(java.util.Locale.ROOT);
            toolCall(workflowState.workflowId(), RETRIEVE_KNOWLEDGE, "KnowledgePort.search");
            List<KnowledgeDocument> documents = guarded(
                    WorkflowFailure.Code.KNOWLEDGE_RETRIEVAL_FAILED,
                    "Unable to retrieve park knowledge",
                    RETRIEVE_KNOWLEDGE,
                    () -> knowledgePort.search(query));
            return Map.of(
                    AlertWorkflowState.RETRIEVED_DOCUMENTS,
                    AlertWorkflowState.serializable(List.copyOf(documents)));
        });
    }

    public AsyncNodeAction diagnoseAlert() {
        return observed(DIAGNOSE_ALERT, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            Diagnosis diagnosis = guarded(
                    WorkflowFailure.Code.DIAGNOSIS_FAILED,
                    "Unable to diagnose alert",
                    DIAGNOSE_ALERT,
                    () -> diagnosisAgent.diagnose(
                            workflowState.alert(),
                            workflowState.parkContext(),
                            workflowState.retrievedDocuments(),
                            toolName -> toolCall(
                                    workflowState.workflowId(),
                                    DIAGNOSE_ALERT,
                                    "AgentTool." + toolName)));
            return delta(
                    AlertWorkflowState.DIAGNOSIS, AlertWorkflowState.serializable(diagnosis),
                    AlertWorkflowState.RISK_LEVEL, AlertWorkflowState.serializable(diagnosis.riskLevel()));
        });
    }

    public AsyncNodeAction riskGate() {
        return observed(RISK_GATE, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            Route route = riskGate.route(
                    workflowState.alert(),
                    workflowState.classification(),
                    workflowState.diagnosis().orElseThrow(),
                    workflowState.retrievedDocuments());
            return Map.of(AlertWorkflowState.ROUTE, AlertWorkflowState.serializable(route));
        });
    }

    public AsyncNodeActionWithConfig humanApproval() {
        return new HumanApprovalAction();
    }

    public AsyncNodeAction createWorkOrder() {
        return observed(CREATE_WORK_ORDER, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            toolCall(workflowState.workflowId(), CREATE_WORK_ORDER, "WorkOrderPort.findByWorkflowId");
            List<WorkOrder> existing = guarded(
                    WorkflowFailure.Code.WORK_ORDER_FAILED,
                    "Unable to create work order",
                    CREATE_WORK_ORDER,
                    () -> workOrderPort.findByWorkflowId(workflowState.workflowId()));
            WorkOrder workOrder;
            if (existing.isEmpty()) {
                toolCall(workflowState.workflowId(), CREATE_WORK_ORDER, "WorkOrderPort.create");
                workOrder = guarded(
                        WorkflowFailure.Code.WORK_ORDER_FAILED,
                        "Unable to create work order",
                        CREATE_WORK_ORDER,
                        () -> workOrderPort.create(
                                workflowState.workflowId(),
                                workflowState.alertId(),
                                workflowState.diagnosis().orElseThrow().summary()));
            }
            else {
                workOrder = existing.get(0);
            }
            return Map.of(AlertWorkflowState.WORK_ORDER, AlertWorkflowState.serializable(workOrder));
        });
    }

    public AsyncNodeAction summarizeResult() {
        return observed(SUMMARIZE_RESULT, state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            boolean rejected = workflowState.approval()
                    .map(ApprovalDecision::decision)
                    .filter(ApprovalDecision.Decision.REJECTED::equals)
                    .isPresent();
            WorkflowStatus status = rejected ? WorkflowStatus.REJECTED : WorkflowStatus.COMPLETED;
            String summary = rejected
                    ? "Alert workflow rejected by operator before work-order creation."
                    : "Alert workflow completed with an idempotent work order.";
            return delta(
                    AlertWorkflowState.STATUS, AlertWorkflowState.serializable(status),
                    AlertWorkflowState.RESULT_SUMMARY, summary);
        });
    }

    public String route(OverAllState state) {
        return AlertWorkflowState.from(state).route().name();
    }

    public long publish(String workflowId, WorkflowEvent.EventType type, String node, String summary) {
        return eventPublisher.publish(
                workflowId,
                type,
                node,
                Instant.now(clock),
                summary).sequence();
    }

    private AsyncNodeAction observed(String node, ThrowingNodeAction action) {
        return node_async(state -> {
            AlertWorkflowState workflowState = AlertWorkflowState.from(state);
            String workflowId = workflowState.workflowId();
            publish(workflowId, WorkflowEvent.EventType.NODE_STARTED, node, node + " started");
            try {
                Map<String, Object> result = new LinkedHashMap<>(action.apply(state));
                long sequence = publish(
                        workflowId,
                        WorkflowEvent.EventType.NODE_COMPLETED,
                        node,
                        node + " completed");
                result.put(AlertWorkflowState.EVENT_SEQUENCE, sequence);
                return result;
            }
            catch (Exception exception) {
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new WorkflowFailure(
                        WorkflowFailure.Code.WORKFLOW_FAILED,
                        "Workflow node failed",
                        node,
                        exception);
            }
        });
    }

    private void toolCall(String workflowId, String node, String operation) {
        publish(workflowId, WorkflowEvent.EventType.TOOL_CALLED, node, operation);
    }

    private static Map<String, Object> delta(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static <T> T guarded(
            WorkflowFailure.Code code,
            String safeSummary,
            String node,
            ThrowingSupplier<T> action) {
        try {
            return action.get();
        }
        catch (WorkflowFailure failure) {
            throw failure;
        }
        catch (Exception exception) {
            throw new WorkflowFailure(code, safeSummary, node, exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingNodeAction {
        Map<String, Object> apply(OverAllState state) throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private final class HumanApprovalAction implements AsyncNodeActionWithConfig, InterruptableAction {

        @Override
        public Optional<InterruptionMetadata> interrupt(
                String nodeId,
                OverAllState state,
                RunnableConfig config) {
            if (config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY).isPresent()) {
                return Optional.empty();
            }
            String workflowId = AlertWorkflowState.from(state).workflowId();
            publish(workflowId, WorkflowEvent.EventType.NODE_STARTED, nodeId, nodeId + " started");
            long pausedSequence = publish(
                    workflowId,
                    WorkflowEvent.EventType.PAUSED,
                    nodeId,
                    "waiting for operator approval");
            return Optional.of(InterruptionMetadata.builder(nodeId, state)
                    .addMetadata("reason", "risk gate requires operator approval")
                    .addMetadata(AlertWorkflowState.EVENT_SEQUENCE, pausedSequence)
                    .build());
        }

        @Override
        public CompletableFuture<Map<String, Object>> apply(OverAllState state, RunnableConfig config) {
            try {
                Object feedbackValue = config.metadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY)
                        .orElseThrow(() -> new IllegalStateException("Missing human approval feedback"));
                if (!(feedbackValue instanceof InterruptionMetadata feedback)) {
                    throw new IllegalStateException("Human approval feedback has an invalid type");
                }
                ApprovalDecision decision = feedback.metadata("approvalDecision")
                        .filter(ApprovalDecision.class::isInstance)
                        .map(ApprovalDecision.class::cast)
                        .orElseThrow(() -> new IllegalStateException("Missing approval decision"));
                Route route = decision.decision() == ApprovalDecision.Decision.APPROVED
                        ? Route.CREATE_WORK_ORDER
                        : Route.REJECT;
                WorkflowStatus status = route == Route.REJECT
                        ? WorkflowStatus.REJECTED
                        : WorkflowStatus.RUNNING;
                String workflowId = AlertWorkflowState.from(state).workflowId();
                long sequence = publish(
                        workflowId,
                        WorkflowEvent.EventType.NODE_COMPLETED,
                        HUMAN_APPROVAL,
                        "operator decision recorded");
                return CompletableFuture.completedFuture(delta(
                        AlertWorkflowState.APPROVAL, AlertWorkflowState.serializable(decision),
                        AlertWorkflowState.ROUTE, AlertWorkflowState.serializable(route),
                        AlertWorkflowState.STATUS, AlertWorkflowState.serializable(status),
                        AlertWorkflowState.EVENT_SEQUENCE, sequence));
            }
            catch (Exception exception) {
                String workflowId = AlertWorkflowState.from(state).workflowId();
                return CompletableFuture.failedFuture(new WorkflowFailure(
                        WorkflowFailure.Code.APPROVAL_FAILED,
                        "Unable to apply approval",
                        HUMAN_APPROVAL,
                        exception));
            }
        }
    }

    public static final class RiskGate {

        private final double confidenceThreshold;

        public RiskGate(double confidenceThreshold) {
            if (confidenceThreshold < 0.0 || confidenceThreshold > 1.0) {
                throw new IllegalArgumentException("confidenceThreshold must be between 0 and 1");
            }
            this.confidenceThreshold = confidenceThreshold;
        }

        public Route route(
                Alert alert,
                AlertTriageAgent.AlertClassificationResult classification,
                Diagnosis diagnosis,
                List<KnowledgeDocument> documents) {
            Objects.requireNonNull(alert, "alert");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(diagnosis, "diagnosis");
            List<KnowledgeDocument> evidence = List.copyOf(Objects.requireNonNull(documents, "documents"));
            if (alert.riskHint().isHighRisk()
                    || classification.riskLevel().isHighRisk()
                    || diagnosis.riskLevel().isHighRisk()
                    || !(classification.confidence() >= confidenceThreshold)
                    || !(diagnosis.confidence() >= confidenceThreshold)
                    || evidence.isEmpty()) {
                return Route.WAIT_FOR_APPROVAL;
            }
            return Route.CREATE_WORK_ORDER;
        }
    }
}
