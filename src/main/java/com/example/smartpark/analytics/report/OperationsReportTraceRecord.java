package com.example.smartpark.analytics.report;

import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record OperationsReportTraceRecord(UUID eventId, long sequence, Instant timestamp,
        String actor, ExecutionStage stage, ExecutionEventType eventType,
        ExecutionStatus status, String safeSummary) {
    public static final String REPORT_ACTOR = "report";

    public ExecutionEvent toExecutionEvent(UUID traceId) {
        return new ExecutionEvent(eventId, traceId, sequence, timestamp,
                ExecutionScenario.OPERATIONS_ANALYSIS, actor, stage, eventType, status, safeSummary, null);
    }
}
