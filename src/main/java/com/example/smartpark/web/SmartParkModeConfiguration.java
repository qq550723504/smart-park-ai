package com.example.smartpark.web;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Set;

@Configuration(proxyBeanMethods = false)
public class SmartParkModeConfiguration {
    private static final Set<String> KNOWLEDGE_MODES = Set.of("mock", "rag");
    private static final Set<String> ANSWER_MODES = Set.of("mock", "dashscope");

    @Value("${smartpark.knowledge.mode:mock}")
    private String knowledgeMode;

    @Value("${smartpark.customer-service.answer-mode:mock}")
    private String answerMode;

    @PostConstruct
    void validate() {
        requireAllowed("smartpark.knowledge.mode", knowledgeMode, KNOWLEDGE_MODES);
        requireAllowed("smartpark.customer-service.answer-mode", answerMode, ANSWER_MODES);
    }

    private static void requireAllowed(String property, String value, Set<String> allowed) {
        if (!allowed.contains(value)) {
            throw new IllegalStateException(property + " must be one of " + allowed + ", but was: " + value);
        }
    }
}
