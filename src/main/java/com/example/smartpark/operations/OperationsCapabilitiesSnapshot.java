package com.example.smartpark.operations;

public record OperationsCapabilitiesSnapshot(
        String knowledgeMode,
        String customerAnswerMode,
        String vectorStore,
        boolean analyticsEnabled,
        boolean collaborationEnabled,
        boolean voiceEnabled) {
}
