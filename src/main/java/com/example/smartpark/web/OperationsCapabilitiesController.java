package com.example.smartpark.web;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/operations")
public class OperationsCapabilitiesController {

    private final String knowledgeMode;
    private final String customerAnswerMode;
    private final boolean analyticsEnabled;
    private final ObjectProvider<ExpertCollaborationService> collaborationService;

    public OperationsCapabilitiesController(
            @Value("${smartpark.knowledge.mode:mock}") String knowledgeMode,
            @Value("${smartpark.customer-service.answer-mode:mock}") String customerAnswerMode,
            @Value("${smartpark.analytics.enabled:false}") boolean analyticsEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationService) {
        this.knowledgeMode = safeMode(knowledgeMode, "mock", "rag");
        this.customerAnswerMode = safeMode(customerAnswerMode, "mock", "dashscope");
        this.analyticsEnabled = analyticsEnabled;
        this.collaborationService = collaborationService;
    }

    @GetMapping("/capabilities")
    public Capabilities capabilities() {
        return new Capabilities(knowledgeMode, customerAnswerMode,
                "rag".equals(knowledgeMode) ? "simple-vector-store" : "none", analyticsEnabled,
                collaborationService.getIfAvailable() != null);
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
            String vectorStore,
            boolean analyticsEnabled,
            boolean collaborationEnabled) { }
}
