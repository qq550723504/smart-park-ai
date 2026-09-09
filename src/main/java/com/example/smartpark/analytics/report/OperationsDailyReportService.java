package com.example.smartpark.analytics.report;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Orchestrates existing read-only analysis sections and persists immutable report snapshots. */
public final class OperationsDailyReportService {
    public static final int MAX_PAGE_SIZE = 50;
    private static final String SAFE_SECTION_FAILURE = "REPORT_SECTION_UNAVAILABLE";

    private final OperationsReportSectionRunner sectionRunner;
    private final OperationsDailyReportStore store;
    private final ExecutionEventPublisher events;
    private final OperationsReportRenderer renderer;
    private final Clock clock;
    private final boolean generationAvailable;

    public OperationsDailyReportService(OperationsReportSectionRunner sectionRunner,
                                        OperationsDailyReportStore store,
                                        ExecutionEventPublisher events,
                                        OperationsReportRenderer renderer,
                                        Clock clock) {
        this(sectionRunner, store, events, renderer, clock, true);
    }

    OperationsDailyReportService(OperationsReportSectionRunner sectionRunner,
                                 OperationsDailyReportStore store,
                                 ExecutionEventPublisher events,
                                 OperationsReportRenderer renderer,
                                 Clock clock,
                                 boolean generationAvailable) {
        this.sectionRunner = java.util.Objects.requireNonNull(sectionRunner, "sectionRunner");
        this.store = java.util.Objects.requireNonNull(store, "store");
        this.events = java.util.Objects.requireNonNull(events, "events");
        this.renderer = java.util.Objects.requireNonNull(renderer, "renderer");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.generationAvailable = generationAvailable;
        recoverInterrupted();
    }

    public OperationsDailyReportStore.StartResult start(OperationsReportRequest request,
                                                        String idempotencyKey,
                                                        String requestedBy,
                                                        String role) {
        if (!generationAvailable) {
            throw new OperationsReportUnavailableException("operations report generation is unavailable");
        }
        String key = requireText(idempotencyKey, "Idempotency-Key", 128);
        String normalizedRole = requireText(role, "role", 30).toUpperCase(Locale.ROOT);
        String actor = requireText(requestedBy, "requestedBy", 100);
        String fingerprint = fingerprint(request, normalizedRole);
        Instant now = clock.instant();
        OperationsDailyReportStore.StartResult admitted = store.createOrGet(key, fingerprint, () -> {
            UUID reportId = UUID.randomUUID();
            UUID runId = UUID.randomUUID();
            return new OperationsDailyReport(reportId, request.reportType(), "智慧园区运营日报",
                    OperationsReportStatus.REQUESTED, actor, normalizedRole, now, null, null,
                    request.timeWindow(), request.timezone(), null, "报告请求已接收",
                    OperationsDailyReportDefinition.sections().stream()
                            .map(OperationsDailyReport.SectionResult::pending).toList(),
                    List.of(), List.of(), runId, runId,
                    OperationsDailyReport.CURRENT_SCHEMA_VERSION,
                    OperationsDailyReport.CURRENT_GENERATION_VERSION, null, key, fingerprint, 0, List.of());
        });
        if (!admitted.created()) {
            hydrate(admitted.report());
            return admitted;
        }
        OperationsDailyReport generating;
        try {
            generating = store.update(admitted.report().reportId(), report ->
                    report.copy(OperationsReportStatus.GENERATING, now, null, null, "报告生成中",
                            report.sections(), report.evidence(), report.sourceReferences(), null,
                            append(report.traceEvents(), "operations-report", ExecutionStage.INITIALIZATION,
                                    ExecutionEventType.RUN_STARTED, ExecutionStatus.RUNNING, "运营日报开始生成")));
            publishNew(generating, 0);
            runSection(generating.reportId(), 0);
        } catch (RuntimeException failure) {
            failReport(admitted.report().reportId(), "REPORT_START_FAILED");
            throw failure;
        }
        return new OperationsDailyReportStore.StartResult(generating, true);
    }

