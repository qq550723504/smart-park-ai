package com.example.smartpark.analytics.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, generation-time report snapshot. Historical reads never re-run analytics. */
public record OperationsDailyReport(
        UUID reportId, String reportType, String title, OperationsReportStatus status,
        String requestedBy, String role, Instant createdAt, Instant startedAt, Instant completedAt,
        OperationsReportRequest.TimeWindow timeWindow, String timezone, Instant asOf, String summary,
        List<SectionResult> sections, List<EvidenceReference> evidence,
        List<SourceReference> sourceReferences, UUID runId, UUID traceId,
        int schemaVersion, String generationVersion, Artifact artifact,
        String idempotencyKey, String requestFingerprint, long revision,
        List<OperationsReportTraceRecord> traceEvents) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final String CURRENT_GENERATION_VERSION = "operations-daily-v2";

    public OperationsDailyReport {
        Objects.requireNonNull(reportId, "reportId");
        reportType = requireText(reportType, "reportType");
        title = requireText(title, "title");
        Objects.requireNonNull(status, "status");
        requestedBy = requireText(requestedBy, "requestedBy");
        role = requireText(role, "role");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(timeWindow, "timeWindow");
        timezone = requireText(timezone, "timezone");
        summary = summary == null ? "" : summary;
        sections = List.copyOf(sections == null ? List.of() : sections);
        evidence = List.copyOf(evidence == null ? List.of() : evidence);
        sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(traceId, "traceId");
        if (schemaVersion < 1) throw new IllegalArgumentException("schemaVersion must be positive");
        generationVersion = requireText(generationVersion, "generationVersion");
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        requestFingerprint = requireText(requestFingerprint, "requestFingerprint");
        if (revision < 0) throw new IllegalArgumentException("revision must not be negative");
        traceEvents = List.copyOf(traceEvents == null ? List.of() : traceEvents);
    }

    public OperationsDailyReport copy(OperationsReportStatus nextStatus, Instant nextStartedAt,
                                      Instant nextCompletedAt, Instant nextAsOf, String nextSummary,
                                      List<SectionResult> nextSections, List<EvidenceReference> nextEvidence,
                                      List<SourceReference> nextSources, Artifact nextArtifact,
                                      List<OperationsReportTraceRecord> nextTrace) {
        return new OperationsDailyReport(reportId, reportType, title, nextStatus, requestedBy, role,
                createdAt, nextStartedAt, nextCompletedAt, timeWindow, timezone, nextAsOf, nextSummary,
                nextSections, nextEvidence, nextSources, runId, traceId, schemaVersion, generationVersion,
                nextArtifact, idempotencyKey, requestFingerprint, revision + 1, nextTrace);
    }

    public record SectionResult(
            String sectionId, String title, String question, OperationsReportSectionStatus status,
            String summary, int rowCount, boolean truncated, List<String> columns,
            List<List<Object>> rows, Map<String, Object> timeResolution,
            List<EvidenceReference> evidenceReferences, List<SourceReference> sourceReferences,
            String partialReason, String failureReason, UUID runId) {

        public SectionResult {
            sectionId = requireText(sectionId, "sectionId");
            title = requireText(title, "title");
            question = requireText(question, "question");
            Objects.requireNonNull(status, "status");
            summary = summary == null ? "" : summary;
            if (rowCount < 0) throw new IllegalArgumentException("rowCount must not be negative");
            columns = List.copyOf(columns == null ? List.of() : columns);
            rows = rows == null ? List.of() : rows.stream()
                    .map(row -> Collections.unmodifiableList(new ArrayList<>(
                            Objects.requireNonNull(row, "row"))))
                    .toList();
            timeResolution = Map.copyOf(timeResolution == null ? Map.of() : timeResolution);
            evidenceReferences = List.copyOf(evidenceReferences == null ? List.of() : evidenceReferences);
            sourceReferences = List.copyOf(sourceReferences == null ? List.of() : sourceReferences);
        }

        public static SectionResult pending(OperationsReportSection section) {
            return pending(section, section.question());
        }

        public static SectionResult pending(OperationsReportSection section, String resolvedQuestion) {
            return new SectionResult(section.id(), section.title(), resolvedQuestion,
                    OperationsReportSectionStatus.PENDING, "", 0, false, List.of(), List.of(), Map.of(),
                    List.of(), List.of(), null, null, null);
        }

        public SectionResult running() {
            return new SectionResult(sectionId, title, question, OperationsReportSectionStatus.RUNNING,
                    summary, rowCount, truncated, columns, rows, timeResolution, evidenceReferences,
                    sourceReferences, partialReason, failureReason, runId);
        }

        public SectionResult unavailable(String reason) {
            return new SectionResult(sectionId, title, question, OperationsReportSectionStatus.UNAVAILABLE,
                    "", 0, false, List.of(), List.of(), Map.of(), List.of(), List.of(), reason, null, runId);
        }

        public SectionResult failed(String reason) {
            return new SectionResult(sectionId, title, question, OperationsReportSectionStatus.FAILED,
                    "", 0, false, List.of(), List.of(), Map.of(), List.of(), List.of(), null, reason, runId);
        }
    }

    public record EvidenceReference(String sourceSystem, String metric, String entity,
                                    Instant observationTime, String runReference, String summary) {
        public EvidenceReference {
            sourceSystem = requireText(sourceSystem, "sourceSystem");
            metric = requireText(metric, "metric");
            entity = requireText(entity, "entity");
            Objects.requireNonNull(observationTime, "observationTime");
            runReference = requireText(runReference, "runReference");
            summary = requireText(summary, "summary");
        }
    }

    public record SourceReference(String sourceSystem, String metric, String unit,
                                  String status, Instant asOf) {
        public SourceReference {
            sourceSystem = requireText(sourceSystem, "sourceSystem");
            metric = requireText(metric, "metric");
            unit = requireText(unit, "unit");
            status = requireText(status, "status");
            Objects.requireNonNull(asOf, "asOf");
        }
    }

    /** Content is persisted atomically with the terminal report and omitted from detail DTOs. */
    public record Artifact(UUID artifactId, String format, String fileName, String contentType,
                           long size, Instant createdAt, String checksum, String rendererVersion,
                           String content) {
        public Artifact {
            Objects.requireNonNull(artifactId, "artifactId");
            format = requireText(format, "format");
            fileName = requireText(fileName, "fileName");
            if (fileName.length() > 120 || !fileName.matches("[A-Za-z0-9._-]+")) {
                throw new IllegalArgumentException("artifact fileName is unsafe");
            }
            contentType = requireText(contentType, "contentType");
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
            Objects.requireNonNull(createdAt, "createdAt");
            checksum = requireText(checksum, "checksum");
            rendererVersion = requireText(rendererVersion, "rendererVersion");
            content = Objects.requireNonNull(content, "content");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
