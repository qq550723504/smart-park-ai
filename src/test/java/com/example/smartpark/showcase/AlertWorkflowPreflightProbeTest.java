package com.example.smartpark.showcase;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.smartpark.adapter.mock.MockParkFixture;
import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.agent.TestChatModel;
import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.RiskLevel;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.tool.alert.AlertQueryTool;
import com.example.smartpark.tool.device.DeviceQueryTool;
import com.example.smartpark.tool.knowledge.ParkKnowledgeTool;
import com.example.smartpark.tool.workorder.WorkOrderTool;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertWorkflowPreflightProbeTest {

    @Test
    void providerShapedPreflightStopsReadOnlyAtWaitingApproval() {
        MockParkFixture park = new MockParkFixture();
        AlertTriageAgent triageAgent = new AlertTriageAgent(new TestChatModel("""
                {"category":"POWER","priority":"HIGH","riskLevel":"HIGH","confidence":0.96}
                """));
        AlertDiagnosisAgent diagnosisAgent = new AlertDiagnosisAgent(
                new TestChatModel("""
                        {
                          "riskLevel":"HIGH",
                          "rootCause":"The main panel has repeated power-quality warnings.",
                          "summary":"Inspect the main panel before any automated remediation.",
                          "evidence":["history: repeated main-panel alerts"],
                          "recommendedAction":"Have an operator inspect the main panel.",
                          "confidence":0.91
                        }
                        """),
                new DeviceQueryTool(park.devices()),
                new AlertQueryTool(park.alerts()),
                new WorkOrderTool(park.workOrders()),
                new ParkKnowledgeTool(park.knowledge()));
        AlertPreflightWorkflowFactory factory = new AlertPreflightWorkflowFactory(
                triageAgent,
                diagnosisAgent,
                park.devices(),
                park.alerts(),
                park.knowledge(),
                park.energy(),
                park.security());

        WorkflowSnapshot snapshot = factory.create().start("ALT-POWER-001");

        assertThat(snapshot.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
        assertThat(snapshot.diagnosis()).isNotNull();
        assertThat(snapshot.errors()).isEmpty();
        assertThat(snapshot.workOrder()).isNull();
        assertThat(snapshot.approval()).isEmpty();
        assertThat(park.workOrders().findByWorkflowId(snapshot.workflowId())).isEmpty();
    }

    @Test
    void passesOnlyAtTheHumanApprovalBoundary() {
        AlertPreflightWorkflowFactory factory = mock(AlertPreflightWorkflowFactory.class);
        AlertWorkflow workflow = mock(AlertWorkflow.class);
        Diagnosis diagnosis = mock(Diagnosis.class);
        when(factory.create()).thenReturn(workflow);
        when(workflow.start("ALT-POWER-001")).thenReturn(new WorkflowSnapshot(
                "preflight-wf",
                "ALT-POWER-001",
                WorkflowStatus.WAITING_APPROVAL,
                Map.of(),
                diagnosis,
                Optional.empty(),
                null,
                List.of(),
                1));

        AlertWorkflowPreflightProbe probe = new AlertWorkflowPreflightProbe(factory);

        assertThat(probe.scenarioId()).isEqualTo(ShowcaseScenarioId.ALERT_WORKFLOW);
        assertThat(probe.probe()).isEqualTo(ShowcaseProbeResult.PASSED);
        verify(workflow).start("ALT-POWER-001");
    }

    @Test
    void rejectsEverySnapshotOutsideTheNoWriteApprovalBoundary() {
        Diagnosis diagnosis = mock(Diagnosis.class);
        WorkOrder workOrder = mock(WorkOrder.class);

        assertThat(runWith(snapshot(WorkflowStatus.COMPLETED, diagnosis, List.of(), null)))
                .isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(runWith(snapshot(WorkflowStatus.WAITING_APPROVAL, null, List.of(), null)))
                .isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(runWith(snapshot(
                WorkflowStatus.WAITING_APPROVAL,
                diagnosis,
                List.of("safe public error"),
                null)))
                .isEqualTo(ShowcaseProbeResult.FAILED);
        assertThat(runWith(snapshot(WorkflowStatus.WAITING_APPROVAL, diagnosis, List.of(), workOrder)))
                .isEqualTo(ShowcaseProbeResult.FAILED);
    }

    @Test
    void returnedFailureLogsOnlySanitizedStageCodeAndInvariantFlags() {
        Logger logger = (Logger) LoggerFactory.getLogger(AlertWorkflowPreflightProbe.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        Diagnosis sensitiveDiagnosis = new Diagnosis(
                "raw-model-secret",
                "prompt-secret",
                "tool-argument-secret",
                RiskLevel.HIGH,
                "exception-message-secret",
                "tool-result-secret",
                List.of("evidence-secret"),
                "provider-secret",
                0.9,
                Instant.parse("2026-08-31T00:00:00Z"));
        try {
            ShowcaseProbeResult result = runWith(snapshot(
                    WorkflowStatus.WAITING_APPROVAL,
                    sensitiveDiagnosis,
                    List.of("exception-message-secret"),
                    null));

            assertThat(result).isEqualTo(ShowcaseProbeResult.FAILED);
            assertThat(appender.list).hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).isEqualTo("alert preflight failed: stage=APPROVAL_BOUNDARY, "
                    + "code=INVARIANT_MISMATCH, waitingApproval=true, diagnosisPresent=true, "
                    + "errorsEmpty=false, workOrderAbsent=true");
            assertThat(message).doesNotContain(
                    "raw-model-secret",
                    "prompt-secret",
                    "tool-argument-secret",
                    "tool-result-secret",
                    "evidence-secret",
                    "provider-secret",
                    "exception-message-secret");
        }
        finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void factoryAndProbeShareTheAlertShowcaseCondition() {
        assertThat(AlertPreflightWorkflowFactory.class.getAnnotation(Conditional.class).value())
                .containsExactly(AlertShowcaseCondition.class);
        assertThat(AlertWorkflowPreflightProbe.class.getAnnotation(Conditional.class).value())
                .containsExactly(AlertShowcaseCondition.class);
    }

    private ShowcaseProbeResult runWith(WorkflowSnapshot snapshot) {
        AlertPreflightWorkflowFactory factory = mock(AlertPreflightWorkflowFactory.class);
        AlertWorkflow workflow = mock(AlertWorkflow.class);
        when(factory.create()).thenReturn(workflow);
        when(workflow.start("ALT-POWER-001")).thenReturn(snapshot);
        return new AlertWorkflowPreflightProbe(factory).probe();
    }

    private WorkflowSnapshot snapshot(
            WorkflowStatus status,
            Diagnosis diagnosis,
            List<String> errors,
            WorkOrder workOrder) {
        return new WorkflowSnapshot(
                "preflight-wf",
                "ALT-POWER-001",
                status,
                Map.of(),
                diagnosis,
                Optional.empty(),
                workOrder,
                errors,
                1);
    }
}
