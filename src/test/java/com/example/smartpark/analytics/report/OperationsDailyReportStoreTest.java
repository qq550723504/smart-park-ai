package com.example.smartpark.analytics.report;

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
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OperationsDailyReportStoreTest {
    private static final Instant NOW = Instant.parse("2026-09-09T02:00:00Z");
    @TempDir Path temp;

    @Test
    void persistsIdempotencyAndSnapshotsAcrossRestart() {
        Path state = temp.resolve("reports.json");
        OperationsDailyReportStore first = store(state, 4, 1, 64 * 1024, 16 * 1024);
        OperationsDailyReportStore.StartResult created = first.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED));

        OperationsDailyReportStore restarted = store(state, 4, 1, 64 * 1024, 16 * 1024);
        OperationsDailyReportStore.StartResult replay = restarted.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED));

        assertThat(replay.created()).isFalse();
        assertThat(replay.report().reportId()).isEqualTo(created.report().reportId());
        assertThat(restarted.find(created.report().reportId())).contains(created.report());
        assertThatThrownBy(() -> restarted.createOrGet("key-1", "different",
                () -> report("key-1", "different", OperationsReportStatus.REQUESTED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void failsClosedWhenAllRetainedReportsAreActive() {
        OperationsDailyReportStore store = store(temp.resolve("reports.json"), 1, 1, 64 * 1024, 16 * 1024);
        store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.GENERATING));

        assertThatThrownBy(() -> store.createOrGet("key-2", "fingerprint-2",
                () -> report("key-2", "fingerprint-2", OperationsReportStatus.REQUESTED)))
                .isInstanceOf(OperationsReportCapacityException.class);
    }

    @Test
    void rejectsOversizedArtifactBeforeReplacingDurableState() {
        OperationsDailyReportStore store = store(temp.resolve("reports.json"), 2, 1, 16 * 1024, 1024);
        OperationsDailyReport created = store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED)).report();
        String content = "x".repeat(1025);

        assertThatThrownBy(() -> store.update(created.reportId(), report -> report.copy(
                OperationsReportStatus.COMPLETED, NOW, NOW, NOW, "done", report.sections(), List.of(), List.of(),
                new OperationsDailyReport.Artifact(UUID.randomUUID(), "MARKDOWN", "safe.md",
                        "text/markdown", content.length(), NOW, "checksum", "v1", content), List.of())))
                .isInstanceOf(OperationsReportCapacityException.class);
        assertThat(store.find(created.reportId()).orElseThrow().status()).isEqualTo(OperationsReportStatus.REQUESTED);
    }

    @Test
    void evictsOldestTerminalReportWithinRetainedBound() {
        InMemoryExecutionEventPublisher publisher = new InMemoryExecutionEventPublisher();
        OperationsDailyReportStore store = new OperationsDailyReportStore(temp.resolve("reports.json"),
                new ObjectMapper().findAndRegisterModules(), 2, 1, 64 * 1024, 16 * 1024,
                traceId -> publisher.remove(traceId));
        OperationsDailyReport oldest = store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.COMPLETED)).report();
        publisher.publish(new ExecutionEvent(UUID.randomUUID(), oldest.traceId(), 0, NOW,
                ExecutionScenario.OPERATIONS_ANALYSIS, "operations-report", ExecutionStage.COMPLETION,
                ExecutionEventType.RUN_COMPLETED, ExecutionStatus.SUCCEEDED, "done", null));
        OperationsDailyReport second = store.createOrGet("key-2", "fingerprint-2",
                () -> report("key-2", "fingerprint-2", OperationsReportStatus.COMPLETED)).report();
        OperationsDailyReport newest = store.createOrGet("key-3", "fingerprint-3",
                () -> report("key-3", "fingerprint-3", OperationsReportStatus.REQUESTED)).report();

        assertThat(store.find(oldest.reportId())).isEmpty();
        assertThat(store.all()).extracting(OperationsDailyReport::reportId)
                .containsExactly(second.reportId(), newest.reportId());
        assertThat(publisher.history(oldest.traceId())).isEmpty();
    }

    @Test
    void rejectsOversizedStructuredReportAndKeepsPriorRevision() {
        OperationsDailyReportStore store = store(temp.resolve("reports.json"), 2, 1, 4096, 1024);
        OperationsDailyReport created = store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED)).report();

        assertThatThrownBy(() -> store.update(created.reportId(), report -> report.copy(
                OperationsReportStatus.GENERATING, NOW, null, null, "x".repeat(8192),
                report.sections(), report.evidence(), report.sourceReferences(), null, report.traceEvents())))
                .isInstanceOf(OperationsReportCapacityException.class);
        assertThat(store.find(created.reportId()).orElseThrow().revision()).isZero();
    }

    @Test
    void rejectsStateFileThatWouldBeUnreadableOnRestartAndKeepsPriorSnapshot() throws Exception {
        Path state = temp.resolve("bounded-state.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OperationsDailyReportStore store = new OperationsDailyReportStore(state, mapper,
                2, 1, 8192, 1024, (source, target) -> java.nio.file.Files.move(source, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING),
                ignored -> { }, 9000);
        OperationsDailyReport first = store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.COMPLETED).copy(
                        OperationsReportStatus.COMPLETED, NOW, NOW, NOW, "x".repeat(4000),
                        List.of(), List.of(), List.of(), null, List.of())).report();
        long acceptedSize = java.nio.file.Files.size(state);

        assertThatThrownBy(() -> store.createOrGet("key-2", "fingerprint-2",
                () -> report("key-2", "fingerprint-2", OperationsReportStatus.COMPLETED).copy(
                        OperationsReportStatus.COMPLETED, NOW, NOW, NOW, "y".repeat(4000),
                        List.of(), List.of(), List.of(), null, List.of())))
                .isInstanceOf(OperationsReportCapacityException.class)
                .hasMessageContaining("durable file byte limit");

        assertThat(java.nio.file.Files.size(state)).isEqualTo(acceptedSize).isLessThanOrEqualTo(9000);
        assertThat(store.all()).extracting(OperationsDailyReport::reportId).containsExactly(first.reportId());
        OperationsDailyReportStore restarted = new OperationsDailyReportStore(state, mapper,
                2, 1, 8192, 1024, (source, target) -> java.nio.file.Files.move(source, target,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING),
                ignored -> { }, 9000);
        assertThat(restarted.all()).extracting(OperationsDailyReport::reportId).containsExactly(first.reportId());
    }

    @Test
    void atomicReplaceFailureDoesNotPublishNewInMemoryRevision() {
        Path state = temp.resolve("reports.json");
        OperationsDailyReport created = store(state, 2, 1, 64 * 1024, 16 * 1024)
                .createOrGet("key-1", "fingerprint-1",
                        () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED)).report();
        OperationsDailyReportStore failing = new OperationsDailyReportStore(state,
                new ObjectMapper().findAndRegisterModules(), 2, 1, 64 * 1024, 16 * 1024,
                (source, target) -> { throw new IOException("simulated replace failure"); });

        assertThatThrownBy(() -> failing.update(created.reportId(), report -> report.copy(
                OperationsReportStatus.GENERATING, NOW, null, null, "running",
                report.sections(), report.evidence(), report.sourceReferences(), null, report.traceEvents())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("persist");
        assertThat(failing.find(created.reportId()).orElseThrow().revision()).isZero();
    }

    @Test
    void preservesButDoesNotExposeUnsupportedSchemaRecords() throws Exception {
        Path state = temp.resolve("reports.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        OperationsDailyReport future = new OperationsDailyReport(
                UUID.randomUUID(), OperationsReportRequest.DAILY, "future", OperationsReportStatus.COMPLETED,
                "demo-role:OPERATOR", "OPERATOR", NOW, NOW, NOW,
                new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW), "Asia/Shanghai", NOW,
                "future", List.of(), List.of(), List.of(), UUID.randomUUID(), UUID.randomUUID(),
                99, "future-v1", null, "future-key", "future-fingerprint", 1, List.of());
        mapper.writeValue(state.toFile(), List.of(future));

        OperationsDailyReportStore store = store(state, 3, 1, 64 * 1024, 16 * 1024);
        assertThat(store.all()).isEmpty();
        assertThat(store.isUnsupported(future.reportId())).isTrue();
        assertThatThrownBy(() -> store.createOrGet("future-key", "another-fingerprint",
                () -> report("future-key", "another-fingerprint", OperationsReportStatus.REQUESTED)))
                .isInstanceOf(IllegalStateException.class).hasMessageStartingWith("Idempotency-Key");
        store.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.REQUESTED));

        assertThat(mapper.readTree(state.toFile())).hasSize(2);
        assertThat(mapper.readTree(state.toFile()).findValuesAsText("generationVersion"))
                .contains("future-v1", "v1");
    }

    @Test
    void loweringRetentionCompactsOldestTerminalRecordsDuringLoad() throws Exception {
        Path state = temp.resolve("lowered-retention.json");
        OperationsDailyReportStore first = store(state, 3, 1, 64 * 1024, 16 * 1024);
        OperationsDailyReport oldest = first.createOrGet("key-1", "fingerprint-1",
                () -> report("key-1", "fingerprint-1", OperationsReportStatus.COMPLETED)).report();
        OperationsDailyReport second = first.createOrGet("key-2", "fingerprint-2",
                () -> report("key-2", "fingerprint-2", OperationsReportStatus.COMPLETED)).report();
        OperationsDailyReport newest = first.createOrGet("key-3", "fingerprint-3",
                () -> report("key-3", "fingerprint-3", OperationsReportStatus.COMPLETED)).report();

        OperationsDailyReportStore reduced = store(state, 2, 1, 64 * 1024, 16 * 1024);

        assertThat(reduced.find(oldest.reportId())).isEmpty();
        assertThat(reduced.all()).extracting(OperationsDailyReport::reportId)
                .containsExactly(second.reportId(), newest.reportId());
        assertThat(new ObjectMapper().readTree(state.toFile())).hasSize(2);
    }

    @Test
    void loweringRetentionReadsAndCompactsAValidSetLargerThanTheDefault() throws Exception {
        Path state = temp.resolve("lowered-from-large-retention.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        List<OperationsDailyReport> prior = new java.util.ArrayList<>();
        for (int index = 0; index < 250; index++) {
            OperationsDailyReport base = report("large-key-" + index, "large-fingerprint-" + index,
                    OperationsReportStatus.COMPLETED);
            prior.add(base.copy(base.status(), base.startedAt(), base.completedAt(), base.asOf(),
                    "x".repeat(6000), base.sections(), base.evidence(), base.sourceReferences(),
                    base.artifact(), base.traceEvents()));
        }
        mapper.writeValue(state.toFile(), prior);
        assertThat(java.nio.file.Files.size(state)).isGreaterThan(200L * 8192);

        OperationsDailyReportStore reduced = store(state, 2, 1, 8192, 1024);

        assertThat(reduced.all()).extracting(OperationsDailyReport::reportId)
                .containsExactly(prior.get(248).reportId(), prior.get(249).reportId());
        assertThat(mapper.readTree(state.toFile())).hasSize(2);
    }

    private OperationsDailyReportStore store(Path state, int retained, int active, int reportBytes, int artifactBytes) {
        return new OperationsDailyReportStore(state, new ObjectMapper().findAndRegisterModules(),
                retained, active, reportBytes, artifactBytes);
    }

    static OperationsDailyReport report(String key, String fingerprint, OperationsReportStatus status) {
        UUID reportId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        return new OperationsDailyReport(reportId, OperationsReportRequest.DAILY, "智慧园区运营日报", status,
                "demo-role:OPERATOR", "OPERATOR", NOW, status == OperationsReportStatus.REQUESTED ? null : NOW,
                status.isTerminal() ? NOW : null,
                new OperationsReportRequest.TimeWindow(NOW.minusSeconds(3600), NOW), "Asia/Shanghai",
                status.isTerminal() ? NOW : null, "snapshot", List.of(), List.of(), List.of(), runId, runId,
                1, "v1", null, key, fingerprint, 0, List.of());
    }
}
