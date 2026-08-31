package com.example.smartpark.showcase;

import com.example.smartpark.model.common.Diagnosis;
import com.example.smartpark.model.common.WorkOrder;
import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertWorkflowPreflightProbeTest {

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
