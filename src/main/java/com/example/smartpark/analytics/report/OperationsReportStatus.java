package com.example.smartpark.analytics.report;

public enum OperationsReportStatus {
    REQUESTED, GENERATING, COMPLETED, PARTIAL, FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == PARTIAL || this == FAILED;
    }
}
