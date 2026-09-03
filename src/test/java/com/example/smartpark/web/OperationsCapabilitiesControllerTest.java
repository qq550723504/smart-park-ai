package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.securityincident.SecurityIncidentService;
import com.example.smartpark.operations.OperationsCapabilitiesService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsCapabilitiesControllerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(OperationsCapabilitiesController.class);

    @Test
    void reportsConfiguredRagAndDashScopeModesBackedByCurrentRuntime() {
        OperationsCapabilitiesController controller = new OperationsCapabilitiesController(new OperationsCapabilitiesService(
                "rag", "dashscope", true, true, true, provider(null), provider(null)));
        var capabilities = controller.capabilities();

        assertThat(capabilities.knowledgeMode()).isEqualTo("rag");
        assertThat(capabilities.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(capabilities.vectorStore()).isEqualTo("simple-vector-store");
        assertThat(capabilities.collaborationEnabled()).isFalse();
        assertThat(capabilities.voiceEnabled()).isTrue();
    }

    @Test
    void reportsCollaborationOnlyWhenItsRuntimeBeanIsAvailable() {
        OperationsCapabilitiesController controller = new OperationsCapabilitiesController(new OperationsCapabilitiesService(
                "mock", "mock", false, false, false, provider(new ExpertCollaborationService(
                        null, null, null, null, null, null, null, null)), provider(null)));
        var capabilities = controller.capabilities();

        assertThat(capabilities.collaborationEnabled()).isTrue();
    }

    @Test
    void hidesVoiceWhenTheLocalDemoTransportIsDisabled() {
        OperationsCapabilitiesController controller = new OperationsCapabilitiesController(new OperationsCapabilitiesService(
                "mock", "mock", false, true, false, provider(null), provider(null)));
        assertThat(controller.capabilities().voiceEnabled()).isFalse();
    }

    @Test
    void hasOneSpringConstructorSoConfiguredValuesCannotBeBypassed() {
        assertThat(OperationsCapabilitiesController.class.getDeclaredConstructors()).hasSize(1);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return getIfAvailable(); }
            @Override public T getObject(Object... args) { return getIfAvailable(); }
            @Override public T getObject() { return getIfAvailable(); }
        };
    }
}
