package com.example.smartpark.orchestration;

public enum OrchestrationStepStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    SKIPPED,
    BLOCKED,
    FAILED,
    WAITING_APPROVAL,
    CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == SKIPPED || this == BLOCKED
                || this == FAILED || this == CANCELLED;
    }
}
