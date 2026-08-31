package com.example.smartpark.showcase;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertWorkflowPreflightProbe implements ShowcasePreflightProbe {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkflowPreflightProbe.class);
    private static final String ALERT_ID = "ALT-POWER-001";

    private final AlertPreflightWorkflowFactory factory;

    public AlertWorkflowPreflightProbe(AlertPreflightWorkflowFactory factory) {
        this.factory = factory;
    }

    @Override
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.ALERT_WORKFLOW;
    }

    @Override
    public ShowcaseProbeResult probe() {
        WorkflowSnapshot snapshot = factory.create().start(ALERT_ID);
        boolean waitingApproval = snapshot.status() == WorkflowStatus.WAITING_APPROVAL;
        boolean diagnosisPresent = snapshot.diagnosis() != null;
        boolean errorsEmpty = snapshot.errors().isEmpty();
        boolean workOrderAbsent = snapshot.workOrder() == null;
        boolean approvalAbsent = snapshot.approval().isEmpty();
        if (waitingApproval && diagnosisPresent && errorsEmpty && workOrderAbsent && approvalAbsent) {
            return ShowcaseProbeResult.PASSED;
        }
        log.warn("alert preflight failed: stage={}, code={}, waitingApproval={}, diagnosisPresent={}, "
                        + "errorsEmpty={}, workOrderAbsent={}, approvalAbsent={}",
                FailureStage.APPROVAL_BOUNDARY,
                FailureCode.INVARIANT_MISMATCH,
                waitingApproval,
                diagnosisPresent,
                errorsEmpty,
                workOrderAbsent,
                approvalAbsent);
        return ShowcaseProbeResult.FAILED;
    }

    private enum FailureStage {
        APPROVAL_BOUNDARY
    }

    private enum FailureCode {
        INVARIANT_MISMATCH
    }
}
