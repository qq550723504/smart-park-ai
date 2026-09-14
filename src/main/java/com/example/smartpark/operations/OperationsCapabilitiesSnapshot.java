package com.example.smartpark.operations;

import com.example.smartpark.port.security.SecurityEventCapability;

import java.util.List;

public record OperationsCapabilitiesSnapshot(
        String knowledgeMode,
        String customerAnswerMode,
        String vectorStore,
        boolean analyticsEnabled,
        boolean collaborationEnabled,
        boolean voiceEnabled,
        boolean securityIncidentEnabled,
        List<SecurityEventCapability> securityEventCapabilities,
        boolean securityDispositionEnabled) {

    public OperationsCapabilitiesSnapshot(String knowledgeMode, String customerAnswerMode, String vectorStore,
                                          boolean analyticsEnabled, boolean collaborationEnabled, boolean voiceEnabled,
                                          boolean securityIncidentEnabled) {
        this(knowledgeMode, customerAnswerMode, vectorStore, analyticsEnabled, collaborationEnabled, voiceEnabled,
                securityIncidentEnabled, List.of(), false);
    }

    public OperationsCapabilitiesSnapshot {
        securityEventCapabilities = securityEventCapabilities == null ? List.of() : List.copyOf(securityEventCapabilities);
    }
}