    public OperationsReportRequest defaultRequest() {
        Instant to = clock.instant();
        return new OperationsReportRequest(OperationsReportRequest.DAILY,
                new OperationsReportRequest.TimeWindow(to.minus(java.time.Duration.ofDays(5)), to),
                OperationsReportRequest.DEFAULT_TIMEZONE);
    }

    public OperationsDailyReport get(UUID reportId, String role) {
        OperationsDailyReport report = store.find(reportId).orElseThrow(() -> {
            if (store.isUnsupported(reportId)) return new UnsupportedOperationsReportSchemaException();
            return new NoSuchElementException("Unknown operations report");
        });
        authorize(report, role);
        return report;
    }

    public Page list(String role, String reportType, OperationsReportStatus status,
                     Instant createdFrom, Instant createdTo, int page, int size) {
        String normalizedRole = requireText(role, "role", 30).toUpperCase(Locale.ROOT);
        if (page < 0) throw new IllegalArgumentException("page must not be negative");
        if (size < 1 || size > MAX_PAGE_SIZE) throw new IllegalArgumentException("page size is invalid");
        if (createdFrom != null && createdTo != null && !createdFrom.isBefore(createdTo)) {
            throw new IllegalArgumentException("created range must be ordered");
        }
        List<OperationsDailyReport> visible = store.all().stream()
                .filter(report -> "ADMIN".equals(normalizedRole) || report.role().equals(normalizedRole))
                .filter(report -> reportType == null || report.reportType().equals(reportType))
                .filter(report -> status == null || report.status() == status)
                .filter(report -> createdFrom == null || !report.createdAt().isBefore(createdFrom))
                .filter(report -> createdTo == null || report.createdAt().isBefore(createdTo))
                .sorted(Comparator.comparing(OperationsDailyReport::createdAt).reversed()
                        .thenComparing(OperationsDailyReport::reportId))
                .toList();
        long offset = (long) page * size;
        List<OperationsDailyReport> content = offset >= visible.size() ? List.of()
                : visible.subList((int) offset, Math.min(visible.size(), (int) offset + size));
        return new Page(content, page, size, visible.size(), offset + content.size() < visible.size());
    }

    public OperationsDailyReport.Artifact download(UUID reportId, String role) {
        OperationsDailyReport report = get(reportId, role);
        if (report.artifact() == null) throw new IllegalStateException("Report artifact is not available");
        return report.artifact();
    }

    private void runSection(UUID reportId, int index) {
        OperationsDailyReport current = store.find(reportId).orElse(null);
        if (current == null || current.status().isTerminal()) return;
        List<OperationsReportSection> definitions = OperationsDailyReportDefinition.sections();
        if (index >= definitions.size()) {
            finishReport(reportId);
            return;
        }
        OperationsReportSection definition = definitions.get(index);
        int firstTrace = current.traceEvents().size();
        OperationsDailyReport running = store.update(reportId, report -> {
            List<OperationsDailyReport.SectionResult> sections = new ArrayList<>(report.sections());
            sections.set(index, sections.get(index).running());
            return report.copy(OperationsReportStatus.GENERATING, report.startedAt(), null,
                    report.asOf(), report.summary(), sections, report.evidence(), report.sourceReferences(), null,
                    append(report.traceEvents(), definition.id(), ExecutionStage.ANALYSIS,
                            ExecutionEventType.STEP_STARTED, ExecutionStatus.RUNNING, definition.title()));
        });
        publishNew(running, firstTrace);
        CompletableFuture<AnalysisRunStore.RunRecord> execution;
        try {
            execution = sectionRunner.run(definition, new OperationsReportRequest(current.reportType(),
                    current.timeWindow(), current.timezone()));
            if (execution == null) throw new IllegalStateException("section runner returned null");
        } catch (RuntimeException failure) {
            completeSection(reportId, index, definition, null, failure);
            return;
        }
        execution.whenComplete((record, failure) -> completeSection(reportId, index, definition, record, failure));
    }

