package com.example.smartpark.execution.model;

/** Lifecycle status of an event; SUCCEEDED, FAILED and INTERRUPTED are terminal for the run. */
public enum ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    NEEDS_CLARIFICATION;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == INTERRUPTED;
    }
}
