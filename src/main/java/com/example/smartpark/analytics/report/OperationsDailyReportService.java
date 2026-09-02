package com.example.smartpark.analytics.report;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.execution.ExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Orchestrates the fixed, read-only operations report sections. */
public class OperationsDailyReportService {

    private final OperationsReportSectionRunner sectionRunner;
    private final OperationsDailyReportStore store;
    private final ExecutionEventPublisher events;
    private final Clock clock;

    public OperationsDailyReportService(OperationsReportSectionRunner sectionRunner,
                                        OperationsDailyReportStore store,
                                        ExecutionEventPublisher events,
                                        Clock clock) {
        this.sectionRunner = sectionRunner;
        this.store = store;
        this.events = events;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public OperationsDailyReport start() {
        if (!store.tryAcquireRun()) {
            throw new IllegalStateException("已有正在生成的运营日报，请等待完成后再启动");
        }
        UUID runId = UUID.randomUUID();
        OperationsDailyReport report;
        try {
            report = store.create(runId, clock.instant());
            publish(runId, ExecutionStage.INITIALIZATION, ExecutionEventType.RUN_STARTED,
                    ExecutionStatus.RUNNING, "运营日报开始");
            runSection(runId, 0);
            return report;
        } catch (RuntimeException failure) {
            store.releaseRun();
            throw failure;
        }
    }

    public OperationsDailyReport get(UUID runId) {
        return store.get(runId).orElseThrow(() -> new NoSuchElementException("Unknown report: " + runId));
    }

    private void runSection(UUID runId, int index) {
        OperationsDailyReport current;
        try {
            current = get(runId);
        } catch (RuntimeException failure) {
            finishFailure(runId, "REPORT_STATE");
            return;
        }
        List<OperationsReportSection> definitions = OperationsDailyReportDefinition.sections();
        if (index >= definitions.size()) {
            finishReport(runId, current);
            return;
        }

        OperationsReportSection section = definitions.get(index);
        List<OperationsDailyReport.SectionResult> runningSections = new ArrayList<>(current.sections());
        runningSections.set(index, runningSections.get(index).running());
        store.update(current.withSections("RUNNING", clock.instant(), runningSections));
        publish(runId, ExecutionStage.ANALYSIS, ExecutionEventType.NODE_STARTED,
                ExecutionStatus.RUNNING, section.title());

        CompletableFuture<AnalysisRunStore.RunRecord> execution;
        try {
            execution = sectionRunner.run(section);
            if (execution == null) throw new IllegalStateException("section runner returned null");
        } catch (RuntimeException failure) {
            completeSection(runId, index, section, null, failure);
            return;
        }
        execution.whenComplete((record, failure) -> completeSection(runId, index, section, record, failure));
    }

    private void completeSection(UUID runId, int index, OperationsReportSection section,
                                 AnalysisRunStore.RunRecord record, Throwable failure) {
        OperationsDailyReport current = get(runId);
        OperationsDailyReport.SectionResult result;
        if (failure != null) {
            result = OperationsDailyReport.SectionResult.pending(section)
                    .failed("REPORT_SECTION_EXECUTION");
        } else if (record == null) {
            result = OperationsDailyReport.SectionResult.pending(section)
                    .failed("REPORT_SECTION_EMPTY_RESULT");
        } else if ("COMPLETED".equals(record.status())) {
            result = completedSection(section, record);
        } else if ("NEEDS_CLARIFICATION".equals(record.status())) {
            result = OperationsDailyReport.SectionResult.pending(section)
                    .failed("REPORT_CLARIFICATION_REQUIRED");
        } else {
            result = OperationsDailyReport.SectionResult.pending(section)
                    .failed("REPORT_SECTION_FAILED");
        }
        List<OperationsDailyReport.SectionResult> sections = new ArrayList<>(current.sections());
        sections.set(index, result);
        store.update(current.withSections("RUNNING", clock.instant(), sections));
        publish(runId, ExecutionStage.ANALYSIS, ExecutionEventType.NODE_COMPLETED,
                result.status() == OperationsReportSectionStatus.COMPLETED
                        ? ExecutionStatus.SUCCEEDED : ExecutionStatus.FAILED,
                section.title());
        runSection(runId, index + 1);
    }

    private void finishReport(UUID runId, OperationsDailyReport current) {
        long completed = current.sections().stream()
                .filter(section -> section.status() == OperationsReportSectionStatus.COMPLETED).count();
        long failed = current.sections().stream()
                .filter(section -> section.status() == OperationsReportSectionStatus.FAILED).count();
        String status = completed == current.sections().size() ? "COMPLETED"
                : failed == current.sections().size() ? "FAILED" : "PARTIAL";
        OperationsDailyReport finalReport = store.update(current.withStatus(status, clock.instant()));
        store.releaseRun();
        ExecutionStatus eventStatus = "FAILED".equals(status) ? ExecutionStatus.FAILED : ExecutionStatus.SUCCEEDED;
        ExecutionEventType eventType = "FAILED".equals(status) ? ExecutionEventType.FAILED : ExecutionEventType.COMPLETED;
        publish(runId, "FAILED".equals(status) ? ExecutionStage.FAILURE : ExecutionStage.COMPLETION,
                eventType, eventStatus,
                "PARTIAL".equals(finalReport.status()) ? "运营日报完成，部分章节失败" : "运营日报完成");
    }

    private void finishFailure(UUID runId, String stage) {
        try {
            OperationsDailyReport current = get(runId);
            store.update(current.withStatus("FAILED", clock.instant()));
        } finally {
            store.releaseRun();
            publish(runId, ExecutionStage.FAILURE, ExecutionEventType.FAILED,
                    ExecutionStatus.FAILED, "运营日报执行失败：" + stage);
        }
    }

    private static OperationsDailyReport.SectionResult completedSection(
            OperationsReportSection section, AnalysisRunStore.RunRecord record) {
        Map<String, Object> timeResolution = record.timeResolution() == null ? Map.of() : Map.of(
                "status", record.timeResolution().status(),
                "fromInclusive", record.timeResolution().fromInclusive() == null ? "" : record.timeResolution().fromInclusive().toString(),
                "toExclusive", record.timeResolution().toExclusive() == null ? "" : record.timeResolution().toExclusive().toString(),
                "source", record.timeResolution().source(),
                "explanation", record.timeResolution().explanation(),
                "empty", record.timeResolution().empty());
        return new OperationsDailyReport.SectionResult(section.id(), section.title(), section.question(),
                OperationsReportSectionStatus.COMPLETED, record.summary(), record.rowCount(), record.truncated(),
                record.columns(), record.rows(), timeResolution, null);
    }

    private void publish(UUID runId, ExecutionStage stage, ExecutionEventType type,
                         ExecutionStatus status, String safeSummary) {
        if (events == null) return;
        events.publish(new ExecutionEvent(UUID.randomUUID(), runId, 0, clock.instant(),
                ExecutionScenario.OPERATIONS_ANALYSIS, "operations-report", stage, type, status, safeSummary, null));
    }
}
