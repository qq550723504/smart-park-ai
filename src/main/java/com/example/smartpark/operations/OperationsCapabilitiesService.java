package com.example.smartpark.operations;

import com.example.smartpark.collaboration.ExpertCollaborationService;
import com.example.smartpark.port.security.SecurityEventCapabilityRegistry;
import com.example.smartpark.securityincident.SecurityIncidentService;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Objects;

public final class OperationsCapabilitiesService {
    private final String knowledgeMode;
    private final String customerAnswerMode;
    private final boolean analyticsEnabled;
    private final boolean voiceEnabled;
    private final boolean localDemoEnabled;
    private final ObjectProvider<ExpertCollaborationService> collaborationService;
    private final ObjectProvider<SecurityIncidentService> securityIncidentService;
    private final ObjectProvider<SecurityEventCapabilityRegistry> securityCapabilities;

    public OperationsCapabilitiesService(
            String knowledgeMode,
            String customerAnswerMode,
            boolean analyticsEnabled,
            boolean voiceEnabled,
            boolean localDemoEnabled,
            ObjectProvider<ExpertCollaborationService> collaborationService,
            ObjectProvider<SecurityIncidentService> securityIncidentService,
            ObjectProvider<SecurityEventCapabilityRegistry> securityCapabilities) {
        this.knowledgeMode = safeMode(knowledgeMode, "mock", "rag");
        this.customerAnswerMode = safeMode(customerAnswerMode, "mock", "dashscope");
        this.analyticsEnabled = analyticsEnabled;
        this.voiceEnabled = voiceEnabled;
        this.localDemoEnabled = localDemoEnabled;
        this.collaborationService = Objects.requireNonNull(collaborationService, "collaborationService");
        this.securityIncidentService = Objects.requireNonNull(securityIncidentService, "securityIncidentService");
        this.securityCapabilities = Objects.requireNonNull(securityCapabilities, "securityCapabilities");
    }

    public OperationsCapabilitiesSnapshot snapshot() {
        SecurityEventCapabilityRegistry registry = securityCapabilities.getIfAvailable();
        return new OperationsCapabilitiesSnapshot(
                knowledgeMode,
                customerAnswerMode,
                "rag".equals(knowledgeMode) ? "simple-vector-store" : "none",
                analyticsEnabled,
                collaborationService.getIfAvailable() != null,
                voiceEnabled && localDemoEnabled,
                securityIncidentService.getIfAvailable() != null,
                registry == null ? List.of() : registry.capabilities(),
                registry != null && registry.dispositionEnabled());
    }

    private static String safeMode(String value, String... allowed) {
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(value)) return candidate;
        }
        return allowed[0];
    }
}
