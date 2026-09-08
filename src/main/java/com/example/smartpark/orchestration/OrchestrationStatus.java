package com.example.smartpark.orchestration;

public enum OrchestrationStatus {
    RUNNING,
    COMPLETED,
    PARTIAL,
    FAILED,
    WAITING_APPROVAL,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }
}
