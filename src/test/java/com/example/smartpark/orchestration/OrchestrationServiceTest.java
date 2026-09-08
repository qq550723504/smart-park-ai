package com.example.smartpark.orchestration;

import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEventType;
import com.example.smartpark.orchestration.OrchestrationPorts.Capabilities;
import com.example.smartpark.orchestration.OrchestrationPorts.ChildOutcome;
import com.example.smartpark.orchestration.OrchestrationPorts.EvidenceOutcome;
import com.example.smartpark.orchestration.OrchestrationPorts.StartedChild;
import com.example.smartpark.orchestration.OrchestrationPorts.WorkflowOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrchestrationServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private ExecutorService executor;

    @AfterEach
    void stopExecutor() {
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void completesTheTypedDefinitionInOrderAndKeepsChildReferences() {
        Harness harness = harness(new Capabilities(true, true, true, true, true), Runnable::run);
        OrchestrationInput input = new OrchestrationInput("研判 B1 能耗与安防异常", "ALT-001",
                List.of("B1"), true, true, true, true);

        OrchestrationRunStore.StartResult started = harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT, input, "key-1", "alice", "ADMIN");
        OrchestrationRun run = harness.service.get(started.run().id());

        assertThat(run.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(run.steps()).extracting(OrchestrationStep::id).containsExactly(
                "collect-context", "operations-analysis", "energy-time-series",
                "expert-collaboration", "security-review", "alert-workflow", "final-summary");
        assertThat(run.steps()).allSatisfy(step -> assertThat(step.status()).isEqualTo(OrchestrationStepStatus.COMPLETED));
        assertThat(run.result().childRuns()).containsKeys("operations-analysis", "expert-collaboration", "alert-workflow");
        assertThat(run.result().evidenceReferences()).contains("energy:B1:120/120", "security-incident:SEC-1");
        assertThat(run.result().recommendations()).contains("人工复核");
        assertThat(harness.events.history(run.id())).extracting(event -> event.eventType())
                .startsWith(ExecutionEventType.RUN_STARTED, ExecutionEventType.STEP_STARTED,
                        ExecutionEventType.STEP_COMPLETED)
                .endsWith(ExecutionEventType.RUN_COMPLETED);
        assertThat(run.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void requiredCapabilityFailureFailsClosedAndDoesNotStartLaterSteps() {
        Harness harness = harness(new Capabilities(false, false, true, true, true), Runnable::run);
        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "required-off", null, "OPERATOR").run();
        run = harness.service.get(run.id());

        assertThat(run.status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(step(run, "operations-analysis").status()).isEqualTo(OrchestrationStepStatus.BLOCKED);
        assertThat(step(run, "final-summary").status()).isEqualTo(OrchestrationStepStatus.PENDING);
        assertThat(harness.events.history(run.id())).extracting(event -> event.eventType())
                .endsWith(ExecutionEventType.STEP_FAILED, ExecutionEventType.RUN_FAILED);
        assertThat(run.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.RUN_FAILED);
    }

    @Test
    void unavailableOptionalCapabilityProducesTruthfulPartialResult() {
        Harness harness = harness(new Capabilities(true, true, false, false, false), Runnable::run);
        OrchestrationInput input = new OrchestrationInput("跨域能耗异常", null, List.of("B1"),
                true, true, false, false);
        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "optional-off", null, "VIEWER").run();
        run = harness.service.get(run.id());

        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
        assertThat(step(run, "expert-collaboration").status()).isEqualTo(OrchestrationStepStatus.SKIPPED);
        assertThat(run.result().partialReasons()).containsExactly("专家协作能力不可用");
        assertThat(run.summary()).doesNotContain("全部");
    }

    @Test
    void rechecksCapabilitiesBeforeEachStepAndDoesNotInvokeOneDisabledDuringTheRun() {
        AtomicReference<Capabilities> current = new AtomicReference<>(
                new Capabilities(true, true, false, false, false));
        AtomicBoolean energyInvoked = new AtomicBoolean();
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        OrchestrationPorts.OperationsRunner operations = question -> {
            current.set(new Capabilities(true, false, false, false, false));
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        OrchestrationService service = new OrchestrationService(new InMemoryOrchestrationRunStore(),
                current::get, operations, buildings -> {
                    energyInvoked.set(true);
                    return new EvidenceOutcome("AVAILABLE", "不应读取", List.of(), List.of(), List.of(), null);
                }, null, null, null, events, Runnable::run, CLOCK);
        OrchestrationInput input = new OrchestrationInput("检查能耗异常", null, List.of("B1"),
                true, false, false, false);

        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "capability-change", null, "OPERATOR").run();

        assertThat(energyInvoked).isFalse();
        assertThat(step(run, "energy-time-series").status()).isEqualTo(OrchestrationStepStatus.SKIPPED);
        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
    }

    @Test
    void redactsUnexpectedChildFailureFromStateAndTrace() {
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run,
                input -> availableSecurity(), completedWorkflow(),
                question -> { throw new RuntimeException("database password=secret-value"); });

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "redacted-failure", null, "OPERATOR").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(run.failureReason()).isEqualTo("步骤执行失败，未采用未确认结果");
        assertThat(run.toString()).doesNotContain("secret-value");
        assertThat(harness.events.history(run.id())).allSatisfy(event ->
                assertThat(event.safeSummary()).doesNotContain("secret-value"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "OPERATOR", "APPROVER", "ADMIN"})
    void allowsExistingOperationsRolesToStartReadOnlyAssessment(String role) {
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run);
        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "role-" + role, null, role).run();
        assertThat(harness.service.get(run.id()).status()).isEqualTo(OrchestrationStatus.COMPLETED);
    }

    @Test
    void customerAgentCannotGainOperationsOrSecurityAccessThroughOrchestrator() {
        Harness harness = harness(new Capabilities(true, true, true, true, true), Runnable::run);
        assertThatThrownBy(() -> harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "customer", null, "CUSTOMER_AGENT"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void securityStepIsSkippedForViewerWithoutInvokingRestrictedReader() {
        AtomicBoolean invoked = new AtomicBoolean();
        Harness harness = harness(new Capabilities(true, false, false, true, false), Runnable::run,
                input -> { invoked.set(true); return availableSecurity(); });
        OrchestrationInput input = new OrchestrationInput("查看安全异常", "ALT-001", List.of(),
                false, false, true, false);
        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "viewer-security", null, "VIEWER").run();
        run = harness.service.get(run.id());

        assertThat(invoked).isFalse();
        assertThat(step(run, "security-review").status()).isEqualTo(OrchestrationStepStatus.SKIPPED);
        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
    }

    @Test
    void duplicateStartReturnsSameRunAndRejectsDifferentPayload() {
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run);
        var first = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "same-key", null, "OPERATOR");
        var replay = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "same-key", null, "OPERATOR");

        assertThat(replay.created()).isFalse();
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThat(harness.events.history(first.run().id()).stream()
                .filter(event -> event.eventType() == ExecutionEventType.RUN_STARTED)).hasSize(1);
        assertThatThrownBy(() -> harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                new OrchestrationInput("另一个问题", null, List.of(), false, false, false, false),
                "same-key", null, "OPERATOR")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void clarificationReleasesTheOperationsChildSlotBeforeFailingTheRun() {
        UUID childId = UUID.randomUUID();
        AtomicReference<UUID> cancelledChild = new AtomicReference<>();
        OrchestrationPorts.OperationsRunner operations = new OrchestrationPorts.OperationsRunner() {
            @Override
            public StartedChild start(String question) {
                ChildOutcome clarification = new ChildOutcome(childId, "NEEDS_CLARIFICATION",
                        "需要选择园区", List.of(), "请选择园区");
                return new StartedChild(childId, CompletableFuture.completedFuture(clarification));
            }

            @Override
            public void cancel(UUID runId) {
                cancelledChild.set(runId);
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run,
                input -> availableSecurity(), completedWorkflow(), operations);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "clarification", null, "OPERATOR").run();

        assertThat(cancelledChild).hasValue(childId);
        assertThat(run.status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(step(run, "operations-analysis").failureReason()).contains("需要澄清");
    }

    @Test
    void waitsForExistingApprovalAndResumesIdempotently() {
        AtomicReference<String> workflowStatus = new AtomicReference<>("WAITING_APPROVAL");
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome(workflowStatus.get()); }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval", null, "APPROVER").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.WAITING_APPROVAL);
        assertThat(step(run, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.WAITING_APPROVAL);
        workflowStatus.set("COMPLETED");
        OrchestrationRun completed = harness.service.get(run.id());
        OrchestrationRun duplicateGet = harness.service.get(run.id());

        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(duplicateGet.revision()).isEqualTo(completed.revision());
        assertThat(harness.events.history(run.id()).stream()
                .filter(event -> event.eventType() == ExecutionEventType.APPROVAL_RESUMED)).hasSize(1);
    }

    @Test
    void cancellationStopsARealChildAndLateCompletionCannotOverwriteTerminalState() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CompletableFuture<ChildOutcome> pending = new CompletableFuture<>();
        UUID childId = UUID.randomUUID();
        AtomicBoolean childCancelled = new AtomicBoolean();
        OrchestrationPorts.OperationsRunner operations = new OrchestrationPorts.OperationsRunner() {
            @Override public StartedChild start(String question) { return new StartedChild(childId, pending); }
            @Override public void cancel(UUID runId) { childCancelled.set(true); pending.completeExceptionally(new RuntimeException("cancelled")); }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, false), executor,
                input -> availableSecurity(), completedWorkflow(), operations);
        OrchestrationRun accepted = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "cancel", null, "OPERATOR").run();
        awaitStep(harness.service, accepted.id(), "operations-analysis", OrchestrationStepStatus.RUNNING);

        OrchestrationRun cancelled = harness.service.cancel(accepted.id());
        pending.complete(completedChild(childId));
        executor.shutdown();
        assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();

        assertThat(childCancelled).isTrue();
        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(harness.service.get(accepted.id()).status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(harness.events.history(accepted.id())).extracting(event -> event.eventType())
                .endsWith(ExecutionEventType.RUN_CANCELLED);
        assertThat(cancelled.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.RUN_CANCELLED);
    }

    @Test
    void cancellationCannotCompleteBeforeWorkflowStartupPersistsItsReference() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        CountDownLatch workflowStarted = new CountDownLatch(1);
        CountDownLatch releaseWorkflow = new CountDownLatch(1);
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override
            public WorkflowOutcome start(String alertId) {
                workflowStarted.countDown();
                try {
                    releaseWorkflow.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted", interrupted);
                }
                return workflowOutcome("WAITING_APPROVAL");
            }

            @Override
            public WorkflowOutcome get(String workflowId) {
                return workflowOutcome("WAITING_APPROVAL");
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), executor,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun accepted = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "cancel-workflow-start", null, "OPERATOR").run();
        assertThat(workflowStarted.await(1, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<OrchestrationRun> cancellation = CompletableFuture.supplyAsync(
                () -> harness.service.cancel(accepted.id()));
        Thread.sleep(50);
        assertThat(cancellation).isNotDone();
        releaseWorkflow.countDown();
        OrchestrationRun cancelled = cancellation.get(2, TimeUnit.SECONDS);

        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(step(cancelled, "alert-workflow").runReference()).isEqualTo("wf-1");
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor) {
        return harness(capabilities, executor, input -> availableSecurity());
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                            OrchestrationPorts.SecurityReader security) {
        return harness(capabilities, executor, security, completedWorkflow());
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                            OrchestrationPorts.SecurityReader security,
                            OrchestrationPorts.WorkflowRunner workflow) {
        return harness(capabilities, executor, security, workflow,
                question -> {
                    UUID id = UUID.randomUUID();
                    return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
                });
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                            OrchestrationPorts.SecurityReader security,
                            OrchestrationPorts.WorkflowRunner workflow,
                            OrchestrationPorts.OperationsRunner operations) {
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        OrchestrationPorts.EnergyReader energy = buildings -> new EvidenceOutcome("AVAILABLE",
                "真实能耗证据", List.of("energy:B1:120/120"), List.of("OPERATIONS_ANALYTICS:energy_kwh"),
                List.of(), null);
        OrchestrationPorts.CollaborationRunner collaboration = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(new ChildOutcome(id,
                    "COMPLETED", "跨域结论", List.of("expert:evidence-1"), null)));
        };
        OrchestrationService service = new OrchestrationService(new InMemoryOrchestrationRunStore(),
                () -> capabilities, operations, energy, collaboration, security, workflow,
                events, executor, CLOCK);
        return new Harness(service, events);
    }

    private static OrchestrationInput simpleInput() {
        return new OrchestrationInput("检查园区运营异常", null, List.of(), false, false, false, false);
    }

    private static ChildOutcome completedChild(UUID id) {
        return new ChildOutcome(id, "COMPLETED", "运营分析结论", List.of("analysis:" + id), null);
    }

    private static EvidenceOutcome availableSecurity() {
        return new EvidenceOutcome("AVAILABLE", "安全事件证据", List.of("security-incident:SEC-1"),
                List.of("security-incident"), List.of("人工复核"), null);
    }

    private static OrchestrationPorts.WorkflowRunner completedWorkflow() {
        return new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("COMPLETED"); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("COMPLETED"); }
        };
    }

    private static WorkflowOutcome workflowOutcome(String status) {
        return new WorkflowOutcome("wf-1", status, "workflow " + status,
                List.of("alert-workflow:wf-1"), "COMPLETED".equals(status) ? "APPROVED" : null, null);
    }

    private static OrchestrationStep step(OrchestrationRun run, String id) {
        return run.steps().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private static void awaitStep(OrchestrationService service, UUID runId, String stepId,
                                  OrchestrationStepStatus status) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (step(service.get(runId), stepId).status() == status) return;
            Thread.sleep(10);
        }
        throw new AssertionError("step did not reach " + status);
    }

    private record Harness(OrchestrationService service, InMemoryExecutionEventPublisher events) {
    }
}
