package com.example.smartpark.agent;

final class ModelOutputException extends IllegalStateException {

    ModelOutputException(String message) {
        super(message);
    }

    ModelOutputException(String message, Throwable cause) {
        super(message, cause);
    }
}
