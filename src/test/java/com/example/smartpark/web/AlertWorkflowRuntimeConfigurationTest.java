package com.example.smartpark.web;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.execution.LegacyWorkflowEventAdapter;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityEventReader;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.port.workorder.WorkOrderPort;
import com.example.smartpark.workflow.AlertWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AlertWorkflowRuntimeConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AlertWorkflowRuntimeConfiguration.class)
            .withBean(AlertTriageAgent.class, () -> mock(AlertTriageAgent.class))
            .withBean(AlertDiagnosisAgent.class, () -> mock(AlertDiagnosisAgent.class))
            .withBean(DevicePort.class, () -> mock(DevicePort.class))
            .withBean(AlertPort.class, () -> mock(AlertPort.class))
            .withBean(WorkOrderPort.class, () -> mock(WorkOrderPort.class))
            .withBean(KnowledgePort.class, () -> mock(KnowledgePort.class))
            .withBean(EnergyPort.class, () -> mock(EnergyPort.class))
            .withBean(LegacyWorkflowEventAdapter.class, () -> mock(LegacyWorkflowEventAdapter.class));

    @Test
    void createsTheWorkflowAgainstALegacySecurityPortThatIsNotAReader() {
        runner.withBean(SecurityPort.class, () -> mock(SecurityPort.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AlertWorkflow.class);
                });
    }

    @Test
    void prefersAReaderOverALegacyPortWhenBothAreRegistered() {
        runner.withBean(SecurityPort.class, () -> mock(SecurityPort.class))
                .withBean(SecurityEventReader.class, () -> mock(SecurityEventReader.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AlertWorkflow.class);
                });
    }
}
