package com.example.smartpark.showcase;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.AlertWorkflowState;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertWorkflowPreflightProbe implements ShowcasePreflightProbe {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkflowPreflightProbe.class);
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
        WorkflowSnapshot snapshot = factory.create().start(
                ShowcaseLaunchInput.forScenario(scenarioId()).alertId());
        boolean waitingApproval = snapshot.status() == WorkflowStatus.WAITING_APPROVAL;
        boolean diagnosisPresent = snapshot.diagnosis() != null;
        boolean errorsEmpty = snapshot.errors().isEmpty();
        boolean workOrderAbsent = snapshot.workOrder() == null;
        boolean approvalAbsent = snapshot.approval().isEmpty();
        Object knowledgeValue = snapshot.statePayload().get(AlertWorkflowState.RETRIEVED_DOCUMENTS);
        int knowledgeCount = knowledgeValue instanceof java.util.List<?> documents ? documents.size() : 0;
        boolean knowledgePresent = knowledgeCount > 0;
        if (waitingApproval && diagnosisPresent && knowledgePresent && errorsEmpty
                && workOrderAbsent && approvalAbsent) {
            return ShowcaseProbeResult.PASSED;
        }
        log.warn("alert preflight failed: stage={}, code={}, waitingApproval={}, diagnosisPresent={}, "
                        + "knowledgePresent={}, knowledgeCount={}, errorsEmpty={}, workOrderAbsent={}, approvalAbsent={}",
                FailureStage.WORKFLOW_INVARIANT,
                FailureCode.INVARIANT_MISMATCH,
                waitingApproval,
                diagnosisPresent,
                knowledgePresent,
                knowledgeCount,
                errorsEmpty,
                workOrderAbsent,
                approvalAbsent);
        return ShowcaseProbeResult.FAILED;
    }

    private enum FailureStage {
        WORKFLOW_INVARIANT
    }

    private enum FailureCode {
        INVARIANT_MISMATCH
    }
}
