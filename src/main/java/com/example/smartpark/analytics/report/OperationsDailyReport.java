package com.example.smartpark.analytics.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Safe immutable report snapshot exposed by the application layer. */
public record OperationsDailyReport(
        UUID runId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        List<SectionResult> sections) {

    private static final List<String> STATUSES = List.of("RUNNING", "COMPLETED", "PARTIAL", "FAILED");

    public OperationsDailyReport {
        Objects.requireNonNull(runId, "runId");
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("unsupported report status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        sections = sections == null ? List.of() : sections.stream().map(Objects::requireNonNull).toList();
    }

    public OperationsDailyReport withStatus(String nextStatus, Instant at) {
        return new OperationsDailyReport(runId, nextStatus, createdAt, at, sections);
    }

    public OperationsDailyReport withSections(String nextStatus, Instant at, List<SectionResult> nextSections) {
        return new OperationsDailyReport(runId, nextStatus, createdAt, at, nextSections);
    }

    public record SectionResult(
            String id,
            String title,
            String question,
            OperationsReportSectionStatus status,
            String summary,
            int rowCount,
            boolean truncated,
            List<String> columns,
            List<List<Object>> rows,
            Map<String, Object> timeResolution,
            String failureStage) {

        public SectionResult {
            id = requireText(id, "id");
            title = requireText(title, "title");
            question = requireText(question, "question");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            if (rowCount < 0) throw new IllegalArgumentException("rowCount must not be negative");
            columns = columns == null ? List.of() : List.copyOf(columns);
            rows = rows == null ? List.of() : rows.stream().map(row -> List.copyOf(row)).toList();
            timeResolution = timeResolution == null ? Map.of() : Map.copyOf(timeResolution);
        }

        public static SectionResult pending(OperationsReportSection section) {
            return new SectionResult(section.id(), section.title(), section.question(),
                    OperationsReportSectionStatus.PENDING, "", 0, false, List.of(), List.of(), Map.of(), null);
        }

        public SectionResult running() {
            return new SectionResult(id, title, question, OperationsReportSectionStatus.RUNNING,
                    summary, rowCount, truncated, columns, rows, timeResolution, failureStage);
        }

        public SectionResult failed(String stage) {
            return new SectionResult(id, title, question, OperationsReportSectionStatus.FAILED,
                    "", 0, false, List.of(), List.of(), Map.of(), stage);
        }

        private static String requireText(String value, String field) {
            Objects.requireNonNull(value, field);
            if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
            return value;
        }
    }
}
