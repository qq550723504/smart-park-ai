package com.example.smartpark.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationsCapabilitiesControllerTest {

    @Test
    void exposesConfiguredCapabilityModes() {
        OperationsCapabilitiesController.Capabilities capabilities =
                new OperationsCapabilitiesController("rag", "dashscope").capabilities();

        assertThat(capabilities.knowledgeMode()).isEqualTo("rag");
        assertThat(capabilities.customerAnswerMode()).isEqualTo("dashscope");
        assertThat(capabilities.vectorStore()).isEqualTo("in-memory");
    }

    @Test
    void hasOneSpringConstructorSoConfiguredValuesCannotBeBypassed() {
        assertThat(OperationsCapabilitiesController.class.getDeclaredConstructors()).hasSize(1);
    }
}
