package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsCapabilitiesControllerTest {

    @Test
    void reportsConfiguredRagAndDashScopeModesBackedByCurrentRuntime() {
        OperationsCapabilitiesController.Capabilities capabilities =
                new OperationsCapabilitiesController("rag", "dashscope", true, provider(null)).capabilities();

        assertThat(capabilities.knowledgeMode()).isEqualTo("rag");
        assertThat(capabilities.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(capabilities.vectorStore()).isEqualTo("simple-vector-store");
        assertThat(capabilities.collaborationEnabled()).isFalse();
    }

    @Test
    void reportsCollaborationOnlyWhenItsRuntimeBeanIsAvailable() {
        OperationsCapabilitiesController.Capabilities capabilities =
                new OperationsCapabilitiesController("mock", "mock", false, provider(new ExpertCollaborationService(
                        null, null, null, null, null, null, null, null))).capabilities();

        assertThat(capabilities.collaborationEnabled()).isTrue();
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
