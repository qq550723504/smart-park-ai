package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsCapabilitiesControllerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OperationsCapabilitiesController.class);

    @Test
    void reportsConfiguredRagAndDashScopeModesBackedByCurrentRuntime() {
        OperationsCapabilitiesController.Capabilities capabilities =
                new OperationsCapabilitiesController("rag", "dashscope", true, true, true, provider(null)).capabilities();

        assertThat(capabilities.knowledgeMode()).isEqualTo("rag");
        assertThat(capabilities.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(capabilities.vectorStore()).isEqualTo("simple-vector-store");
        assertThat(capabilities.collaborationEnabled()).isFalse();
        assertThat(capabilities.voiceEnabled()).isTrue();
    }

    @Test
    void reportsCollaborationOnlyWhenItsRuntimeBeanIsAvailable() {
        OperationsCapabilitiesController.Capabilities capabilities =
                new OperationsCapabilitiesController("mock", "mock", false, false, false, provider(new ExpertCollaborationService(
                        null, null, null, null, null, null, null, null))).capabilities();

        assertThat(capabilities.collaborationEnabled()).isTrue();
    }

    @Test
    void hidesVoiceWhenTheLocalDemoTransportIsDisabled() {
        contextRunner
                .withPropertyValues(
                        "smartpark.voice.enabled=true",
                        "smartpark.local-demo.enabled=false")
                .run(context -> assertThat(context.getBean(OperationsCapabilitiesController.class)
                        .capabilities().voiceEnabled()).isFalse());
    }

    @Test
    void hasOneSpringConstructorSoConfiguredValuesCannotBeBypassed() {
        assertThat(OperationsCapabilitiesController.class.getDeclaredConstructors()).hasSize(1);
    }

    private static ObjectProvider<ExpertCollaborationService> provider(Object value) {
        return new ObjectProvider<>() {
            @Override public ExpertCollaborationService getIfAvailable() {
                return value == null ? null : (ExpertCollaborationService) value;
            }
            @Override public ExpertCollaborationService getIfUnique() { return getIfAvailable(); }
            @Override public ExpertCollaborationService getObject(Object... args) { return getIfAvailable(); }
            @Override public ExpertCollaborationService getObject() { return getIfAvailable(); }
        };
    }
}
