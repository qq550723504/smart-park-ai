package com.example.smartpark.showcase;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertPreflightWorkflowFactory {

    private final AlertTriageAgent triageAgent;
    private final AlertDiagnosisAgent diagnosisAgent;
    private final DevicePort devicePort;
    private final AlertPort alertPort;
    private final KnowledgePort knowledgePort;
    private final EnergyPort energyPort;
    private final SecurityPort securityPort;

    public AlertPreflightWorkflowFactory(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            KnowledgePort knowledgePort,
            EnergyPort energyPort,
            SecurityPort securityPort) {
        this.triageAgent = triageAgent;
        this.diagnosisAgent = diagnosisAgent;
        this.devicePort = devicePort;
        this.alertPort = alertPort;
        this.knowledgePort = knowledgePort;
        this.energyPort = energyPort;
        this.securityPort = securityPort;
    }

    public AlertWorkflow create() {
        return new AlertWorkflow(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                new RejectingPreflightWorkOrderPort(),
                knowledgePort,
                WorkflowExecutionStore.inMemory(),
                WorkflowEventPublisher.inMemory(),
                energyPort,
                securityPort,
                new AlertPreflightBoundaryObserver());
    }
}
