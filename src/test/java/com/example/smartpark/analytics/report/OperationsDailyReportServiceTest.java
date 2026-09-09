package com.example.smartpark.analytics.report;

import com.example.smartpark.analytics.AnalysisRunStore;
import com.example.smartpark.analytics.agent.TimeResolutionMetadata;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.execution.model.ExecutionScenario;
import com.example.smartpark.execution.model.ExecutionStage;
import com.example.smartpark.execution.model.ExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsDailyReportServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @TempDir Path temp;

    @Test
    void completesWithDurableEvidenceTraceAndSnapshotDownload() {
        AtomicInteger sourceValue = new AtomicInteger(100);
        AtomicInteger calls = new AtomicInteger();
        List<OperationsReportRequest> requests = new ArrayList<>();
        InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) -> {
            calls.incrementAndGet();
            requests.add(request);
            return CompletableFuture.completedFuture(completed(section.question(), sourceValue.get()));
        }, publisher);

        var started = service.start(service.defaultRequest(), "daily-key", "demo-role:OPERATOR", "OPERATOR");
        OperationsDailyReport report = service.get(started.report().reportId(), "OPERATOR");
        sourceValue.set(200);
        OperationsDailyReport historical = service.get(report.reportId(), "OPERATOR");

        assertThat(report.status()).isEqualTo(OperationsReportStatus.COMPLETED);
        assertThat(report.reportId()).isNotEqualTo(report.runId());
        assertThat(report.sections()).allSatisfy(section -> {
            assertThat(section.status()).isEqualTo(OperationsReportSectionStatus.COMPLETED);
            assertThat(section.evidenceReferences()).hasSize(1);
            assertThat(section.sourceReferences()).hasSize(1);
        });
        assertThat(requests).allMatch(request -> request.timeWindow().equals(service.defaultRequest().timeWindow()));
        assertThat(historical.sections().get(0).rows().get(0)).containsExactly(100);
        assertThat(service.download(report.reportId(), "OPERATOR").content()).contains("100").doesNotContain("200");
        assertThat(calls).hasValue(3);
        assertThat(publisher.history(report.traceId())).extracting(event -> event.eventType())
                .containsExactly(ExecutionEventType.RUN_STARTED,
                        ExecutionEventType.STEP_STARTED, ExecutionEventType.STEP_COMPLETED,
                        ExecutionEventType.STEP_STARTED, ExecutionEventType.STEP_COMPLETED,
                        ExecutionEventType.STEP_STARTED, ExecutionEventType.STEP_COMPLETED,
                        ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void idempotentReplayReturnsSameReportAndConflictingPayloadFails() {
        AtomicInteger calls = new AtomicInteger();
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(completed(section.question(), 1));
        }, new InMemoryExecutionEventPublisher());
        OperationsReportRequest request = service.defaultRequest();

        var first = service.start(request, "same-key", "demo-role:OPERATOR", "OPERATOR");
        var replay = service.start(request, "same-key", "demo-role:OPERATOR", "OPERATOR");

        assertThat(replay.created()).isFalse();
        assertThat(replay.report().reportId()).isEqualTo(first.report().reportId());
        assertThat(calls).hasValue(3);
        OperationsReportRequest different = new OperationsReportRequest(OperationsReportRequest.DAILY,
                new OperationsReportRequest.TimeWindow(NOW.minusSeconds(7200), NOW), "Asia/Shanghai");
        assertThatThrownBy(() -> service.start(different, "same-key", "demo-role:OPERATOR", "OPERATOR"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsGeneratingVisibleAndBoundsDuplicateOrConcurrentCreation() {
        CompletableFuture<AnalysisRunStore.RunRecord> pending = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) -> {
            calls.incrementAndGet();
            return pending;
        }, new InMemoryExecutionEventPublisher());

        var started = service.start(service.defaultRequest(), "network-retry-key",
                "demo-role:OPERATOR", "OPERATOR");
        var replay = service.start(service.defaultRequest(), "network-retry-key",
                "demo-role:OPERATOR", "OPERATOR");

        assertThat(service.get(started.report().reportId(), "OPERATOR").status())
                .isEqualTo(OperationsReportStatus.GENERATING);
        assertThat(replay.created()).isFalse();
        assertThat(replay.report().reportId()).isEqualTo(started.report().reportId());
        assertThat(calls).hasValue(1);
        assertThatThrownBy(() -> service.start(service.defaultRequest(), "other-window-key",
                "demo-role:OPERATOR", "OPERATOR"))
                .isInstanceOf(OperationsReportCapacityException.class);
        assertThatThrownBy(() -> service.start(service.defaultRequest(), "x".repeat(129),
                "demo-role:OPERATOR", "OPERATOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOnlyCreationWhenAnalyticsGenerationIsUnavailable() {
        Path state = temp.resolve("reports.json");
        OperationsDailyReportStore store = new OperationsDailyReportStore(state,
                new ObjectMapper().findAndRegisterModules(), 20, 1, 512 * 1024, 256 * 1024);
        OperationsDailyReportService service = new OperationsDailyReportService(
                (section, request) -> CompletableFuture.failedFuture(new AssertionError("must not run")),
                store, new InMemoryExecutionEventPublisher(), new OperationsReportRenderer(), CLOCK, false);

        assertThat(service.list("OPERATOR", null, null, null, null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.start(service.defaultRequest(), "disabled-key",
                "demo-role:OPERATOR", "OPERATOR"))
                .isInstanceOf(OperationsReportUnavailableException.class);
        assertThat(store.all()).isEmpty();
    }

    @Test
    void marksUnavailableSectionAsPartialWithoutLeakingFailure() {
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) -> {
            if ("PARKING_UTILIZATION".equals(section.id())) {
                return CompletableFuture.failedFuture(new IllegalStateException("jdbc:password=secret"));
            }
            return CompletableFuture.completedFuture(completed(section.question(), 1));
        }, new InMemoryExecutionEventPublisher());

        OperationsDailyReport report = service.get(service.start(service.defaultRequest(), "partial-key",
                "demo-role:OPERATOR", "OPERATOR").report().reportId(), "OPERATOR");

        assertThat(report.status()).isEqualTo(OperationsReportStatus.PARTIAL);
        assertThat(report.sections().get(1).status()).isEqualTo(OperationsReportSectionStatus.UNAVAILABLE);
        assertThat(report.sections().get(1).partialReason()).isEqualTo("REPORT_SECTION_UNAVAILABLE");
        assertThat(report.toString()).doesNotContain("password", "jdbc:", "secret");
        assertThat(report.artifact()).isNotNull();
        assertThat(report.traceEvents()).last().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_COMPLETED);
            assertThat(event.status().name()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void failsWithoutArtifactWhenNoSectionIsAvailable() {
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) ->
                CompletableFuture.failedFuture(new IllegalStateException("password=secret")),
                new InMemoryExecutionEventPublisher());

        OperationsDailyReport report = service.get(service.start(service.defaultRequest(), "failed-key",
                "demo-role:OPERATOR", "OPERATOR").report().reportId(), "OPERATOR");

        assertThat(report.status()).isEqualTo(OperationsReportStatus.FAILED);
        assertThat(report.artifact()).isNull();
        assertThat(report.sections()).allMatch(section ->
                section.status() == OperationsReportSectionStatus.UNAVAILABLE
                        && "REPORT_SECTION_UNAVAILABLE".equals(section.partialReason()));
        assertThat(report.traceEvents()).last().extracting(OperationsReportTraceRecord::eventType)
                .isEqualTo(ExecutionEventType.RUN_FAILED);
        assertThat(report.toString()).doesNotContain("password", "secret");
    }

    @Test
    void restartRecoversGeneratingReportAsFailedAndKeepsIdempotency() {
        Path state = temp.resolve("reports.json");
        CompletableFuture<AnalysisRunStore.RunRecord> pending = new CompletableFuture<>();
        OperationsDailyReportService first = service(state, (section, request) -> pending,
                new InMemoryExecutionEventPublisher());
        UUID reportId = first.start(first.defaultRequest(), "restart-key", "demo-role:OPERATOR", "OPERATOR")
                .report().reportId();

        OperationsDailyReportService restarted = service(state,
                (section, request) -> CompletableFuture.completedFuture(completed(section.question(), 2)),
                new InMemoryExecutionEventPublisher());
        OperationsDailyReport recovered = restarted.get(reportId, "OPERATOR");

        assertThat(recovered.status()).isEqualTo(OperationsReportStatus.FAILED);
        assertThat(recovered.sections().get(0).partialReason()).isEqualTo("GENERATION_INTERRUPTED");
        assertThat(recovered.traceEvents()).last().extracting(event -> event.eventType())
                .isEqualTo(ExecutionEventType.RUN_FAILED);
        assertThat(restarted.start(restarted.defaultRequest(), "restart-key", "demo-role:OPERATOR", "OPERATOR")
                .report().reportId()).isEqualTo(reportId);
    }

    @Test
    void restartRecoversCompletedSectionsAsPartialWithDurableArtifactAndTrace() {
        Path state = temp.resolve("reports.json");
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<AnalysisRunStore.RunRecord> pending = new CompletableFuture<>();
        OperationsDailyReportService first = service(state, (section, request) ->
                        calls.getAndIncrement() == 0
                                ? CompletableFuture.completedFuture(completed(section.question(), 100)) : pending,
                new InMemoryExecutionEventPublisher());
        UUID reportId = first.start(first.defaultRequest(), "partial-restart-key",
                "demo-role:OPERATOR", "OPERATOR").report().reportId();

        InMemoryExecutionEventPublisher restartedPublisher = new InMemoryExecutionEventPublisher();
        OperationsDailyReportService restarted = service(state,
                (section, request) -> CompletableFuture.completedFuture(completed(section.question(), 200)),
                restartedPublisher);
        OperationsDailyReport recovered = restarted.get(reportId, "OPERATOR");

        assertThat(recovered.status()).isEqualTo(OperationsReportStatus.PARTIAL);
        assertThat(recovered.sections().get(0).rows().get(0)).containsExactly(100);
        assertThat(recovered.sections().subList(1, 3)).allMatch(section ->
                "GENERATION_INTERRUPTED".equals(section.partialReason()));
        assertThat(recovered.artifact().content()).contains("100").doesNotContain("200");
        assertThat(restartedPublisher.history(recovered.traceId())).last().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_COMPLETED);
            assertThat(event.status().name()).isEqualTo("SUCCEEDED");
        });
    }

    @Test
    void historyIsPagedFilteredAndRoleScoped() {
        OperationsDailyReportService service = service(temp.resolve("reports.json"), (section, request) ->
                CompletableFuture.completedFuture(completed(section.question(), 1)),
                new InMemoryExecutionEventPublisher());
        UUID reportId = service.start(service.defaultRequest(), "history-key", "demo-role:OPERATOR", "OPERATOR")
                .report().reportId();
        UUID secondReportId = service.start(service.defaultRequest(), "history-key-2",
                "demo-role:OPERATOR", "OPERATOR").report().reportId();

        OperationsDailyReportService.Page firstPage = service.list("OPERATOR", OperationsReportRequest.DAILY,
                OperationsReportStatus.COMPLETED, NOW.minusSeconds(60), NOW.plusSeconds(60), 0, 1);
        assertThat(firstPage.content()).hasSize(1);
        assertThat(firstPage.totalElements()).isEqualTo(2);
        assertThat(firstPage.hasNext()).isTrue();
        assertThat(service.list("OPERATOR", OperationsReportRequest.DAILY,
                OperationsReportStatus.COMPLETED, NOW.minusSeconds(60), NOW.plusSeconds(60), 1, 1).content())
                .hasSize(1).extracting(OperationsDailyReport::reportId)
                .doesNotContain(firstPage.content().get(0).reportId());
        assertThat(service.list("OPERATOR", null, OperationsReportStatus.FAILED,
                null, null, 0, 20).content()).isEmpty();
        assertThat(service.list("OPERATOR", null, null,
                NOW.plusSeconds(1), null, 0, 20).content()).isEmpty();
        assertThatThrownBy(() -> service.get(reportId, "APPROVER")).isInstanceOf(SecurityException.class);
        assertThat(service.get(reportId, "ADMIN").reportId()).isEqualTo(reportId);
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), "OPERATOR"))
                .isInstanceOf(java.util.NoSuchElementException.class);
        assertThatThrownBy(() -> service.list("OPERATOR", null, null, null, null, 0, 51))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.list("OPERATOR", null, null, null, null, 0, 20).content())
                .extracting(OperationsDailyReport::reportId).containsExactlyInAnyOrder(reportId, secondReportId);
        List<UUID> expectedOrder = java.util.stream.Stream.of(reportId, secondReportId).sorted().toList();
        assertThat(service.list("OPERATOR", null, null, null, null, 0, 20).content())
                .extracting(OperationsDailyReport::reportId).containsExactlyElementsOf(expectedOrder);
    }

    @Test
    void durableTraceArchiveReplaysTerminalStateAndEnforcesRole() {
        Path state = temp.resolve("reports.json");
        OperationsDailyReportService service = service(state, (section, request) ->
                CompletableFuture.completedFuture(completed(section.question(), 1)),
                new InMemoryExecutionEventPublisher());
        OperationsDailyReport report = service.get(service.start(service.defaultRequest(), "trace-key",
                "demo-role:OPERATOR", "OPERATOR").report().reportId(), "OPERATOR");
        OperationsReportTraceArchive archive = new OperationsReportTraceArchive(new OperationsDailyReportStore(
                state, new ObjectMapper().findAndRegisterModules(), 20, 1, 512 * 1024, 256 * 1024));

        assertThat(archive.history(report.traceId())).last().satisfies(event -> {
            assertThat(event.eventType()).isEqualTo(ExecutionEventType.RUN_COMPLETED);
            assertThat(event.isTerminal()).isTrue();
        });
        assertThatThrownBy(() -> archive.authorize(report.traceId(), "APPROVER"))
                .isInstanceOf(SecurityException.class);
        archive.authorize(report.traceId(), "ADMIN");
    }

    @Test
    void traceArchiveRejectsAnOrphanedLiveReportProjection() {
        UUID traceId = UUID.randomUUID();
        InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();
        publisher.publish(new ExecutionEvent(
                UUID.randomUUID(), traceId, 0, NOW, ExecutionScenario.OPERATIONS_ANALYSIS,
                "operations-report", ExecutionStage.COMPLETION,
                ExecutionEventType.RUN_COMPLETED, ExecutionStatus.SUCCEEDED,
                "done", null));
        OperationsReportTraceArchive archive = new OperationsReportTraceArchive(
                new OperationsDailyReportStore(temp.resolve("empty-reports.json"),
                        new ObjectMapper().findAndRegisterModules(), 20, 1, 512 * 1024, 256 * 1024),
                publisher);

        assertThatThrownBy(() -> archive.authorize(traceId, "VIEWER"))
                .isInstanceOf(java.util.NoSuchElementException.class)
                .hasMessageContaining("Unknown operations report trace");
    }

    private OperationsDailyReportService service(Path state, OperationsReportSectionRunner runner,
                                                 InMemoryExecutionEventPublisher publisher) {
        OperationsDailyReportStore store = new OperationsDailyReportStore(state,
                new ObjectMapper().findAndRegisterModules(), 20, 1, 512 * 1024, 256 * 1024);
        return new OperationsDailyReportService(runner, store, publisher, new OperationsReportRenderer(), CLOCK);
    }

    private static AnalysisRunStore.RunRecord completed(String question, int value) {
        UUID runId = UUID.randomUUID();
        return new AnalysisRunStore.RunRecord(runId, question, "COMPLETED", List.of(), List.of(),
                "安全摘要", 1, false, 3, null, NOW.minusSeconds(1), NOW,
                List.of("metric"), List.of(List.of((Object) value)),
                TimeResolutionMetadata.defaultLookback(NOW.minusSeconds(3600), NOW));
    }
}
