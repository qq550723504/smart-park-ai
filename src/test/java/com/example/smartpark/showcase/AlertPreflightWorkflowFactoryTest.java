package com.example.smartpark.showcase;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.workflow.AlertWorkflow;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AlertPreflightWorkflowFactoryTest {

    @Test
    void createsFreshIsolatedWorkflowsWithoutTouchingDependencies() {
        AlertTriageAgent triageAgent = mock(AlertTriageAgent.class);
        AlertDiagnosisAgent diagnosisAgent = mock(AlertDiagnosisAgent.class);
        DevicePort devicePort = mock(DevicePort.class);
        AlertPort alertPort = mock(AlertPort.class);
        KnowledgePort knowledgePort = mock(KnowledgePort.class);
        EnergyPort energyPort = mock(EnergyPort.class);
        SecurityPort securityPort = mock(SecurityPort.class);
        AlertPreflightWorkflowFactory factory = new AlertPreflightWorkflowFactory(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                knowledgePort,
                energyPort,
                securityPort);

        AlertWorkflow first = factory.create();
        AlertWorkflow second = factory.create();

        assertThat(first).isNotSameAs(second);
        verifyNoInteractions(
                triageAgent,
                diagnosisAgent,
                devicePort,
                alertPort,
                knowledgePort,
                energyPort,
                securityPort);
    }

    @Test
    void showcaseConditionMatchesTheFullyEnabledAlertChain() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("smartpark.knowledge.mode", "rag")
                .withProperty("smartpark.customer-service.answer-mode", "dashscope");

        assertThat(matches(environment)).isTrue();
        assertThat(matches(environment.withProperty("spring.ai.dashscope.enabled", "true"))).isTrue();
    }

    @Test
    void showcaseConditionRejectsDefaultOrPartiallyEnabledDeployments() {
        assertThat(matches(new MockEnvironment())).isFalse();
        assertThat(matches(new MockEnvironment()
                .withProperty("spring.ai.dashscope.enabled", "false")
                .withProperty("smartpark.knowledge.mode", "rag")
                .withProperty("smartpark.customer-service.answer-mode", "dashscope"))).isFalse();
        assertThat(matches(new MockEnvironment()
                .withProperty("smartpark.knowledge.mode", "mock")
                .withProperty("smartpark.customer-service.answer-mode", "dashscope"))).isFalse();
        assertThat(matches(new MockEnvironment()
                .withProperty("smartpark.knowledge.mode", "rag")
                .withProperty("smartpark.customer-service.answer-mode", "mock"))).isFalse();
    }

    private static boolean matches(MockEnvironment environment) {
        ConditionContext context = mock(ConditionContext.class);
        when(context.getEnvironment()).thenReturn(environment);
        return new AlertShowcaseCondition().matches(context, mock());
    }
}
