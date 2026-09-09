package com.example.smartpark.execution;

/** A new replay stream cannot be admitted without evicting an active run. */
public final class ExecutionEventCapacityException extends IllegalStateException {
    public ExecutionEventCapacityException(String message) {
        super(message);
    }
}
