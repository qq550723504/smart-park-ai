package com.example.smartpark.workflow;

public final class CustomerServiceValidationException extends RuntimeException {
    public CustomerServiceValidationException(String message) {
        super(message);
    }
}
