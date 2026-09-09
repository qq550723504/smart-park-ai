package com.example.smartpark.analytics.report;

/** The record is preserved, but this runtime cannot safely project its schema. */
public final class UnsupportedOperationsReportSchemaException extends RuntimeException {
    public UnsupportedOperationsReportSchemaException() {
        super("operations report schema is unsupported");
    }
}
