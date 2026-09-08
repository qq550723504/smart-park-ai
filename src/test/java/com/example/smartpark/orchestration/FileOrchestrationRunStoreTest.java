package com.example.smartpark.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileOrchestrationRunStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void survivesReopenAndRestoresIdempotencyIndexAndTrace() {
        Path file = temporaryDirectory.resolve("runs.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileOrchestrationRunStore first = new FileOrchestrationRunStore(file, mapper);
        OrchestrationRun created = run();
        first.createOrGet("key", "fingerprint", () -> created);
        first.update(created.id(), current -> current.copy(current.status(), current.startedAt(), null,
                current.summary(), current.steps(), current.evidence(), null, null, false,
                List.of(new OrchestrationTraceRecord(UUID.randomUUID(), 1, Instant.now(), "orchestrator",
                        com.example.smartpark.execution.model.ExecutionStage.INITIALIZATION,
                        com.example.smartpark.execution.model.ExecutionEventType.RUN_STARTED,
                        com.example.smartpark.execution.model.ExecutionStatus.RUNNING, "started"))));

        FileOrchestrationRunStore reopened = new FileOrchestrationRunStore(file, mapper);
        OrchestrationRun restored = reopened.find(created.id()).orElseThrow();
        var replay = reopened.createOrGet("key", "fingerprint", FileOrchestrationRunStoreTest::simpleInputRunForStore);

        assertThat(restored.traceEvents()).hasSize(1);
        assertThat(replay.created()).isFalse();
        assertThat(replay.run().id()).isEqualTo(created.id());
        assertThat(new OrchestrationTraceArchive(reopened).history(created.id()))
                .singleElement().satisfies(event -> {
                    assertThat(event.runId()).isEqualTo(created.traceId());
                    assertThat(event.scenario().name()).isEqualTo("ORCHESTRATION");
                    assertThat(event.safeSummary()).isEqualTo("started");
                });
    }

    @Test
    void failedInitialPersistenceDoesNotPoisonTheInMemoryIdempotencyIndex() throws Exception {
        Path blockedParent = temporaryDirectory.resolve("blocked");
        Files.writeString(blockedParent, "not a directory");
        FileOrchestrationRunStore store = new FileOrchestrationRunStore(
                blockedParent.resolve("runs.json"), new ObjectMapper().findAndRegisterModules());

        assertThatThrownBy(() -> store.createOrGet("retry-key", "fingerprint",
                () -> run("retry-key", "fingerprint", OrchestrationStatus.RUNNING,
                        Instant.parse("2026-09-08T00:00:00Z"))))
                .isInstanceOf(IllegalStateException.class);

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        OrchestrationRunStore.StartResult retry = store.createOrGet(
                "retry-key", "fingerprint",
                () -> run("retry-key", "fingerprint", OrchestrationStatus.RUNNING,
                        Instant.parse("2026-09-08T00:00:00Z")));
        assertThat(retry.created()).isTrue();
        assertThat(Files.exists(blockedParent.resolve("runs.json"))).isTrue();
    }

    @Test
    void compactsTheOldestTerminalRunAndItsIdempotencyKeyBeforeAdmission() {
        Path file = temporaryDirectory.resolve("bounded-runs.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileOrchestrationRunStore store = new FileOrchestrationRunStore(file, mapper, 2, 2);
        Instant base = Instant.parse("2026-09-08T00:00:00Z");
        OrchestrationRun oldest = run("oldest", "fp-oldest", OrchestrationStatus.COMPLETED, base);
        OrchestrationRun newer = run("newer", "fp-newer", OrchestrationStatus.COMPLETED, base.plusSeconds(1));
        OrchestrationRun active = run("active", "fp-active", OrchestrationStatus.RUNNING, base.plusSeconds(2));
        store.createOrGet("oldest", "fp-oldest", () -> oldest);
        store.createOrGet("newer", "fp-newer", () -> newer);

        store.createOrGet("active", "fp-active", () -> active);

        assertThat(store.find(oldest.id())).isEmpty();
        assertThat(store.find(newer.id())).contains(newer);
        assertThat(store.find(active.id())).contains(active);
        FileOrchestrationRunStore reopened = new FileOrchestrationRunStore(file, mapper, 2, 2);
        assertThat(reopened.find(oldest.id())).isEmpty();
        assertThat(reopened.createOrGet("oldest", "fp-oldest",
                () -> run("oldest", "fp-oldest", OrchestrationStatus.RUNNING, base.plusSeconds(3))).created())
                .isTrue();
    }

    @Test
    void rejectsNewAdmissionAtActiveCapacityWithoutCallingTheFactoryOrChangingTheSnapshot() throws Exception {
        Path file = temporaryDirectory.resolve("active-capacity.json");
        FileOrchestrationRunStore store = new FileOrchestrationRunStore(
                file, new ObjectMapper().findAndRegisterModules(), 3, 1);
        Instant base = Instant.parse("2026-09-08T00:00:00Z");
        OrchestrationRun active = run("active", "fp-active", OrchestrationStatus.RUNNING, base);
        store.createOrGet("active", "fp-active", () -> active);
        String authoritativeSnapshot = Files.readString(file);
        AtomicBoolean called = new AtomicBoolean();

        assertThatThrownBy(() -> store.createOrGet("rejected", "fp-rejected", () -> {
            called.set(true);
            return run("rejected", "fp-rejected", OrchestrationStatus.RUNNING, base.plusSeconds(1));
        })).isInstanceOf(OrchestrationCapacityException.class);

        assertThat(called).isFalse();
        assertThat(Files.readString(file)).isEqualTo(authoritativeSnapshot);
        assertThat(store.createOrGet("active", "fp-active", FileOrchestrationRunStoreTest::run).created())
                .isFalse();
    }

    @Test
    void compactsLegacyTerminalRunsWhenReopenedWithABoundedPolicy() {
        Path file = temporaryDirectory.resolve("legacy-runs.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Instant base = Instant.parse("2026-09-08T00:00:00Z");
        FileOrchestrationRunStore legacy = new FileOrchestrationRunStore(file, mapper, 3, 3);
        OrchestrationRun oldest = run("oldest", "fp-oldest", OrchestrationStatus.COMPLETED, base);
        OrchestrationRun newer = run("newer", "fp-newer", OrchestrationStatus.FAILED, base.plusSeconds(1));
        OrchestrationRun active = run("active", "fp-active", OrchestrationStatus.RUNNING, base.plusSeconds(2));
        legacy.createOrGet("oldest", "fp-oldest", () -> oldest);
        legacy.createOrGet("newer", "fp-newer", () -> newer);
        legacy.createOrGet("active", "fp-active", () -> active);

        FileOrchestrationRunStore bounded = new FileOrchestrationRunStore(file, mapper, 2, 2);

        assertThat(bounded.find(oldest.id())).isEmpty();
        assertThat(bounded.find(newer.id())).contains(newer);
        assertThat(bounded.find(active.id())).contains(active);
        FileOrchestrationRunStore reopened = new FileOrchestrationRunStore(file, mapper, 2, 2);
        assertThat(reopened.find(oldest.id())).isEmpty();
        assertThat(reopened.find(newer.id())).contains(newer);
        assertThat(reopened.find(active.id())).contains(active);
    }

    @Test
    void unsupportedAtomicReplacementFailsClosedAndPreservesTheAuthoritativeSnapshot() throws Exception {
        Path file = temporaryDirectory.resolve("atomic-runs.json");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        FileOrchestrationRunStore initial = new FileOrchestrationRunStore(file, mapper);
        OrchestrationRun created = run();
        initial.createOrGet("key", "fingerprint", () -> created);
        String authoritativeSnapshot = Files.readString(file);
        FileOrchestrationRunStore unsupported = new FileOrchestrationRunStore(file, mapper,
                (source, target) -> {
                    throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                            "atomic replacement unavailable");
                });

        assertThatThrownBy(() -> unsupported.update(created.id(), current -> current.copy(
                current.status(), current.startedAt(), null, "must not persist",
                current.steps(), current.evidence(), null, null, false, current.traceEvents())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unable to persist orchestration state")
                .hasCauseInstanceOf(AtomicMoveNotSupportedException.class);

        assertThat(unsupported.find(created.id()).orElseThrow().revision()).isEqualTo(created.revision());
        assertThat(Files.readString(file)).isEqualTo(authoritativeSnapshot);
    }

    @Test
    void traceArchiveAllowsOnlyTheOwningRoleOrAdmin() {
        FileOrchestrationRunStore store = new FileOrchestrationRunStore(
                temporaryDirectory.resolve("authorized-runs.json"), new ObjectMapper().findAndRegisterModules());
        OrchestrationRun created = run();
        store.createOrGet("key", "fingerprint", () -> created);
        OrchestrationTraceArchive archive = new OrchestrationTraceArchive(store);

        archive.authorize(created.traceId(), "operator");
        archive.authorize(created.traceId(), "ADMIN");
        assertThatThrownBy(() -> archive.authorize(created.traceId(), "VIEWER"))
                .isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> archive.authorize(created.traceId(), null))
                .isInstanceOf(SecurityException.class);
    }

    private static OrchestrationRun run() {
        return simpleInputRunForStore();
    }

    private static OrchestrationRun simpleInputRunForStore() {
        return run("key", "fingerprint", OrchestrationStatus.RUNNING,
                Instant.parse("2026-09-08T00:00:00Z"));
    }

    private static OrchestrationRun run(String key, String fingerprint,
                                        OrchestrationStatus status, Instant createdAt) {
        UUID id = UUID.randomUUID();
        OrchestrationInput input = new OrchestrationInput("检查异常", null, List.of(),
                false, false, false, false);
        return new OrchestrationRun(id, OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                status, createdAt, createdAt, status.isTerminal() ? createdAt : null, "demo", "OPERATOR", input,
                "started", OrchestrationDefinition.steps(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT)
                        .stream().map(OrchestrationStep::pending).toList(),
                List.of(), id, null, null, key, fingerprint, false, 0, List.of());
    }
}
