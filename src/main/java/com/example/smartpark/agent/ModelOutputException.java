package com.example.smartpark.agent;

public final class ModelOutputException extends IllegalStateException {

    private final AlertModelFailureStage failureStage;

    public ModelOutputException(String message) {
        super(message);
        this.failureStage = null;
    }

    public ModelOutputException(String message, Throwable cause) {
        super(message, cause);
        this.failureStage = null;
    }

    ModelOutputException(String message, AlertModelFailureStage failureStage) {
        super(message);
        this.failureStage = failureStage;
    }

    AlertModelFailureStage failureStage() {
        return failureStage;
    }
}
