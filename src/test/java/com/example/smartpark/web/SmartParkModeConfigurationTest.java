package com.example.smartpark.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SmartParkModeConfigurationTest {
    @Test
    void invalidKnowledgeModeFailsClearly() {
        SmartParkModeConfiguration configuration = new SmartParkModeConfiguration();
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "knowledgeMode", "remote");
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "answerMode", "mock");
        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smartpark.knowledge.mode");
    }

    @Test
    void invalidAnswerModeFailsClearly() {
        SmartParkModeConfiguration configuration = new SmartParkModeConfiguration();
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "knowledgeMode", "mock");
        org.springframework.test.util.ReflectionTestUtils.setField(configuration, "answerMode", "llm");
        assertThatThrownBy(configuration::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("smartpark.customer-service.answer-mode");
    }
}