    private void completeSection(UUID reportId, int index, OperationsReportSection definition,
                                 AnalysisRunStore.RunRecord record, Throwable failure) {
        try {
            OperationsDailyReport.SectionResult result = sectionResult(definition, record, failure);
            OperationsDailyReport current = store.find(reportId)
                    .orElseThrow(() -> new NoSuchElementException("Unknown operations report"));
            if (current.status().isTerminal()) return;
            int firstTrace = current.traceEvents().size();
            OperationsDailyReport updated = store.update(reportId, report -> {
                List<OperationsDailyReport.SectionResult> sections = new ArrayList<>(report.sections());
                sections.set(index, result);
                List<OperationsDailyReport.EvidenceReference> evidence = sections.stream()
                        .flatMap(section -> section.evidenceReferences().stream()).toList();
                List<OperationsDailyReport.SourceReference> sources = sections.stream()
                        .flatMap(section -> section.sourceReferences().stream()).toList();
                ExecutionEventType type = result.status() == OperationsReportSectionStatus.COMPLETED
                        ? ExecutionEventType.STEP_COMPLETED : ExecutionEventType.STEP_FAILED;
                ExecutionStatus eventStatus = result.status() == OperationsReportSectionStatus.COMPLETED
                        ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED;
                String summary = result.status() == OperationsReportSectionStatus.COMPLETED
                        ? definition.title() + "已完成" : definition.title() + "不可用";
                return report.copy(OperationsReportStatus.GENERATING, report.startedAt(), null,
                        latestAsOf(sources), report.summary(), sections, evidence, sources, null,
                        append(report.traceEvents(), definition.id(), ExecutionStage.ANALYSIS,
                                type, eventStatus, summary));
            });
            publishNew(updated, firstTrace);
            runSection(reportId, index + 1);
        } catch (RuntimeException storeFailure) {
            failReport(reportId, "REPORT_PERSISTENCE_FAILED");
        }
    }

    private OperationsDailyReport.SectionResult sectionResult(OperationsReportSection definition,
                                                               AnalysisRunStore.RunRecord record,
                                                               Throwable failure) {
        if (failure != null || record == null) {
            return OperationsDailyReport.SectionResult.pending(definition).unavailable(SAFE_SECTION_FAILURE);
        }
        if (!"COMPLETED".equals(record.status())) {
            return OperationsDailyReport.SectionResult.pending(definition).unavailable(SAFE_SECTION_FAILURE);
        }
        Map<String, Object> resolution = timeResolution(record);
        Instant asOf = observationTime(record);
        OperationsDailyReport.SourceReference source = new OperationsDailyReport.SourceReference(
                definition.sourceSystem(), definition.metric(), definition.unit(),
                record.truncated() ? "PARTIAL" : "AVAILABLE", asOf);
        OperationsDailyReport.EvidenceReference evidence = new OperationsDailyReport.EvidenceReference(
                definition.sourceSystem(), definition.metric(), "REPORT_SECTION:" + definition.id(), asOf,
                record.runId().toString(), "已保存 " + record.rowCount() + " 行生成时结果");
        return new OperationsDailyReport.SectionResult(definition.id(), definition.title(), definition.question(),
                OperationsReportSectionStatus.COMPLETED, record.summary(), record.rowCount(), record.truncated(),
                record.columns(), record.rows(), resolution, List.of(evidence), List.of(source),
                record.truncated() ? "RESULT_TRUNCATED" : null, null, record.runId());
    }

