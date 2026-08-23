package com.example.smartpark.workflow;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import com.example.smartpark.park.mock.MockParkSystem;
import com.example.smartpark.tool.AlertQueryTool;
import com.example.smartpark.tool.DeviceQueryTool;
import com.example.smartpark.tool.ParkKnowledgeTool;
import com.example.smartpark.tool.WorkOrderTool;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AlertWorkflowTest {

    private static final Instant NOW = Instant.parse("2026-08-23T01:45:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void lowRiskAlertCompletesWithOneWorkOrder() {
        Fixture fixture = fixture("ALT-TEMP-001", 0.92, "LOW", null, sequentialIds());

        WorkflowSnapshot result = fixture.workflow.start("ALT-TEMP-001");

        assertThat(result.status()).as("workflow errors: %s", result.errors()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(result.workOrder()).isNotNull();
        assertThat(fixture.parkSystem.findByWorkflowId(result.workflowId())).hasSize(1);
        assertThat(result.statePayload()).containsKeys(
                AlertWorkflowState.WORKFLOW_ID,
                AlertWorkflowState.ALERT_ID,
                AlertWorkflowState.ALERT,
                AlertWorkflowState.CLASSIFICATION,
                AlertWorkflowState.PARK_CONTEXT,
                AlertWorkflowState.RETRIEVED_DOCUMENTS,
                AlertWorkflowState.DIAGNOSIS,
                AlertWorkflowState.RISK_LEVEL,
                AlertWorkflowState.APPROVAL,
                AlertWorkflowState.WORK_ORDER,
                AlertWorkflowState.STATUS,
                AlertWorkflowState.ERRORS,
                AlertWorkflowState.EVENT_SEQUENCE);
    }

    @Test
    void highRiskAlertPausesAndApprovalResumesTheSameThread() {
        Fixture fixture = fixture("ALT-POWER-001", 0.96, "HIGH", null, sequentialIds());

        WorkflowSnapshot waiting = fixture.workflow.start("ALT-POWER-001");
        assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(checkpointState(fixture, waiting.workflowId()).status())
                .isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(waiting.statePayload()).containsEntry(
                AlertWorkflowState.STATUS,
                WorkflowStatus.WAITING_APPROVAL.name());
        WorkflowSnapshot completed = fixture.workflow.approve(
                waiting.workflowId(),
                approvedAt("2026-08-23T02:00:00Z"));

        assertThat(waiting.workOrder()).isNull();
        assertThat(completed.workflowId()).isEqualTo(waiting.workflowId());
        assertThat(fixture.store.execution(completed.workflowId()))
                .get()
                .extracting(WorkflowExecutionStore.Execution::graphThreadId)
                .isEqualTo(waiting.workflowId());
        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(completed.approval()).contains(approvedAt("2026-08-23T02:00:00Z"));
        assertThat(fixture.parkSystem.findByWorkflowId(waiting.workflowId())).hasSize(1);
        List<WorkflowEvent.EventType> eventTypes = fixture.publisher.events(waiting.workflowId())
                .map(WorkflowEvent::eventType)
                .collectList()
                .block(Duration.ofSeconds(2));
        assertThat(eventTypes).contains(WorkflowEvent.EventType.PAUSED, WorkflowEvent.EventType.RESUMED);
    }

    @Test
    void rejectionEndsWithoutCreatingAWorkOrder() {
        Fixture fixture = fixture("ALT-POWER-001", 0.96, "HIGH", null, sequentialIds());
        WorkflowSnapshot waiting = fixture.workflow.start("ALT-POWER-001");

        WorkflowSnapshot rejected = fixture.workflow.approve(
                waiting.workflowId(),
                new ApprovalDecision(
                        ApprovalDecision.Decision.REJECTED,
                        "operator-1",
                        "insufficient evidence",
                        "approval-reject-1",
                        Instant.parse("2026-08-23T02:01:00Z")));

        assertThat(rejected.status()).isEqualTo(WorkflowStatus.REJECTED);
        assertThat(rejected.approval()).get().extracting(ApprovalDecision::decision)
                .isEqualTo(ApprovalDecision.Decision.REJECTED);
        assertThat(fixture.parkSystem.findByWorkflowId(waiting.workflowId())).isEmpty();
    }

    @Test
    void missingKnowledgeEvidencePausesBeforeSideEffects() {
        KnowledgePort noKnowledge = query -> List.of();
        Fixture fixture = fixture("ALT-TEMP-001", 0.95, "LOW", noKnowledge, sequentialIds());

        WorkflowSnapshot waiting = fixture.workflow.start("ALT-TEMP-001");

        assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(waiting.diagnosis().evidence()).containsExactly(
                "INSUFFICIENT_EVIDENCE: no knowledge documents matched the request");
        assertThat(fixture.parkSystem.findByWorkflowId(waiting.workflowId())).isEmpty();
    }

    @Test
    void lowDiagnosisConfidencePausesEvenWhenClassificationConfidenceIsHigh() {
        Fixture fixture = fixture(
                "ALT-TEMP-001",
                0.99,
                0.42,
                "LOW",
                null,
                sequentialIds());

        WorkflowSnapshot waiting = fixture.workflow.start("ALT-TEMP-001");

        assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(waiting.diagnosis().confidence()).isEqualTo(0.42);
        assertThat(fixture.parkSystem.findByWorkflowId(waiting.workflowId())).isEmpty();
    }

    @Test
    void portFailureIsCheckpointedAndOnlyExposesAStableSafeError() {
        Fixture fixture = fixture("ALT-TEMP-001", 0.92, "LOW", null, sequentialIds());

        WorkflowSnapshot failed = fixture.workflow.start("ALT-MISSING");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("ALERT_LOOKUP_FAILED: Unable to load alert");
        assertThat(checkpointState(fixture, failed.workflowId()).status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(checkpointState(fixture, failed.workflowId()).errors()).isEqualTo(failed.errors());
        assertThat(fixture.store.execution(failed.workflowId()).orElseThrow().failureCause().orElseThrow())
                .hasMessageContaining("Unknown alert");
        assertThat(failed.workOrder()).isNull();
        assertThat(fixture.parkSystem.findByWorkflowId(failed.workflowId())).isEmpty();
    }

    @Test
    void duplicateStartForWaitingAlertReturnsTheExistingWorkflow() {
        Fixture fixture = fixture("ALT-POWER-001", 0.96, "HIGH", null, sequentialIds());

        WorkflowSnapshot first = fixture.workflow.start("ALT-POWER-001");
        WorkflowSnapshot duplicate = fixture.workflow.start("ALT-POWER-001");

        assertThat(duplicate).isEqualTo(first);
        assertThat(fixture.store.findRunningByAlertId("ALT-POWER-001"))
                .contains(first);
    }

    @Test
    void duplicateApprovalWithTheSameIdempotencyKeyReturnsTheRecordedResultWithoutSideEffects() {
        Fixture fixture = fixture("ALT-POWER-001", 0.96, "HIGH", null, sequentialIds());
        WorkflowSnapshot waiting = fixture.workflow.start("ALT-POWER-001");
        ApprovalDecision firstDecision = approvedAt("approval-duplicate-1", "2026-08-23T02:00:00Z");
        ApprovalDecision retryDecision = approvedAt("approval-duplicate-1", "2026-08-23T02:02:00Z");

        WorkflowSnapshot completed = fixture.workflow.approve(waiting.workflowId(), firstDecision);
        WorkflowSnapshot duplicate = fixture.workflow.approve(waiting.workflowId(), retryDecision);

        assertThat(duplicate).isEqualTo(completed);
        assertThat(duplicate.approval()).contains(firstDecision);
        assertThat(fixture.parkSystem.findByWorkflowId(waiting.workflowId())).hasSize(1);
    }

    @Test
    void reusingAnIdempotencyKeyWithDifferentApprovalContentIsRejected() {
        Fixture fixture = fixture("ALT-POWER-001", 0.96, "HIGH", null, sequentialIds());
        WorkflowSnapshot waiting = fixture.workflow.start("ALT-POWER-001");
        fixture.workflow.approve(
                waiting.workflowId(),
                approvedAt("approval-conflict-1", "2026-08-23T02:00:00Z"));

        assertThatThrownBy(() -> fixture.workflow.approve(
                waiting.workflowId(),
                new ApprovalDecision(
                        ApprovalDecision.Decision.REJECTED,
                        "operator-1",
                        "changed decision",
                        "approval-conflict-1",
                        Instant.parse("2026-08-23T02:02:00Z"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void approvalRequiresANonBlankExplicitIdempotencyKey() {
        assertThatThrownBy(() -> new ApprovalDecision(
                ApprovalDecision.Decision.APPROVED,
                "operator-1",
                "safe to dispatch",
                "  ",
                Instant.parse("2026-08-23T02:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
    }

    @Test
    void eventsArePublishedInStrictSequenceAndContainAllLifecycleKinds() {
        Fixture fixture = fixture("ALT-TEMP-001", 0.92, "LOW", null, sequentialIds());
        WorkflowSnapshot result = fixture.workflow.start("ALT-TEMP-001");

        List<WorkflowEvent> events = fixture.publisher.events(result.workflowId())
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(events).isNotNull().isNotEmpty();
        assertThat(events).extracting(WorkflowEvent::sequence)
                .containsExactlyElementsOf(LongStream.rangeClosed(1, events.size()).boxed().toList());
        assertThat(events).extracting(WorkflowEvent::eventType)
                .contains(
                        WorkflowEvent.EventType.NODE_STARTED,
                        WorkflowEvent.EventType.NODE_COMPLETED,
                        WorkflowEvent.EventType.TOOL_CALLED,
                        WorkflowEvent.EventType.COMPLETED);
        assertThat(events).allSatisfy(event -> assertThat(event.redactedSummary()).doesNotContain("prompt", "apiKey"));
        assertThat(result.eventSequence()).isEqualTo(events.get(events.size() - 1).sequence());
    }

    @Test
    void modelFailureKeepsDetailedCauseInternalAndCheckpointsSafeError() {
        MockParkSystem parkSystem = new MockParkSystem();
        AlertDiagnosisAgent failingAgent = mock(AlertDiagnosisAgent.class);
        when(failingAgent.diagnose(any(), any(), anyList(), any()))
                .thenThrow(new IllegalStateException("model payload apiKey=secret-model-key"));
        Fixture fixture = fixture(parkSystem, parkSystem, failingAgent, sequentialIds());

        WorkflowSnapshot failed = fixture.workflow.start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("DIAGNOSIS_FAILED: Unable to diagnose alert");
        assertThat(failed.errors().toString()).doesNotContain("secret-model-key", "apiKey");
        assertThat(checkpointState(fixture, failed.workflowId()).errors()).isEqualTo(failed.errors());
        assertThat(fixture.store.execution(failed.workflowId()).orElseThrow().failureCause().orElseThrow())
                .hasMessageContaining("secret-model-key");
    }

    @Test
    void agentInitiatedToolCallsUseTheSameRedactedAuditEventStream() {
        MockParkSystem parkSystem = new MockParkSystem();
        AlertDiagnosisAgent auditingAgent = mock(AlertDiagnosisAgent.class);
        when(auditingAgent.diagnose(any(), any(), anyList(), any())).thenAnswer(invocation -> {
            Consumer<String> auditor = invocation.getArgument(3);
            auditor.accept("lookupDeviceStatus");
            return diagnosis("ALT-TEMP-001", "LOW", 0.92, false);
        });
        Fixture fixture = fixture(parkSystem, parkSystem, auditingAgent, sequentialIds());

        WorkflowSnapshot result = fixture.workflow.start("ALT-TEMP-001");
        List<WorkflowEvent> events = fixture.publisher.events(result.workflowId())
                .collectList()
                .block(Duration.ofSeconds(2));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.eventType()).isEqualTo(WorkflowEvent.EventType.TOOL_CALLED);
            assertThat(event.node()).isEqualTo(AlertWorkflowNodes.DIAGNOSE_ALERT);
            assertThat(event.redactedSummary()).isEqualTo("AgentTool.lookupDeviceStatus");
        });
    }

    @Test
    void existingWorkflowWorkOrderIsReusedWithoutCallingCreateAgain() {
        MockParkSystem parkSystem = new MockParkSystem();
        CountingWorkOrderPort workOrderPort = new CountingWorkOrderPort(parkSystem);
        WorkOrder existing = parkSystem.create("wf-fixed", "ALT-TEMP-001", "existing order");
        Fixture fixture = fixture(
                parkSystem,
                workOrderPort,
                "ALT-TEMP-001",
                0.92,
                0.92,
                "LOW",
                parkSystem,
                () -> "wf-fixed");

        WorkflowSnapshot result = fixture.workflow.start("ALT-TEMP-001");

        assertThat(result.workOrder().id()).isEqualTo(existing.id());
        assertThat(workOrderPort.createCalls()).isZero();
        assertThat(parkSystem.findByWorkflowId("wf-fixed")).containsExactly(existing);
    }

    private static ApprovalDecision approvedAt(String instant) {
        return approvedAt("approval-" + instant, instant);
    }

    private static ApprovalDecision approvedAt(String idempotencyKey, String instant) {
        return new ApprovalDecision(
                ApprovalDecision.Decision.APPROVED,
                "operator-1",
                "safe to dispatch",
                idempotencyKey,
                Instant.parse(instant));
    }

    private static AlertWorkflowState checkpointState(Fixture fixture, String workflowId) {
        WorkflowExecutionStore.Execution execution = fixture.store.execution(workflowId).orElseThrow();
        return AlertWorkflowState.from(execution.compiledGraph().getState(
                RunnableConfig.builder().threadId(execution.graphThreadId()).build()).state());
    }

    private static Fixture fixture(
            String alertId,
            double confidence,
            String riskLevel,
            KnowledgePort knowledgePort,
            Supplier<String> workflowIds) {
        return fixture(alertId, confidence, confidence, riskLevel, knowledgePort, workflowIds);
    }

    private static Fixture fixture(
            String alertId,
            double classificationConfidence,
            double diagnosisConfidence,
            String riskLevel,
            KnowledgePort knowledgePort,
            Supplier<String> workflowIds) {
        MockParkSystem parkSystem = new MockParkSystem();
        return fixture(
                parkSystem,
                parkSystem,
                alertId,
                classificationConfidence,
                diagnosisConfidence,
                riskLevel,
                knowledgePort == null ? parkSystem : knowledgePort,
                workflowIds);
    }

    private static Fixture fixture(
            MockParkSystem parkSystem,
            WorkOrderPort workOrderPort,
            String alertId,
            double classificationConfidence,
            double diagnosisConfidence,
            String riskLevel,
            KnowledgePort knowledgePort,
            Supplier<String> workflowIds) {
        TestChatModel triageModel = new TestChatModel(triageJson(alertId, classificationConfidence, riskLevel));
        String knowledgeQuery = alertId.contains("POWER") ? "power" : "temperature";
        TestChatModel diagnosisModel = new TestChatModel(
                diagnosisJson(
                        alertId,
                        riskLevel,
                        diagnosisConfidence,
                        knowledgePort.search(knowledgeQuery).isEmpty()));
        AlertTriageAgent triageAgent = new AlertTriageAgent(triageModel);
        AlertDiagnosisAgent diagnosisAgent = new AlertDiagnosisAgent(
                diagnosisModel,
                new DeviceQueryTool(parkSystem),
                new AlertQueryTool(parkSystem),
                new WorkOrderTool(workOrderPort),
                new ParkKnowledgeTool(knowledgePort));
        WorkflowExecutionStore store = WorkflowExecutionStore.inMemory();
        WorkflowEventPublisher publisher = WorkflowEventPublisher.inMemory();
        AlertWorkflow workflow = new AlertWorkflow(
                triageAgent,
                diagnosisAgent,
                parkSystem,
                parkSystem,
                workOrderPort,
                knowledgePort,
                store,
                publisher,
                CLOCK,
                workflowIds);
        return new Fixture(workflow, parkSystem, store, publisher);
    }

    private static Fixture fixture(
            MockParkSystem parkSystem,
            AlertPort alertPort,
            AlertDiagnosisAgent diagnosisAgent,
            Supplier<String> workflowIds) {
        AlertTriageAgent triageAgent = new AlertTriageAgent(
                new TestChatModel(triageJson("ALT-TEMP-001", 0.99, "LOW")));
        WorkflowExecutionStore store = WorkflowExecutionStore.inMemory();
        WorkflowEventPublisher publisher = WorkflowEventPublisher.inMemory();
        AlertWorkflow workflow = new AlertWorkflow(
                triageAgent,
                diagnosisAgent,
                parkSystem,
                alertPort,
                parkSystem,
                parkSystem,
                store,
                publisher,
                CLOCK,
                workflowIds);
        return new Fixture(workflow, parkSystem, store, publisher);
    }

    private static Supplier<String> sequentialIds() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "wf-" + sequence.incrementAndGet();
    }

    private static String triageJson(String alertId, double confidence, String riskLevel) {
        String category = alertId.contains("POWER") ? "POWER" : "TEMPERATURE";
        String priority = "HIGH".equals(riskLevel) ? "HIGH" : "MEDIUM";
        return """
                {"category":"%s","priority":"%s","riskLevel":"%s","confidence":%s}
                """.formatted(category, priority, riskLevel, confidence);
    }

    private static String diagnosisJson(
            String alertId,
            String riskLevel,
            double confidence,
            boolean insufficientEvidence) {
        String deviceId = alertId.contains("POWER") ? "DEV-POWER-001" : "DEV-HVAC-001";
        String evidence = insufficientEvidence
                ? "INSUFFICIENT_EVIDENCE: no knowledge documents matched the request"
                : "knowledge: matching playbook and device history";
        return """
                {
                  "id":"diag-%s",
                  "alertId":"%s",
                  "deviceId":"%s",
                  "riskLevel":"%s",
                  "rootCause":"fixture root cause",
                  "summary":"fixture diagnosis summary",
                  "evidence":["%s"],
                  "recommendedAction":"inspect the device",
                  "confidence":%s,
                  "diagnosedAt":"2026-08-23T01:30:00Z"
                }
                """.formatted(alertId, alertId, deviceId, riskLevel, evidence, confidence);
    }

    private static Diagnosis diagnosis(
            String alertId,
            String riskLevel,
            double confidence,
            boolean insufficientEvidence) {
        String deviceId = alertId.contains("POWER") ? "DEV-POWER-001" : "DEV-HVAC-001";
        String evidence = insufficientEvidence
                ? "INSUFFICIENT_EVIDENCE: no knowledge documents matched the request"
                : "knowledge: matching playbook and device history";
        return new Diagnosis(
                "diag-" + alertId,
                alertId,
                deviceId,
                com.example.smartpark.model.common.RiskLevel.valueOf(riskLevel),
                "fixture root cause",
                "fixture diagnosis summary",
                List.of(evidence),
                "inspect the device",
                confidence,
                Instant.parse("2026-08-23T01:30:00Z"));
    }

    private record Fixture(
            AlertWorkflow workflow,
            MockParkSystem parkSystem,
            WorkflowExecutionStore store,
            WorkflowEventPublisher publisher) {
    }

    private static final class CountingWorkOrderPort implements WorkOrderPort {
        private final WorkOrderPort delegate;
        private final AtomicInteger createCalls = new AtomicInteger();

        private CountingWorkOrderPort(WorkOrderPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<WorkOrder> findByWorkflowId(String workflowId) {
            return delegate.findByWorkflowId(workflowId);
        }

        @Override
        public WorkOrder create(String workflowId, String alertId, String summary) {
            createCalls.incrementAndGet();
            return delegate.create(workflowId, alertId, summary);
        }

        private int createCalls() {
            return createCalls.get();
        }
    }
}
