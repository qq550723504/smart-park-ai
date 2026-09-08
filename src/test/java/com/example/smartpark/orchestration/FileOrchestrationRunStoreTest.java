package com.example.smartpark.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

        assertThatThrownBy(() -> store.createOrGet("retry-key", "fingerprint", FileOrchestrationRunStoreTest::run))
                .isInstanceOf(IllegalStateException.class);

        Files.delete(blockedParent);
        Files.createDirectory(blockedParent);
        OrchestrationRunStore.StartResult retry = store.createOrGet(
                "retry-key", "fingerprint", FileOrchestrationRunStoreTest::run);
        assertThat(retry.created()).isTrue();
        assertThat(Files.exists(blockedParent.resolve("runs.json"))).isTrue();
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
        Instant now = Instant.parse("2026-09-08T00:00:00Z");
        UUID id = UUID.randomUUID();
        OrchestrationInput input = new OrchestrationInput("检查异常", null, List.of(),
                false, false, false, false);
        return new OrchestrationRun(id, OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                OrchestrationStatus.RUNNING, now, now, null, "demo", "OPERATOR", input,
                "started", OrchestrationDefinition.steps(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT)
                        .stream().map(OrchestrationStep::pending).toList(),
                List.of(), id, null, null, "key", "fingerprint", false, 0, List.of());
    }
}
