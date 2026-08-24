package com.example.smartpark.agent;

public final class ModelOutputException extends IllegalStateException {

    public ModelOutputException(String message) {
        super(message);
    }

    public ModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
