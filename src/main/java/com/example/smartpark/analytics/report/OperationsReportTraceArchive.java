package com.example.smartpark.analytics.report;

import com.example.smartpark.execution.ExecutionEventArchive;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;

import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

/** Makes durable report traces replayable through the unified execution API. */
public final class OperationsReportTraceArchive implements ExecutionEventArchive {
    private final OperationsDailyReportStore store;
    private final ExecutionEventPublisher publisher;

    public OperationsReportTraceArchive(OperationsDailyReportStore store) {
        this(store, null);
    }

    public OperationsReportTraceArchive(OperationsDailyReportStore store,
                                        ExecutionEventPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public List<ExecutionEvent> history(UUID runId) {
        return store.findByRunId(runId).map(report -> report.traceEvents().stream()
                .map(trace -> trace.toExecutionEvent(report.traceId()))
                .toList()).orElseGet(List::of);
    }

    @Override
    public void authorize(UUID runId, String role) {
        var persisted = store.findByRunId(runId);
        persisted.ifPresent(report -> {
            String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            if (!"ADMIN".equals(normalized) && !report.role().equals(normalized)) {
                throw new SecurityException("role is not allowed to read operations report trace");
            }
        });
        if (persisted.isEmpty() && publisher != null && publisher.history(runId).stream()
                .anyMatch(event -> OperationsReportTraceRecord.REPORT_ACTOR.equals(event.actor()))) {
            throw new NoSuchElementException("Unknown operations report trace");
        }
    }
}
