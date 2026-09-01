package com.example.smartpark.showcase;

import com.example.smartpark.agent.AlertDiagnosisAgent;
import com.example.smartpark.agent.AlertTriageAgent;
import com.example.smartpark.analytics.OperationsAnalysisService;
import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.port.alert.AlertPort;
import com.example.smartpark.port.device.DevicePort;
import com.example.smartpark.port.energy.EnergyPort;
import com.example.smartpark.port.knowledge.KnowledgePort;
import com.example.smartpark.port.security.SecurityPort;
import com.example.smartpark.voice.VoiceAnswerAgent;
import com.example.smartpark.voice.port.StreamingAsrPort;
import com.example.smartpark.voice.port.StreamingTtsPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ShowcasePreflightRegistrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ExpertCollaborationService.class,
                    () -> mock(ExpertCollaborationService.class))
            .withBean(OperationsAnalysisService.class,
                    () -> mock(OperationsAnalysisService.class))
            .withBean(AlertTriageAgent.class, () -> mock(AlertTriageAgent.class))
            .withBean(AlertDiagnosisAgent.class, () -> mock(AlertDiagnosisAgent.class))
            .withBean(DevicePort.class, () -> mock(DevicePort.class))
            .withBean(AlertPort.class, () -> mock(AlertPort.class))
            .withBean(KnowledgePort.class, () -> mock(KnowledgePort.class))
            .withBean(EnergyPort.class, () -> mock(EnergyPort.class))
            .withBean(SecurityPort.class, () -> mock(SecurityPort.class))
            .withBean(StreamingAsrPort.class, () -> mock(StreamingAsrPort.class))
            .withBean(VoiceAnswerAgent.class, () -> mock(VoiceAnswerAgent.class))
            .withBean(StreamingTtsPort.class, () -> mock(StreamingTtsPort.class))
            .withUserConfiguration(ProbeFixture.class);

    @Test
    void defaultModesRegisterCollaborationAndOfflineCustomerProbes() {
        runner.run(context -> {
            assertThat(context.getBeansOfType(ShowcasePreflightProbe.class).values())
                    .extracting(ShowcasePreflightProbe::scenarioId)
                    .containsExactlyInAnyOrder(
                            ShowcaseScenarioId.EXPERT_COLLABORATION,
                            ShowcaseScenarioId.CUSTOMER_SERVICE);
            assertThat(context).doesNotHaveBean(AlertPreflightWorkflowFactory.class);
            assertThat(context).doesNotHaveBean(AlertWorkflowPreflightProbe.class);
            assertThat(context).doesNotHaveBean(OperationsAnalysisPreflightProbe.class);
            assertThat(context).doesNotHaveBean(VoiceAssistantPreflightProbe.class);
        });
    }

    @Test
    void fullShowcaseModesRegisterExactlyFourUniqueProbes() {
        runner.withPropertyValues(
                        "spring.ai.dashscope.enabled=true",
                        "smartpark.knowledge.mode=rag",
                        "smartpark.customer-service.answer-mode=dashscope",
                        "smartpark.analytics.enabled=true",
                        "smartpark.voice.enabled=true")
                .run(context -> {
                    assertThat(context.getBeansOfType(ShowcasePreflightProbe.class).values())
                            .extracting(ShowcasePreflightProbe::scenarioId)
                            .containsExactlyInAnyOrder(ShowcaseScenarioId.values());
                    assertThat(context).hasSingleBean(AlertPreflightWorkflowFactory.class);
                    assertThat(context).hasSingleBean(AlertWorkflowPreflightProbe.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @Import({
            ExpertCollaborationPreflightProbe.class,
            OperationsAnalysisPreflightProbe.class,
            CustomerServicePreflightProbe.class,
            AlertPreflightWorkflowFactory.class,
            AlertWorkflowPreflightProbe.class,
            VoiceAssistantPreflightProbe.class
    })
    static class ProbeFixture {
    }
}
