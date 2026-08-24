package com.example.smartpark.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsCapabilitiesController {

    private final String knowledgeMode;
    private final String customerAnswerMode;

    public OperationsCapabilitiesController(
            @Value("${smartpark.knowledge.mode:mock}") String knowledgeMode,
            @Value("${smartpark.customer.answer-mode:mock}") String customerAnswerMode) {
        this.knowledgeMode = safeMode(knowledgeMode, "mock", "rag");
        this.customerAnswerMode = safeMode(customerAnswerMode, "mock", "dashscope");
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(knowledgeMode, customerAnswerMode, "in-memory");
    }

    private static String safeMode(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) return candidate;
        }
        return allowed[0];
    }

    public record Capabilities(
            String knowledgeMode,
            String customerAnswerMode,
            String vectorStore) { }
}