    private void finishReport(UUID reportId) {
        OperationsDailyReport current = store.find(reportId)
                .orElseThrow(() -> new NoSuchElementException("Unknown operations report"));
        long completed = current.sections().stream()
                .filter(section -> section.status() == OperationsReportSectionStatus.COMPLETED).count();
        boolean degraded = current.sections().stream().anyMatch(section -> section.partialReason() != null);
        OperationsReportStatus status = completed == current.sections().size() && !degraded
                ? OperationsReportStatus.COMPLETED
                : completed == 0 ? OperationsReportStatus.FAILED : OperationsReportStatus.PARTIAL;
        Instant now = clock.instant();
        String summary = status == OperationsReportStatus.COMPLETED ? "运营日报已基于生成时证据完成"
                : status == OperationsReportStatus.PARTIAL ? "运营日报部分完成，未完成章节已明确标记"
                : "运营日报生成失败，没有可用章节";
        int firstTrace = current.traceEvents().size();
        OperationsDailyReport terminal = store.update(reportId, report -> {
            ExecutionEventType type = status == OperationsReportStatus.FAILED
                    ? ExecutionEventType.RUN_FAILED : ExecutionEventType.RUN_COMPLETED;
            ExecutionStatus traceStatus = status == OperationsReportStatus.FAILED
                    ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
            List<OperationsReportTraceRecord> trace = append(report.traceEvents(), "operations-report",
                    status == OperationsReportStatus.FAILED ? ExecutionStage.FAILURE : ExecutionStage.COMPLETION,
                    type, traceStatus, summary);
            OperationsDailyReport provisional = report.copy(status, report.startedAt(), now,
                    report.asOf(), summary, report.sections(), report.evidence(),
                    report.sourceReferences(), null, trace);
            OperationsDailyReport.Artifact artifact = status == OperationsReportStatus.FAILED
                    ? null : renderer.render(provisional, now);
            return report.copy(status, report.startedAt(), now, provisional.asOf(), summary,
                    report.sections(), report.evidence(), report.sourceReferences(), artifact, trace);
        });
        publishNew(terminal, firstTrace);
    }

    private void failReport(UUID reportId, String reason) {
        OperationsDailyReport current = store.find(reportId).orElse(null);
        if (current == null || current.status().isTerminal()) return;
        int firstTrace = current.traceEvents().size();
        try {
            OperationsDailyReport failed = store.update(reportId, report -> report.copy(
                    OperationsReportStatus.FAILED, report.startedAt(), clock.instant(), report.asOf(),
                    "运营日报生成失败", report.sections(), report.evidence(), report.sourceReferences(), null,
                    append(report.traceEvents(), "operations-report", ExecutionStage.FAILURE,
                            ExecutionEventType.RUN_FAILED, ExecutionStatus.FAILED, reason)));
            publishNew(failed, firstTrace);
        } catch (RuntimeException ignored) {
            // The durable state remains non-terminal only when the persistence boundary itself is unavailable.
        }
    }

    private void recoverInterrupted() {
        for (OperationsDailyReport interrupted : store.nonTerminalReports()) {
            long completed = interrupted.sections().stream()
                    .filter(section -> section.status() == OperationsReportSectionStatus.COMPLETED).count();
            OperationsReportStatus status = completed > 0 ? OperationsReportStatus.PARTIAL : OperationsReportStatus.FAILED;
            Instant now = clock.instant();
            OperationsDailyReport recovered;
            try {
                recovered = recoverInterrupted(interrupted.reportId(), status, now, true);
            } catch (OperationsReportCapacityException artifactTooLarge) {
                // A valid structured snapshot must never make startup dependent on whether
                // its optional Markdown projection fits the smaller artifact byte limit.
                recovered = recoverInterrupted(interrupted.reportId(), status, now, false);
            }
            hydrate(recovered);
        }
    }

    private OperationsDailyReport recoverInterrupted(UUID reportId, OperationsReportStatus status,
                                                      Instant now, boolean includeArtifact) {
        return store.update(reportId, report -> {
            List<OperationsDailyReport.SectionResult> sections = report.sections().stream().map(section ->
                    section.status() == OperationsReportSectionStatus.COMPLETED ? section
                            : section.unavailable("GENERATION_INTERRUPTED")).toList();
            List<OperationsReportTraceRecord> trace = append(report.traceEvents(), "operations-report",
                    ExecutionStage.FAILURE, status == OperationsReportStatus.FAILED
                            ? ExecutionEventType.RUN_FAILED : ExecutionEventType.RUN_COMPLETED,
                    status == OperationsReportStatus.FAILED ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED,
                    "运营日报生成被服务重启中断");
            String summary = status == OperationsReportStatus.PARTIAL
                    ? includeArtifact ? "报告生成被中断，已保留完成章节"
                            : "报告生成被中断，已保留完成章节；下载文件超出容量限制"
                    : "报告生成被中断";
            OperationsDailyReport provisional = report.copy(status, report.startedAt(), now,
                    report.asOf(), summary, sections,
                    report.evidence(), report.sourceReferences(), null, trace);
            OperationsDailyReport.Artifact artifact = status == OperationsReportStatus.PARTIAL && includeArtifact
                    ? renderer.render(provisional, now) : null;
            return report.copy(status, report.startedAt(), now, provisional.asOf(), summary,
                    sections, report.evidence(), report.sourceReferences(), artifact, trace);
        });
    }

