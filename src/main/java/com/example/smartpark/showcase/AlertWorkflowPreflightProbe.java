package com.example.smartpark.showcase;

import com.example.smartpark.model.common.WorkflowStatus;
import com.example.smartpark.workflow.WorkflowSnapshot;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertWorkflowPreflightProbe implements ShowcasePreflightProbe {

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
        boolean passed = snapshot.status() == WorkflowStatus.WAITING_APPROVAL
                && snapshot.diagnosis() != null
                && snapshot.errors().isEmpty()
                && snapshot.workOrder() == null;
        return passed ? ShowcaseProbeResult.PASSED : ShowcaseProbeResult.FAILED;
    }
}
