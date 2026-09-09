package com.example.smartpark.orchestration;

/** Admission was rejected before a new orchestration run was created. */
public final class OrchestrationCapacityException extends RuntimeException {
    public OrchestrationCapacityException(String message) {
        super(message);
    }
}
