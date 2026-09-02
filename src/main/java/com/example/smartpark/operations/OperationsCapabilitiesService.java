package com.example.smartpark.operations;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Objects;

public final class OperationsCapabilitiesService {
    private final String knowledgeMode;
    private final String customerAnswerMode;
    private final boolean analyticsEnabled;
    private final boolean voiceEnabled;
    private final boolean localDemoEnabled;
    private final ObjectProvider<ExpertCollaborationService> collaborationService;
    private final ObjectProvider<SecurityIncidentService> securityIncidentService;

    public OperationsCapabilitiesService(
            String knowledgeMode,
            String customerAnswerMode,
            boolean analyticsEnabled,
            boolean voiceEnabled,
            boolean localDemoEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationService,
            ObjectProvider<SecurityIncidentService> securityIncidentService) {
        this.knowledgeMode = safeMode(knowledgeMode, "mock", "rag");
        this.customerAnswerMode = safeMode(customerAnswerMode, "mock", "dashscope");
        this.analyticsEnabled = analyticsEnabled;
        this.voiceEnabled = voiceEnabled;
        this.localDemoEnabled = localDemoEnabled;
        this.collaborationService = Objects.requireNonNull(collaborationService, "collaborationService");
        this.securityIncidentService = Objects.requireNonNull(securityIncidentService, "securityIncidentService");
    }

    public OperationsCapabilitiesSnapshot snapshot() {
        return new OperationsCapabilitiesSnapshot(
                knowledgeMode,
                customerAnswerMode,
                "rag".equals(knowledgeMode) ? "simple-vector-store" : "none",
                analyticsEnabled,
                collaborationService.getIfAvailable() != null,
                voiceEnabled && localDemoEnabled,
                securityIncidentService.getIfAvailable() != null);
    }

    private static String safeMode(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) return candidate;
        }
        return allowed[0];
    }
}
