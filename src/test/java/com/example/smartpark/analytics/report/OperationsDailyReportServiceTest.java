package com.example.smartpark.analytics.report;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsDailyReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-02T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void runsTheThreeFixedSectionsInOrderAndCompletesTheReport() {
        List<String> questions = new ArrayList<>();
        InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();
        OperationsDailyReportService service = service(section -> {
            questions.add(section.question());
            return CompletableFuture.completedFuture(completed(UUID.randomUUID(), section.question()));
        }, publisher);

        OperationsDailyReport accepted = service.start();
        OperationsDailyReport report = service.get(accepted.runId());

        assertThat(report.status()).isEqualTo("COMPLETED");
        assertThat(questions).containsExactly(
                "过去5天各楼宇能耗基线偏差",
                "过去5天各停车区域停车利用率",
                "过去5天高风险告警数量");
        assertThat(report.sections()).allSatisfy(section ->
                assertThat(section.status()).isEqualTo(OperationsReportSectionStatus.COMPLETED));
        List<ExecutionEvent> events = publisher.history(report.runId());
        assertThat(events).extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.NODE_STARTED, ExecutionEventType.NODE_COMPLETED,
                        ExecutionEventType.NODE_STARTED, ExecutionEventType.NODE_COMPLETED,
                        ExecutionEventType.NODE_STARTED, ExecutionEventType.NODE_COMPLETED,
                        ExecutionEventType.COMPLETED);
    }

    @Test
    void continuesAfterASectionFailureAndMarksTheReportPartial() {
        List<String> questions = new ArrayList<>();
        OperationsDailyReportService service = service(section -> {
            questions.add(section.id());
            if ("PARKING_UTILIZATION".equals(section.id())) {
                return CompletableFuture.completedFuture(failed(UUID.randomUUID(), "private SQL text"));
            }
            return CompletableFuture.completedFuture(completed(UUID.randomUUID(), "安全摘要"));
        }, new InMemoryExecutionEventPublisher());

        OperationsDailyReport accepted = service.start();
        OperationsDailyReport report = service.get(accepted.runId());

        assertThat(report.status()).isEqualTo("PARTIAL");
        assertThat(questions).containsExactly("ENERGY_BASELINE", "PARKING_UTILIZATION", "ALERT_RISK");
        assertThat(report.sections().get(1).status()).isEqualTo(OperationsReportSectionStatus.FAILED);
        assertThat(report.sections().get(1).failureStage()).isEqualTo("REPORT_SECTION_FAILED");
        assertThat(report.sections().get(0).summary()).isEqualTo("安全摘要");
    }

    @Test
    void rejectsConcurrentReportAndDoesNotExposeExceptionTextInEvents() {
        CompletableFuture<AnalysisRunStore.RunRecord> pending = new CompletableFuture<>();
        InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();
        AtomicReference<UUID> reportId = new AtomicReference<>();
        AtomicReference<Integer> calls = new AtomicReference<>(0);
        OperationsDailyReportService service = service(section -> {
            if (calls.getAndUpdate(value -> value + 1) == 0) return pending;
            return CompletableFuture.completedFuture(completed(UUID.randomUUID(), section.question()));
        }, publisher);

        OperationsDailyReport report = service.start();
        reportId.set(report.runId());
        assertThatThrownBy(service::start).isInstanceOf(IllegalStateException.class);

        pending.completeExceptionally(new IllegalStateException("secret prompt and SQL"));
        OperationsDailyReport failed = service.get(reportId.get());
        assertThat(failed.status()).isEqualTo("PARTIAL");
        assertThat(failed.sections().get(0).failureStage()).isEqualTo("REPORT_SECTION_EXECUTION");
        assertThat(publisher.history(reportId.get())).allSatisfy(event ->
                assertThat(event.safeSummary()).doesNotContain("secret prompt", "SQL"));
    }

    private OperationsDailyReportService service(OperationsReportSectionRunner runner,
                                                 InMemoryExecutionEventPublisher publisher) {
        return new OperationsDailyReportService(runner,
                new OperationsDailyReportStore(Duration.ofMinutes(30), CLOCK), publisher, CLOCK);
    }

    private static AnalysisRunStore.RunRecord completed(UUID runId, String question) {
        return new AnalysisRunStore.RunRecord(runId, question, "COMPLETED", List.of(), List.of(),
                "安全摘要", 1, false, 3, null, NOW, NOW, List.of("metric"), List.of(List.of(1)));
    }

    private static AnalysisRunStore.RunRecord failed(UUID runId, String stage) {
        return new AnalysisRunStore.RunRecord(runId, "问题", "FAILED", List.of(), List.of(),
                "", 0, false, 3, stage, NOW, NOW, List.of(), List.of());
    }
}
