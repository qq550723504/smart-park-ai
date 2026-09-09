package com.example.smartpark.analytics.report;

/** Signals that historical reports are readable but new report generation is unavailable. */
public final class OperationsReportUnavailableException extends RuntimeException {
    public OperationsReportUnavailableException(String message) {
        super(message);
    }
}
