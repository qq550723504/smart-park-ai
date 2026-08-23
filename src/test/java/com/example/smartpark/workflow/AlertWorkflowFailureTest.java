package com.example.smartpark.workflow;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.ApprovalDecision;
import com.example.smartpark.model.WorkOrder;
import com.example.smartpark.model.WorkflowStatus;
import com.example.smartpark.park.AlertPort;
import com.example.smartpark.park.DevicePort;
import com.example.smartpark.park.KnowledgePort;
import com.example.smartpark.park.WorkOrderPort;
import com.example.smartpark.park.mock.MockParkSystem;
import com.example.smartpark.tool.AlertQueryTool;
import com.example.smartpark.tool.DeviceQueryTool;
import com.example.smartpark.tool.ParkKnowledgeTool;
import com.example.smartpark.tool.WorkOrderTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertWorkflowFailureTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-23T03:00:00Z"),
            ZoneOffset.UTC);

    @Test
    void fixedFailingChatModelPreservesCompletedEvidenceAndKeepsProviderDetailsInternal() {
        MockParkSystem park = new MockParkSystem();
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                new FailingChatModel("providerResponse=raw-model-payload Authorization: Bearer model-token"),
                park,
                park,
                park,
                park,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("DIAGNOSIS_FAILED: Unable to diagnose alert");
        assertThat(failed.errors().toString())
                .doesNotContain("raw-model-payload", "model-token", "providerResponse", "Authorization");
        assertThat(failed.statePayload()).containsKeys(
                AlertWorkflowState.ALERT,
                AlertWorkflowState.CLASSIFICATION,
                AlertWorkflowState.PARK_CONTEXT,
                AlertWorkflowState.RETRIEVED_DOCUMENTS);
        assertThat(failed.diagnosis()).isNull();
        assertThat(failed.workOrder()).isNull();
        assertThat(fixture.store().execution(failed.workflowId()).orElseThrow().failureCause().orElseThrow())
                .hasMessageContaining("raw-model-payload");
    }

    @Test
    void malformedStructuredOutputFailsClosedWithoutInventingWorkflowEvidence() {
        MockParkSystem park = new MockParkSystem();
        Fixture fixture = fixture(
                new TestChatModel("not-json providerResponse=raw-output"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park,
                park,
                park,
                park,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("CLASSIFICATION_FAILED: Unable to classify alert");
        assertThat(failed.errors().toString()).doesNotContain("raw-output", "providerResponse");
        assertThat(failed.statePayload())
                .containsEntry(AlertWorkflowState.CLASSIFICATION, null)
                .containsEntry(AlertWorkflowState.PARK_CONTEXT, null)
                .containsEntry(AlertWorkflowState.DIAGNOSIS, null)
                .containsEntry(AlertWorkflowState.WORK_ORDER, null);
        assertThat(failed.workOrder()).isNull();
    }

    @Test
    void deviceLookupFailurePreservesAlertAndClassificationButStopsBeforeDiagnosis() {
        MockParkSystem park = new MockParkSystem();
        DevicePort failingDevice = ignored -> {
            throw new IllegalStateException("device header Authorization=private-device-header");
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                failingDevice,
                park,
                park,
                park,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("PARK_CONTEXT_FAILED: Unable to collect park context");
        assertThat(failed.errors().toString()).doesNotContain("private-device-header", "Authorization");
        assertThat(failed.statePayload())
                .containsKeys(AlertWorkflowState.ALERT, AlertWorkflowState.CLASSIFICATION)
                .containsEntry(AlertWorkflowState.PARK_CONTEXT, null)
                .containsEntry(AlertWorkflowState.DIAGNOSIS, null);
        assertThat(failed.workOrder()).isNull();
    }

    @Test
    void alertHistoryFailureStopsContextCollectionWithAStableSafeError() {
        MockParkSystem park = new MockParkSystem();
        AlertPort failingHistory = new AlertPort() {
            @Override
            public com.example.smartpark.model.Alert getAlert(String alertId) {
                return park.getAlert(alertId);
            }

            @Override
            public List<com.example.smartpark.model.Alert> findHistory(String deviceId) {
                throw new IllegalStateException("alert service token=private-alert-token");
            }
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park,
                failingHistory,
                park,
                park,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("PARK_CONTEXT_FAILED: Unable to collect park context");
        assertThat(failed.errors().toString()).doesNotContain("private-alert-token", "token=");
        assertThat(failed.workOrder()).isNull();
    }

    @Test
    void knowledgeFailurePreservesCollectedParkContextAndStopsBeforeDiagnosis() {
        MockParkSystem park = new MockParkSystem();
        KnowledgePort failingKnowledge = ignored -> {
            throw new IllegalStateException("knowledge providerResponse=private-knowledge-response");
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park,
                park,
                park,
                failingKnowledge,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly(
                "KNOWLEDGE_RETRIEVAL_FAILED: Unable to retrieve park knowledge");
        assertThat(failed.errors().toString()).doesNotContain("private-knowledge-response", "providerResponse");
        assertThat(failed.statePayload())
                .containsKeys(
                        AlertWorkflowState.ALERT,
                        AlertWorkflowState.CLASSIFICATION,
                        AlertWorkflowState.PARK_CONTEXT)
                .containsEntry(AlertWorkflowState.DIAGNOSIS, null);
        assertThat(failed.workOrder()).isNull();
    }

    @Test
    void workOrderCreationFailureUsesSpecificStatusAndNeverFabricatesAnId() {
        MockParkSystem park = new MockParkSystem();
        AtomicInteger createCalls = new AtomicInteger();
        WorkOrderPort failingWorkOrders = new WorkOrderPort() {
            @Override
            public List<WorkOrder> findByWorkflowId(String workflowId) {
                return List.of();
            }

            @Override
            public WorkOrder create(String workflowId, String alertId, String summary) {
                createCalls.incrementAndGet();
                throw new IllegalStateException("ticket response apiKey=private-ticket-key");
            }
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park,
                park,
                failingWorkOrders,
                park,
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.WORK_ORDER_FAILED);
        assertThat(failed.errors()).containsExactly("WORK_ORDER_FAILED: Unable to create work order");
        assertThat(failed.errors().toString()).doesNotContain("private-ticket-key", "apiKey");
        assertThat(failed.diagnosis()).isNotNull();
        assertThat(failed.workOrder()).isNull();
        assertThat(failed.statePayload()).containsEntry(AlertWorkflowState.WORK_ORDER, null);
        assertThat(createCalls).hasValue(1);
    }

    @Test
    void duplicateStartReturnsTheWaitingExecutionWithoutStartingAnotherEventStream() {
        MockParkSystem park = new MockParkSystem();
        Fixture fixture = fixture(
                validTriageModel("ALT-POWER-001", "HIGH"),
                validDiagnosisModel("ALT-POWER-001", "HIGH"),
                park,
                park,
                park,
                park,
                sequentialIds());

        WorkflowSnapshot first = fixture.workflow().start("ALT-POWER-001");
        WorkflowSnapshot duplicate = fixture.workflow().start("ALT-POWER-001");

        assertThat(duplicate).isEqualTo(first);
        List<WorkflowEvent> events = fixture.publisher().events(first.workflowId())
                .take(first.eventSequence())
                .collectList()
                .block(Duration.ofSeconds(2));
        assertThat(events).extracting(WorkflowEvent::eventType)
                .containsOnlyOnce(WorkflowEvent.EventType.STARTED);
    }

    @Test
    void duplicateApprovalReturnsRecordedResultAndConflictingReuseIsRejected() {
        MockParkSystem park = new MockParkSystem();
        Fixture fixture = fixture(
                validTriageModel("ALT-POWER-001", "HIGH"),
                validDiagnosisModel("ALT-POWER-001", "HIGH"),
                park,
                park,
                park,
                park,
                sequentialIds());
        WorkflowSnapshot waiting = fixture.workflow().start("ALT-POWER-001");
        ApprovalDecision first = approval(
                ApprovalDecision.Decision.APPROVED,
                "approval-task-6",
                "safe to dispatch",
                "2026-08-23T03:01:00Z");

        WorkflowSnapshot completed = fixture.workflow().approve(waiting.workflowId(), first);
        WorkflowSnapshot duplicate = fixture.workflow().approve(
                waiting.workflowId(),
                approval(
                        ApprovalDecision.Decision.APPROVED,
                        "approval-task-6",
                        "safe to dispatch",
                        "2026-08-23T03:02:00Z"));

        assertThat(duplicate).isEqualTo(completed);
        assertThat(park.findByWorkflowId(waiting.workflowId())).hasSize(1);
        assertThatThrownBy(() -> fixture.workflow().approve(
                waiting.workflowId(),
                approval(
                        ApprovalDecision.Decision.REJECTED,
                        "approval-task-6",
                        "changed decision",
                        "2026-08-23T03:03:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThat(park.findByWorkflowId(waiting.workflowId())).hasSize(1);
    }

    private static Fixture fixture(
            ChatModel triageModel,
            ChatModel diagnosisModel,
            DevicePort devicePort,
            AlertPort alertPort,
            WorkOrderPort workOrderPort,
            KnowledgePort knowledgePort,
            Supplier<String> workflowIds) {
        AlertTriageAgent triageAgent = new AlertTriageAgent(triageModel);
        AlertDiagnosisAgent diagnosisAgent = new AlertDiagnosisAgent(
                diagnosisModel,
                new DeviceQueryTool(devicePort),
                new AlertQueryTool(alertPort),
                new WorkOrderTool(workOrderPort),
                new ParkKnowledgeTool(knowledgePort));
        WorkflowExecutionStore store = WorkflowExecutionStore.inMemory();
        WorkflowEventPublisher publisher = WorkflowEventPublisher.inMemory();
        AlertWorkflow workflow = new AlertWorkflow(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                workOrderPort,
                knowledgePort,
                store,
                publisher,
                CLOCK,
                workflowIds);
        return new Fixture(workflow, store, publisher);
    }

    private static TestChatModel validTriageModel(String alertId, String riskLevel) {
        String category = alertId.contains("POWER") ? "POWER" : "TEMPERATURE";
        String priority = "HIGH".equals(riskLevel) ? "HIGH" : "MEDIUM";
        return new TestChatModel("""
                {"category":"%s","priority":"%s","riskLevel":"%s","confidence":0.95}
                """.formatted(category, priority, riskLevel));
    }

    private static TestChatModel validDiagnosisModel(String alertId, String riskLevel) {
        String deviceId = alertId.contains("POWER") ? "DEV-POWER-001" : "DEV-HVAC-001";
        return new TestChatModel("""
                {
                  "id":"diag-%s",
                  "alertId":"%s",
                  "deviceId":"%s",
                  "riskLevel":"%s",
                  "rootCause":"fixture root cause",
                  "summary":"fixture diagnosis summary",
                  "evidence":["knowledge: matching playbook and device history"],
                  "recommendedAction":"inspect the device",
                  "confidence":0.95,
                  "diagnosedAt":"2026-08-23T02:59:00Z"
                }
                """.formatted(alertId, alertId, deviceId, riskLevel));
    }

    private static ApprovalDecision approval(
            ApprovalDecision.Decision decision,
            String idempotencyKey,
            String comment,
            String decidedAt) {
        return new ApprovalDecision(
                decision,
                "operator-task-6",
                comment,
                idempotencyKey,
                Instant.parse(decidedAt));
    }

    private static Supplier<String> sequentialIds() {
        AtomicInteger sequence = new AtomicInteger();
        return () -> "wf-task-6-" + sequence.incrementAndGet();
    }

    private record Fixture(
            AlertWorkflow workflow,
            WorkflowExecutionStore store,
            WorkflowEventPublisher publisher) {
    }

    private static final class FailingChatModel implements ChatModel {
        private final RuntimeException failure;

        private FailingChatModel(String message) {
            this.failure = new IllegalStateException(message);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            throw failure;
        }
    }
}