    private void authorize(OperationsDailyReport report, String role) {
        String normalized = requireText(role, "role", 30).toUpperCase(Locale.ROOT);
        if (!"ADMIN".equals(normalized) && !report.role().equals(normalized)) {
            throw new SecurityException("role is not allowed to read operations report");
        }
    }

    private void publishNew(OperationsDailyReport report, int firstTraceIndex) {
        for (int index = firstTraceIndex; index < report.traceEvents().size(); index++) {
            OperationsReportTraceRecord trace = report.traceEvents().get(index);
            ExecutionEvent projection = projection(report, trace);
            try {
                events.publish(projection);
            } catch (IllegalArgumentException | IllegalStateException duplicateOrClosed) {
                if (events.history(report.traceId()).stream().noneMatch(projection::equals)) throw duplicateOrClosed;
            }
        }
    }

    private void hydrate(OperationsDailyReport report) {
        events.hydrate(report.traceId(), report.traceEvents().stream()
                .map(trace -> projection(report, trace)).toList());
    }

    private static ExecutionEvent projection(OperationsDailyReport report, OperationsReportTraceRecord trace) {
        return new ExecutionEvent(trace.eventId(), report.traceId(), trace.sequence(), trace.timestamp(),
                ExecutionScenario.OPERATIONS_ANALYSIS, trace.actor(), trace.stage(), trace.eventType(),
                trace.status(), trace.safeSummary(), null);
    }

    private List<OperationsReportTraceRecord> append(List<OperationsReportTraceRecord> current,
                                                     String actor, ExecutionStage stage,
                                                     ExecutionEventType type, ExecutionStatus status,
                                                     String summary) {
        List<OperationsReportTraceRecord> next = new ArrayList<>(current);
        next.add(new OperationsReportTraceRecord(UUID.randomUUID(), current.size() + 1L,
                clock.instant(), actor, stage, type, status, summary));
        return next;
    }

    private static Map<String, Object> timeResolution(AnalysisRunStore.RunRecord record) {
        if (record.timeResolution() == null) return Map.of();
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("status", record.timeResolution().status());
        values.put("fromInclusive", record.timeResolution().fromInclusive() == null
                ? "" : record.timeResolution().fromInclusive().toString());
        values.put("toExclusive", record.timeResolution().toExclusive() == null
                ? "" : record.timeResolution().toExclusive().toString());
        values.put("source", record.timeResolution().source());
        values.put("explanation", record.timeResolution().explanation());
        values.put("empty", record.timeResolution().empty());
        return values;
    }

    private static Instant observationTime(AnalysisRunStore.RunRecord record) {
        // Aggregate queries do not expose a newest-fact timestamp. Keep the
        // source-read capture time instead of overstating the requested upper
        // window bound as measured data freshness.
        return record.updatedAt();
    }

    private static Instant latestAsOf(List<OperationsDailyReport.SourceReference> sources) {
        return sources.stream().map(OperationsDailyReport.SourceReference::asOf).max(Instant::compareTo).orElse(null);
    }

    private static String fingerprint(OperationsReportRequest request, String role) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "operations-report-request-v1");
            update(digest, request.reportType());
            update(digest, role);
            update(digest, request.timezone());
            update(digest, request.timeWindow().fromInclusive().toString());
            update(digest, request.timeWindow().toExclusive().toString());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return value.trim();
    }

    public record Page(List<OperationsDailyReport> content, int page, int size, long totalElements,
                       boolean hasNext) {
        public Page {
            content = List.copyOf(content);
        }
    }
}
