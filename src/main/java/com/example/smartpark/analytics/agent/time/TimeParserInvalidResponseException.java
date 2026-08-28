package com.example.smartpark.analytics.agent.time;

public class TimeParserInvalidResponseException extends RuntimeException {
    public TimeParserInvalidResponseException(String message) {
        super(message);
    }

    public TimeParserInvalidResponseException(String message, Throwable cause) {
        super(message, cause);
    }
}
