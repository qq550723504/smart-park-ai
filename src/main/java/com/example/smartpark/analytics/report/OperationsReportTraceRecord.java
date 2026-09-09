package com.example.smartpark.analytics.report;

import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;

import java.time.Instant;
import java.util.UUID;

public record OperationsReportTraceRecord(UUID eventId, long sequence, Instant timestamp,
        String actor, ExecutionStage stage, ExecutionEventType eventType,
        ExecutionStatus status, String safeSummary) {
}
