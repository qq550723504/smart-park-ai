package com.example.smartpark.workflow;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.model.common.ApprovalDecision;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        MockParkFixture park = new MockParkFixture();
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                new FailingChatModel("providerResponse=raw-model-payload Authorization: "
                        + "Bear" + "er model-token"),
                park.devices(),
                park.alerts(),
                park.workOrders(),
                park.knowledge(),
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
        MockParkFixture park = new MockParkFixture();
        Fixture fixture = fixture(
                new TestChatModel("not-json providerResponse=raw-output"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park.devices(),
                park.alerts(),
                park.workOrders(),
                park.knowledge(),
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
        MockParkFixture park = new MockParkFixture();
        DevicePort failingDevice = ignored -> {
            throw new IllegalStateException("device header Authorization=private-device-header");
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                failingDevice,
                park.alerts(),
                park.workOrders(),
                park.knowledge(),
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
        MockParkFixture park = new MockParkFixture();
        AlertPort failingHistory = new AlertPort() {
            @Override
            public com.example.smartpark.model.alert.Alert getAlert(String alertId) {
                return park.alerts().getAlert(alertId);
            }

            @Override
            public List<com.example.smartpark.model.alert.Alert> findHistory(String deviceId) {
                throw new IllegalStateException("alert service token=private-alert-token");
            }
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park.devices(),
                failingHistory,
                park.workOrders(),
                park.knowledge(),
                sequentialIds());

        WorkflowSnapshot failed = fixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(failed.errors()).containsExactly("PARK_CONTEXT_FAILED: Unable to collect park context");
        assertThat(failed.errors().toString()).doesNotContain("private-alert-token", "token=");
        assertThat(failed.workOrder()).isNull();
    }

    @Test
    void knowledgeFailurePreservesCollectedParkContextAndStopsBeforeDiagnosis() {
        MockParkFixture park = new MockParkFixture();
        KnowledgePort failingKnowledge = (domain, ignored) -> {
            throw new IllegalStateException("knowledge providerResponse=private-knowledge-response");
        };
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park.devices(),
                park.alerts(),
                park.workOrders(),
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
        MockParkFixture park = new MockParkFixture();
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
                park.devices(),
                park.alerts(),
                failingWorkOrders,
                park.knowledge(),
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
        MockParkFixture park = new MockParkFixture();
        Fixture fixture = fixture(
                validTriageModel("ALT-POWER-001", "HIGH"),
                validDiagnosisModel("ALT-POWER-001", "HIGH"),
                park.devices(),
                park.alerts(),
                park.workOrders(),
                park.knowledge(),
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
    void duplicateStartReturnsTheCompletedExecutionWithoutCreatingAnotherWorkOrder() {
        MockParkFixture park = new MockParkFixture();
        CountingWorkOrderPort workOrders = new CountingWorkOrderPort(park.workOrders());
        AtomicInteger generatedIds = new AtomicInteger();
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                park.devices(),
                park.alerts(),
                workOrders,
                park.knowledge(),
                () -> "wf-terminal-" + generatedIds.incrementAndGet());

        WorkflowSnapshot completed = fixture.workflow().start("ALT-TEMP-001");
        WorkflowSnapshot duplicate = fixture.workflow().start("ALT-TEMP-001");

        assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(duplicate).isEqualTo(completed);
        assertThat(workOrders.createCalls()).isEqualTo(1);
        assertThat(generatedIds).hasValue(1);
    }

    @Test
    void failedStartCanBeRetriedButRejectedTerminalExecutionRemainsIdempotent() {
        MockParkFixture failedPark = new MockParkFixture();
        AtomicInteger retryIds = new AtomicInteger();
        Fixture failedFixture = fixture(
                new TestChatModel(
                        "not-json",
                        "still-not-json",
                        "{\"category\":\"TEMPERATURE\",\"priority\":\"MEDIUM\",\"riskLevel\":\"LOW\",\"confidence\":0.95}"),
                validDiagnosisModel("ALT-TEMP-001", "LOW"),
                failedPark.devices(),
                failedPark.alerts(),
                failedPark.workOrders(),
                failedPark.knowledge(),
                () -> "wf-retry-" + retryIds.incrementAndGet());
        WorkflowSnapshot failed = failedFixture.workflow().start("ALT-TEMP-001");
        WorkflowSnapshot retried = failedFixture.workflow().start("ALT-TEMP-001");

        assertThat(failed.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(retried.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(retried.workflowId()).isNotEqualTo(failed.workflowId());
        assertThat(retryIds).hasValue(2);

        MockParkFixture rejectedPark = new MockParkFixture();
        AtomicInteger rejectedIds = new AtomicInteger();
        Fixture rejectedFixture = fixture(
                validTriageModel("ALT-POWER-001", "HIGH"),
                validDiagnosisModel("ALT-POWER-001", "HIGH"),
                rejectedPark.devices(),
                rejectedPark.alerts(),
                rejectedPark.workOrders(),
                rejectedPark.knowledge(),
                () -> "wf-rejected-" + rejectedIds.incrementAndGet());
        WorkflowSnapshot waiting = rejectedFixture.workflow().start("ALT-POWER-001");
        WorkflowSnapshot rejected = rejectedFixture.workflow().approve(
                waiting.workflowId(),
                approval(
                        ApprovalDecision.Decision.REJECTED,
                        "reject-terminal",
                        "do not dispatch",
                        "2026-08-23T03:04:00Z"));

        assertThat(rejected.status()).isEqualTo(WorkflowStatus.REJECTED);
        assertThat(rejectedFixture.workflow().start("ALT-POWER-001")).isEqualTo(rejected);
        assertThat(rejectedPark.workOrders().findByWorkflowId(rejected.workflowId())).isEmpty();
        assertThat(rejectedIds).hasValue(1);
    }

    @Test
    void concurrentDuplicateStartReusesTheRegisteredExecutionAndCreatesOneWorkOrder() throws Exception {
        MockParkFixture park = new MockParkFixture();
        CountingWorkOrderPort workOrders = new CountingWorkOrderPort(park.workOrders());
        BlockingChatModel diagnosisModel = new BlockingChatModel(
                validDiagnosisModel("ALT-TEMP-001", "LOW"));
        AtomicInteger generatedIds = new AtomicInteger();
        Fixture fixture = fixture(
                validTriageModel("ALT-TEMP-001", "LOW"),
                diagnosisModel,
                park.devices(),
                park.alerts(),
                workOrders,
                park.knowledge(),
                () -> "wf-concurrent-" + generatedIds.incrementAndGet());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<WorkflowSnapshot> firstStart = executor.submit(
                    () -> fixture.workflow().start("ALT-TEMP-001"));
            assertThat(diagnosisModel.awaitCall()).isTrue();

            WorkflowSnapshot concurrent = fixture.workflow().start("ALT-TEMP-001");
            diagnosisModel.release();
            WorkflowSnapshot completed = firstStart.get(5, TimeUnit.SECONDS);

            assertThat(concurrent.workflowId()).isEqualTo(completed.workflowId());
            assertThat(fixture.workflow().start("ALT-TEMP-001")).isEqualTo(completed);
            assertThat(workOrders.createCalls()).isEqualTo(1);
            assertThat(generatedIds).hasValue(1);
        }
        finally {
            diagnosisModel.release();
            executor.shutdownNow();
        }
    }

    @Test
    void duplicateApprovalReturnsRecordedResultAndConflictingReuseIsRejected() {
        MockParkFixture park = new MockParkFixture();
        Fixture fixture = fixture(
                validTriageModel("ALT-POWER-001", "HIGH"),
                validDiagnosisModel("ALT-POWER-001", "HIGH"),
                park.devices(),
                park.alerts(),
                park.workOrders(),
                park.knowledge(),
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
        assertThat(park.workOrders().findByWorkflowId(waiting.workflowId())).hasSize(1);
        assertThatThrownBy(() -> fixture.workflow().approve(
                waiting.workflowId(),
                approval(
                        ApprovalDecision.Decision.REJECTED,
                        "approval-task-6",
                        "changed decision",
                        "2026-08-23T03:03:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThat(park.workOrders().findByWorkflowId(waiting.workflowId())).hasSize(1);
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
        return new TestChatModel("""
                {
                  "riskLevel":"%s",
                  "rootCause":"fixture root cause",
                  "summary":"fixture diagnosis summary",
                  "evidence":["knowledge: matching playbook and device history"],
                  "recommendedAction":"inspect the device",
                  "confidence":0.95
                }
                """.formatted(riskLevel));
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

    private static final class BlockingChatModel implements ChatModel {
        private final ChatModel delegate;
        private final CountDownLatch called = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        private BlockingChatModel(ChatModel delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            called.countDown();
            try {
                if (!released.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to release fixed model");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting to release fixed model", exception);
            }
            return delegate.call(prompt);
        }

        private boolean awaitCall() throws InterruptedException {
            return called.await(5, TimeUnit.SECONDS);
        }

        private void release() {
            released.countDown();
        }
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
