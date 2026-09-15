package com.example.smartpark.showcase;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityEventCatalog;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.security.SecuritySourceAdapter;
import com.example.smartpark.support.SecurityEventReaders;
import com.example.smartpark.workflow.AlertWorkflow;
import com.example.smartpark.workflow.WorkflowEventPublisher;
import com.example.smartpark.workflow.WorkflowExecutionStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertPreflightWorkflowFactory {

    private final AlertTriageAgent triageAgent;
    private final AlertDiagnosisAgent diagnosisAgent;
    private final DevicePort devicePort;
    private final AlertPort alertPort;
    private final KnowledgePort knowledgePort;
    private final EnergyPort energyPort;
    private final SecurityEventReader securityEventReader;
    private final List<SecuritySourceAdapter> securitySourceAdapters;

    @Autowired
    public AlertPreflightWorkflowFactory(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            KnowledgePort knowledgePort,
            EnergyPort energyPort,
            ObjectProvider<SecurityPort> securityPorts,
            List<SecuritySourceAdapter> securitySourceAdapters) {
        this(triageAgent, diagnosisAgent, devicePort, alertPort, knowledgePort, energyPort,
                SecurityEventReaders.resolve(securityPorts), securitySourceAdapters);
    }

    public AlertPreflightWorkflowFactory(
            AlertTriageAgent triageAgent,
            AlertDiagnosisAgent diagnosisAgent,
            DevicePort devicePort,
            AlertPort alertPort,
            KnowledgePort knowledgePort,
            EnergyPort energyPort,
            SecurityEventReader securityEventReader,
            List<SecuritySourceAdapter> securitySourceAdapters) {
        this.triageAgent = triageAgent;
        this.diagnosisAgent = diagnosisAgent;
        this.devicePort = devicePort;
        this.alertPort = alertPort;
        this.knowledgePort = knowledgePort;
        this.energyPort = energyPort;
        this.securityEventReader = securityEventReader;
        this.securitySourceAdapters = List.copyOf(securitySourceAdapters);
    }

    public AlertWorkflow create() {
        // Aggregate every ingestion path so a source-qualified reference resolves against
        // the referenced source even when adapters are the only registered event source.
        SecurityEventReader securityEvents = SecurityEventCatalog.aggregating(securityEventReader, securitySourceAdapters);
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
                securityEvents,
                new AlertPreflightBoundaryObserver());
    }
}
