package com.example.smartpark.orchestration;

import com.example.smartpark.execution.ExecutionEventCapacityException;
import com.example.smartpark.execution.InMemoryExecutionEventPublisher;
import com.example.smartpark.execution.model.ExecutionEvent;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

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
        assertThat(run.result().sourceReferences())
                .containsExactly("OPERATIONS_ANALYTICS:energy_kwh", "security-incident");
        assertThat(run.result().sourceReferences()).doesNotContain("park-context", "orchestration-summary");
        assertThat(run.result().recommendations()).contains("人工复核");
        assertThat(run.result().humanApprovalResult()).isEqualTo("APPROVED");
        assertThat(step(run, "alert-workflow").approvalResult()).isEqualTo("APPROVED");
        assertThat(harness.events.history(run.id())).extracting(event -> event.eventType())
                .startsWith(ExecutionEventType.RUN_STARTED, ExecutionEventType.STEP_STARTED,
                        ExecutionEventType.STEP_COMPLETED)
                .endsWith(ExecutionEventType.RUN_COMPLETED);
        assertThat(run.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.RUN_COMPLETED);
        assertThat(harness.service.retainedRunLockCount()).isZero();
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
                .endsWith(ExecutionEventType.STEP_FAILED, ExecutionEventType.RUN_FAILED);
        assertThat(harness.service.retainedRunLockCount()).isZero();
    }

    @Test
    void completedActionWithoutHumanDecisionKeepsApprovalResultNull() {
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override
            public WorkflowOutcome start(String alertId) {
                return new WorkflowOutcome("wf-low-risk", "COMPLETED", "completed without approval",
                        List.of("alert-workflow:wf-low-risk"), null, null);
            }

            @Override
            public WorkflowOutcome get(String workflowId) {
                throw new AssertionError("completed workflow must not be polled");
            }

            @Override
            public void releaseRetention(String workflowId) {
                retentionReleases.incrementAndGet();
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("执行低风险处置", "ALT-LOW", List.of(),
                false, false, false, true);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "no-human-decision", null, "OPERATOR").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(step(run, "alert-workflow").approvalResult()).isNull();
        assertThat(run.result().humanApprovalResult()).isNull();
        assertThat(retentionReleases).hasValue(1);
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
    void partialEnergyOutcomePersistsItsCompleteMetadataAndActualSources() {
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        OrchestrationPorts.EnergyReader partialEnergy = buildings -> new EvidenceOutcome("PARTIAL",
                "能耗数据存在缺口", List.of("energy:B1:110/120"),
                List.of("OPERATIONS_ANALYTICS:energy_kwh"), List.of("补采十个缺失点"), null);
        OrchestrationService service = new OrchestrationService(new InMemoryOrchestrationRunStore(),
                () -> new Capabilities(true, true, false, false, false), operations, partialEnergy,
                null, null, null, events, Runnable::run, CLOCK);
        OrchestrationInput input = new OrchestrationInput("检查 B1 能耗异常", null, List.of("B1"),
                true, false, false, false);

        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "partial-energy", null, "OPERATOR").run();
        OrchestrationStep energyStep = step(run, "energy-time-series");

        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
        assertThat(energyStep.status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(energyStep.failureReason()).isEqualTo("能耗时序存在缺失点");
        assertThat(energyStep.recommendations()).containsExactly("补采十个缺失点");
        assertThat(energyStep.sourceReferences()).containsExactly("OPERATIONS_ANALYTICS:energy_kwh");
        assertThat(run.result().sourceReferences()).containsExactly("OPERATIONS_ANALYTICS:energy_kwh");
    }

    @Test
    void partialOperationsOutcomeKeepsTheRequiredStepAndMarksTheRunPartial() {
        UUID childId = UUID.randomUUID();
        OrchestrationPorts.OperationsRunner operations = question -> new StartedChild(childId,
                CompletableFuture.completedFuture(new ChildOutcome(childId, "PARTIAL",
                        "运营结论基于受限结果", List.of("analysis:" + childId),
                        "运营分析结果已达到查询上限，结论基于截断结果集", null)));
        OrchestrationService service = new OrchestrationService(new InMemoryOrchestrationRunStore(),
                () -> new Capabilities(true, false, false, false, false), operations,
                null, null, null, null, new InMemoryExecutionEventPublisher(), Runnable::run, CLOCK);

        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "partial-operations", null, "OPERATOR").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
        assertThat(step(run, "operations-analysis").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(run.result().partialReasons()).containsExactly(
                "运营分析结果已达到查询上限，结论基于截断结果集");
    }

    @Test
    void partialCollaborationOutcomeKeepsItsUncertaintiesInTheFinalResult() {
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        UUID collaborationId = UUID.randomUUID();
        OrchestrationPorts.CollaborationRunner collaboration = question -> new StartedChild(collaborationId,
                CompletableFuture.completedFuture(new ChildOutcome(collaborationId, "PARTIAL",
                        "无法确认跨域关联", List.of("expert:evidence-1"),
                        "专家协作证据不足：设备时间窗缺失；需要人工复核", null)));
        OrchestrationService service = new OrchestrationService(new InMemoryOrchestrationRunStore(),
                () -> new Capabilities(true, false, true, false, false), operations,
                null, collaboration, null, null, new InMemoryExecutionEventPublisher(), Runnable::run, CLOCK);
        OrchestrationInput input = new OrchestrationInput("跨域异常", null, List.of(),
                false, true, false, false);

        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "partial-collaboration", null, "OPERATOR").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.PARTIAL);
        assertThat(step(run, "expert-collaboration").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(run.result().partialReasons()).containsExactly(
                "专家协作证据不足：设备时间窗缺失；需要人工复核");
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
    void idempotentReplayDoesNotDependOnMutableAlertAvailability() {
        AtomicBoolean alertAvailable = new AtomicBoolean(true);
        OrchestrationPorts.AlertScopeReader alertScope = alertId -> {
            if (!alertAvailable.get()) throw new NoSuchElementException("alert unavailable");
            return "B1";
        };
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), completedWorkflow(), operations, alertScope);
        OrchestrationInput input = new OrchestrationInput("处置 B1 告警", "ALT-001", List.of("B1"),
                true, false, false, true);

        OrchestrationRunStore.StartResult first = harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "stable-replay", null, "OPERATOR");
        alertAvailable.set(false);
        OrchestrationRunStore.StartResult replay = harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "stable-replay", null, "OPERATOR");

        assertThat(replay.created()).isFalse();
        assertThat(replay.run().id()).isEqualTo(first.run().id());
        assertThatThrownBy(() -> harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "new-admission", null, "OPERATOR"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("alert unavailable");
    }

    @Test
    void idempotencyFingerprintFramesStructuredFieldsIndependently() {
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run);
        OrchestrationInput first = new OrchestrationInput("a, alertId=b", null, List.of(),
                false, false, false, false);
        OrchestrationInput differentlyStructured = new OrchestrationInput("a", "b", List.of(),
                false, false, false, false);

        harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                first, "delimiter-collision", null, "OPERATOR");

        assertThatThrownBy(() -> harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                differentlyStructured, "delimiter-collision", null, "OPERATOR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Idempotency-Key");
    }

    @Test
    void rejectsUnboundedOrMalformedAlertIdentifiersBeforeAdmission() {
        assertThatThrownBy(() -> new OrchestrationInput("question", "A".repeat(101), List.of(),
                false, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alertId");
        assertThatThrownBy(() -> new OrchestrationInput("question", "ALT/../../runs", List.of(),
                false, false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alertId");
    }

    @Test
    void rejectsAnActionWhenTheAlertIsOutsideTheRequestedBuildingScope() {
        Harness harness = harness(new Capabilities(true, true, false, false, true), Runnable::run);
        OrchestrationInput input = new OrchestrationInput("分析 B2 并处置告警", "ALT-001", List.of("B2"),
                true, false, false, true);

        assertThatThrownBy(() -> harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT, input,
                "mismatched-alert-scope", null, "OPERATOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    @Test
    void propagatesReplayCapacityExhaustionForNewAndIdempotentAdmissions() {
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore();
        AtomicInteger publishAttempts = new AtomicInteger();
        AtomicInteger scheduled = new AtomicInteger();
        InMemoryExecutionEventPublisher exhausted = new InMemoryExecutionEventPublisher() {
            @Override
            public ExecutionEvent publish(ExecutionEvent event) {
                publishAttempts.incrementAndGet();
                throw new ExecutionEventCapacityException("execution event replay capacity is exhausted");
            }
        };
        OrchestrationService service = new OrchestrationService(store,
                () -> new Capabilities(true, false, false, false, false),
                null, null, null, null, null, exhausted,
                task -> scheduled.incrementAndGet(), CLOCK);

        for (int attempt = 0; attempt < 2; attempt++) {
            assertThatThrownBy(() -> service.start(
                    OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT, simpleInput(),
                    "exhausted-trace-capacity", null, "OPERATOR"))
                    .isInstanceOf(ExecutionEventCapacityException.class);
        }
        assertThat(publishAttempts).hasValue(2);
        assertThat(scheduled).hasValue(0);
        assertThat(store.nonTerminalRuns()).isEmpty();
    }

    @Test
    void recoveredTraceCapacityReplaysTheTerminalAdmissionFailureWithoutScheduling() {
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore();
        AtomicBoolean rejectFirstProjection = new AtomicBoolean(true);
        AtomicInteger scheduled = new AtomicInteger();
        InMemoryExecutionEventPublisher recovering = new InMemoryExecutionEventPublisher() {
            @Override
            public ExecutionEvent publish(ExecutionEvent event) {
                if (rejectFirstProjection.compareAndSet(true, false)) {
                    throw new ExecutionEventCapacityException("execution event replay capacity is exhausted");
                }
                return super.publish(event);
            }
        };
        OrchestrationService service = new OrchestrationService(store,
                () -> new Capabilities(true, false, false, false, false),
                null, null, null, null, null, recovering,
                task -> scheduled.incrementAndGet(), CLOCK);

        assertThatThrownBy(() -> service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT, simpleInput(),
                "recovering-trace-capacity", null, "OPERATOR"))
                .isInstanceOf(ExecutionEventCapacityException.class);
        OrchestrationRunStore.StartResult replay = service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT, simpleInput(),
                "recovering-trace-capacity", null, "OPERATOR");

        assertThat(replay.created()).isFalse();
        assertThat(replay.run().status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(store.nonTerminalRuns()).isEmpty();
        assertThat(scheduled).hasValue(0);
        assertThat(recovering.history(replay.run().traceId()))
                .extracting(ExecutionEvent::eventType)
                .containsExactly(ExecutionEventType.RUN_STARTED, ExecutionEventType.RUN_FAILED);
    }

    @Test
    void initialSnapshotAlreadyContainsTheStartEventBeforeTheRunIsScheduled() {
        InMemoryOrchestrationRunStore delegate = new InMemoryOrchestrationRunStore();
        OrchestrationRunStore createOnly = new OrchestrationRunStore() {
            @Override
            public StartResult createOrGet(String key, String fingerprint,
                                           java.util.function.Supplier<OrchestrationRun> factory) {
                return delegate.createOrGet(key, fingerprint, factory);
            }

            @Override
            public java.util.Optional<OrchestrationRun> find(UUID runId) {
                return delegate.find(runId);
            }

            @Override
            public OrchestrationRun update(UUID runId,
                                           java.util.function.UnaryOperator<OrchestrationRun> transition) {
                throw new AssertionError("start must not require a second persistence before scheduling");
            }

            @Override
            public List<OrchestrationRun> nonTerminalRuns() {
                return delegate.nonTerminalRuns();
            }
        };
        AtomicReference<Runnable> scheduled = new AtomicReference<>();
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        OrchestrationService service = new OrchestrationService(createOnly,
                () -> new Capabilities(true, false, false, false, false),
                null, null, null, null, null, events, scheduled::set, CLOCK);

        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "atomic-start", null, "OPERATOR").run();

        assertThat(scheduled).doesNotHaveNullValue();
        assertThat(run.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .containsExactly(ExecutionEventType.RUN_STARTED);
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
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome(workflowStatus.get()); }
            @Override public void releaseRetention(String workflowId) { retentionReleases.incrementAndGet(); }
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
        assertThat(step(completed, "alert-workflow").approvalResult()).isEqualTo("APPROVED");
        assertThat(completed.result().humanApprovalResult()).isEqualTo("APPROVED");
        assertThat(duplicateGet.revision()).isEqualTo(completed.revision());
        assertThat(harness.events.history(run.id()).stream()
                .filter(event -> event.eventType() == ExecutionEventType.APPROVAL_RESUMED)).hasSize(1);
        assertThat(retentionReleases).hasValue(1);
    }

    @Test
    void approvalRecoveryCommitsTheStepRunEvidenceAndTraceInOneRevision() {
        AtomicReference<String> workflowStatus = new AtomicReference<>("WAITING_APPROVAL");
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome(workflowStatus.get()); }
        };
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> continuation = new AtomicReference<>();
        java.util.concurrent.Executor stagedExecutor = command -> {
            if (submissions.getAndIncrement() == 0) command.run();
            else continuation.set(command);
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), stagedExecutor,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun waiting = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "atomic-approval", null, "APPROVER").run();
        workflowStatus.set("COMPLETED");

        OrchestrationRun resumed = harness.service.get(waiting.id());

        assertThat(resumed.revision()).isEqualTo(waiting.revision() + 1);
        assertThat(resumed.status()).isEqualTo(OrchestrationStatus.RUNNING);
        assertThat(step(resumed, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(step(resumed, "alert-workflow").approvalResult()).isEqualTo("APPROVED");
        assertThat(resumed.evidence()).contains("alert-workflow:wf-1");
        assertThat(resumed.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.APPROVAL_RESUMED, ExecutionEventType.STEP_COMPLETED);
        assertThat(continuation).doesNotHaveNullValue();
    }

    @Test
    void missingApprovalChildAlsoResumesWithOneAtomicRevision() {
        AtomicInteger lookups = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome get(String workflowId) {
                if (lookups.getAndIncrement() == 0) return workflowOutcome("WAITING_APPROVAL");
                throw new NoSuchElementException("gone");
            }
        };
        AtomicInteger submissions = new AtomicInteger();
        AtomicReference<Runnable> continuation = new AtomicReference<>();
        java.util.concurrent.Executor stagedExecutor = command -> {
            if (submissions.getAndIncrement() == 0) command.run();
            else continuation.set(command);
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), stagedExecutor,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun waiting = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "missing-approval-child", null, "APPROVER").run();

        OrchestrationRun resumed = harness.service.get(waiting.id());

        assertThat(resumed.revision()).isEqualTo(waiting.revision() + 1);
        assertThat(resumed.status()).isEqualTo(OrchestrationStatus.RUNNING);
        assertThat(step(resumed, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.FAILED);
        assertThat(resumed.evidence()).contains("alert-workflow:wf-1");
        assertThat(resumed.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.APPROVAL_RESUMED, ExecutionEventType.STEP_FAILED);
        assertThat(continuation).doesNotHaveNullValue();
    }

    @Test
    void rejectedContinuationDoesNotRewriteADurablyCompletedApprovalStep() {
        AtomicReference<String> workflowStatus = new AtomicReference<>("WAITING_APPROVAL");
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome(workflowStatus.get()); }
        };
        AtomicInteger submissions = new AtomicInteger();
        java.util.concurrent.Executor rejectingAfterStart = command -> {
            if (submissions.getAndIncrement() == 0) command.run();
            else throw new RejectedExecutionException("shutdown");
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), rejectingAfterStart,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun waiting = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "rejected-approval-continuation", null, "APPROVER").run();
        workflowStatus.set("COMPLETED");

        assertThatThrownBy(() -> harness.service.get(waiting.id()))
                .isInstanceOf(RejectedExecutionException.class);

        OrchestrationRun durable = harness.service.snapshot(waiting.id());
        assertThat(durable.status()).isEqualTo(OrchestrationStatus.RUNNING);
        assertThat(step(durable, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(step(durable, "alert-workflow").approvalResult()).isEqualTo("APPROVED");
    }

    @Test
    void expiredApprovalIsTerminalizedBeforeTheNextCapacityCheck() {
        MutableClock clock = new MutableClock(NOW);
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore(
                new OrchestrationStoreLimits(3, 1));
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome expireApproval(String workflowId, Instant approvalExpiresAt) {
                return new WorkflowOutcome(workflowId, "APPROVAL_EXPIRED", "approval expired",
                        List.of("alert-workflow:" + workflowId), null,
                        "approval expired", approvalExpiresAt);
            }
        };
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher();
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        OrchestrationService service = new OrchestrationService(store,
                () -> new Capabilities(true, false, false, false, true), operations,
                null, null, null, workflow, events, Runnable::run, clock, Duration.ofMinutes(5));
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun first = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval-capacity-1", null, "APPROVER").run();

        assertThat(step(first, "alert-workflow").approvalExpiresAt()).isEqualTo(NOW.plusSeconds(300));
        clock.advance(Duration.ofMinutes(6));
        OrchestrationRun second = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval-capacity-2", null, "APPROVER").run();

        OrchestrationRun expired = service.snapshot(first.id());
        assertThat(expired.status()).isEqualTo(OrchestrationStatus.FAILED);
        assertThat(expired.failureReason()).isEqualTo("人工审批等待超时");
        assertThat(step(expired, "alert-workflow").approvalResult()).isNull();
        assertThat(second.status()).isEqualTo(OrchestrationStatus.WAITING_APPROVAL);
    }

    @Test
    void restartHydratesTheDurableTraceBeforeSchedulingRecoveryTransitions() {
        InMemoryOrchestrationRunStore store = new InMemoryOrchestrationRunStore();
        ConcurrentLinkedQueue<Runnable> originalTasks = new ConcurrentLinkedQueue<>();
        OrchestrationPorts.OperationsRunner operations = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        OrchestrationService original = new OrchestrationService(store,
                () -> new Capabilities(true, false, false, false, false), operations,
                null, null, null, null, new InMemoryExecutionEventPublisher(), originalTasks::add, CLOCK);
        OrchestrationRun persisted = original.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "restart-trace-hydration", null, "OPERATOR").run();
        assertThat(originalTasks).hasSize(1);

        InMemoryExecutionEventPublisher recoveredEvents = new InMemoryExecutionEventPublisher();
        ConcurrentLinkedQueue<Runnable> recoveryTasks = new ConcurrentLinkedQueue<>();
        OrchestrationService recovered = new OrchestrationService(store,
                () -> new Capabilities(true, false, false, false, false), operations,
                null, null, null, null, recoveredEvents, recoveryTasks::add, CLOCK);

        recovered.recover();

        assertThat(recoveredEvents.history(persisted.traceId()))
                .extracting(ExecutionEvent::sequence, ExecutionEvent::eventType)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1L, ExecutionEventType.RUN_STARTED));
        assertThat(recoveryTasks).hasSize(1);

        recoveryTasks.remove().run();

        OrchestrationRun completed = recovered.snapshot(persisted.id());
        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(recoveredEvents.history(persisted.traceId()))
                .extracting(ExecutionEvent::sequence)
                .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(
                        1, completed.traceEvents().size()).boxed().toList());
        assertThat(recoveredEvents.history(persisted.traceId()))
                .extracting(ExecutionEvent::eventType).endsWith(ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void duplicateRecoveryTasksCannotStartTheSamePendingStepTwice() throws Exception {
        InMemoryOrchestrationRunStore delegate = new InMemoryOrchestrationRunStore();
        AtomicBoolean armed = new AtomicBoolean();
        ThreadLocal<Integer> findsByWorker = ThreadLocal.withInitial(() -> 0);
        CyclicBarrier selectedPendingSnapshot = new CyclicBarrier(2);
        OrchestrationRunStore barrierStore = new OrchestrationRunStore() {
            @Override
            public StartResult createOrGet(String key, String fingerprint,
                                           java.util.function.Supplier<OrchestrationRun> factory) {
                return delegate.createOrGet(key, fingerprint, factory);
            }

            @Override
            public java.util.Optional<OrchestrationRun> find(UUID runId) {
                if (armed.get()) {
                    int count = findsByWorker.get() + 1;
                    findsByWorker.set(count);
                    if (count == 2) {
                        try {
                            selectedPendingSnapshot.await(2, TimeUnit.SECONDS);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    }
                }
                return delegate.find(runId);
            }

            @Override
            public OrchestrationRun update(UUID runId,
                                           java.util.function.UnaryOperator<OrchestrationRun> transition) {
                return delegate.update(runId, transition);
            }

            @Override
            public List<OrchestrationRun> nonTerminalRuns() {
                return delegate.nonTerminalRuns();
            }
        };
        ConcurrentLinkedQueue<Runnable> scheduled = new ConcurrentLinkedQueue<>();
        AtomicInteger childStarts = new AtomicInteger();
        OrchestrationPorts.OperationsRunner operations = question -> {
            childStarts.incrementAndGet();
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
        };
        OrchestrationService service = new OrchestrationService(barrierStore,
                () -> new Capabilities(true, false, false, false, false), operations,
                null, null, null, null, new InMemoryExecutionEventPublisher(), scheduled::add, CLOCK);
        OrchestrationRun run = service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "duplicate-recovery", null, "OPERATOR").run();
        scheduled.remove();
        service.recover();
        service.recover();
        Runnable firstRecovery = scheduled.remove();
        Runnable secondRecovery = scheduled.remove();
        armed.set(true);
        executor = Executors.newFixedThreadPool(2);
        executor.execute(firstRecovery);
        executor.execute(secondRecovery);
        executor.shutdown();
        assertThat(executor.awaitTermination(3, TimeUnit.SECONDS)).isTrue();

        assertThat(childStarts).hasValue(1);
        assertThat(service.snapshot(run.id()).status()).isEqualTo(OrchestrationStatus.COMPLETED);
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

            @Override
            public WorkflowOutcome cancel(String workflowId) {
                return workflowOutcome("CANCELLED");
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

    @Test
    void cancellationTerminalizesAWaitingApprovalChildBeforeReturning() throws Exception {
        executor = Executors.newSingleThreadExecutor();
        AtomicBoolean childCancelled = new AtomicBoolean();
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                childCancelled.set(true);
                return workflowOutcome("CANCELLED");
            }
            @Override public void releaseRetention(String workflowId) { retentionReleases.incrementAndGet(); }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), executor,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun accepted = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "cancel-waiting-workflow", null, "OPERATOR").run();
        awaitStep(harness.service, accepted.id(), "alert-workflow", OrchestrationStepStatus.WAITING_APPROVAL);

        OrchestrationRun cancelled = harness.service.cancel(accepted.id());

        assertThat(childCancelled).isTrue();
        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(step(cancelled, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.CANCELLED);
        assertThat(retentionReleases).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"FAILED", "WORK_ORDER_FAILED"})
    void cancellationStillTerminatesTheParentWhenTheChildAlreadyFailed(String childStatus) {
        AtomicInteger childCancellationAttempts = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                childCancellationAttempts.incrementAndGet();
                return workflowOutcome(childStatus);
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun accepted = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "cancel-after-child-" + childStatus, null, "OPERATOR").run();

        OrchestrationRun cancelled = harness.service.cancel(accepted.id());

        assertThat(childCancellationAttempts).hasValue(1);
        assertThat(cancelled.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(step(cancelled, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.CANCELLED);
        assertThat(step(cancelled, "final-summary").status()).isEqualTo(OrchestrationStepStatus.CANCELLED);
        assertThat(cancelled.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .endsWith(ExecutionEventType.RUN_CANCELLED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETED", "REJECTED"})
    void cancellationRecordsASettledApprovalChildButCancelsRemainingWork(String childStatus) {
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome cancel(String workflowId) { return workflowOutcome(childStatus); }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);
        OrchestrationRun accepted = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "cancel-after-approval", null, "OPERATOR").run();

        OrchestrationRun observed = harness.service.cancel(accepted.id());

        assertThat(observed.status()).isEqualTo(OrchestrationStatus.CANCELLED);
        assertThat(step(observed, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(step(observed, "final-summary").status()).isEqualTo(OrchestrationStepStatus.CANCELLED);
        assertThat(observed.cancelRequested()).isTrue();
        assertThat(observed.traceEvents()).extracting(OrchestrationTraceRecord::eventType)
                .contains(ExecutionEventType.STEP_COMPLETED)
                .endsWith(ExecutionEventType.RUN_CANCELLED);
    }

    @Test
    void orchestrationStartsAnOwnedWorkflowExecution() {
        AtomicBoolean ownedStart = new AtomicBoolean();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) {
                throw new AssertionError("shared alert start must not be used");
            }
            @Override public WorkflowOutcome startOwned(String alertId, Instant approvalExpiresAt) {
                ownedStart.set(true);
                return workflowOutcome("COMPLETED");
            }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("COMPLETED"); }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);

        OrchestrationRun completed = harness.service.start(
                OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "owned-workflow", null, "OPERATOR").run();

        assertThat(ownedStart).isTrue();
        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
    }

    @Test
    void referencePersistenceFailureCancelsAndReleasesTheOwnedWorkflow() {
        FailNextUpdateStore store = new FailNextUpdateStore();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) {
                throw new AssertionError("shared alert start must not be used");
            }
            @Override public WorkflowOutcome startOwned(String alertId, Instant approvalExpiresAt) {
                store.failNextUpdate();
                return workflowOutcome("COMPLETED");
            }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("COMPLETED"); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                cancellations.incrementAndGet();
                return workflowOutcome("CANCELLED");
            }
            @Override public void releaseRetention(String workflowId) {
                retentionReleases.incrementAndGet();
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow,
                question -> {
                    UUID id = UUID.randomUUID();
                    return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
                }, alertId -> "B1", store);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "reference-write-failure", null, "OPERATOR").run();

        assertThat(cancellations).hasValue(1);
        assertThat(retentionReleases).hasValue(1);
        assertThat(step(run, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.FAILED);
        assertThat(step(run, "alert-workflow").runReference()).isNull();
    }

    @Test
    void approvalWaitPersistenceFailureCancelsAndReleasesTheOwnedWorkflow() {
        FailNextUpdateStore store = new FailNextUpdateStore();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) {
                throw new AssertionError("shared alert start must not be used");
            }
            @Override public WorkflowOutcome startOwned(String alertId, Instant approvalExpiresAt) {
                store.failAfterSuccessfulUpdates(1);
                return workflowOutcome("WAITING_APPROVAL");
            }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("WAITING_APPROVAL"); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                cancellations.incrementAndGet();
                return workflowOutcome("CANCELLED");
            }
            @Override public void releaseRetention(String workflowId) {
                retentionReleases.incrementAndGet();
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow,
                question -> {
                    UUID id = UUID.randomUUID();
                    return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
                }, alertId -> "B1", store);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval-wait-write-failure", null, "OPERATOR").run();

        assertThat(cancellations).hasValue(1);
        assertThat(retentionReleases).hasValue(1);
        assertThat(step(run, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.FAILED);
        assertThat(step(run, "alert-workflow").runReference()).isEqualTo("wf-1");
    }

    @Test
    void approvalWinningTheFailedWaitPersistenceRaceIsRecorded() {
        FailNextUpdateStore store = new FailNextUpdateStore();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) {
                throw new AssertionError("shared alert start must not be used");
            }
            @Override public WorkflowOutcome startOwned(String alertId, Instant approvalExpiresAt) {
                store.failAfterSuccessfulUpdates(1);
                return workflowOutcome("WAITING_APPROVAL");
            }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome("COMPLETED"); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                cancellations.incrementAndGet();
                return workflowOutcome("COMPLETED");
            }
            @Override public void releaseRetention(String workflowId) {
                retentionReleases.incrementAndGet();
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow,
                question -> {
                    UUID id = UUID.randomUUID();
                    return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
                }, alertId -> "B1", store);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval-wins-wait-write-race", null, "OPERATOR").run();

        assertThat(cancellations).hasValue(1);
        assertThat(retentionReleases).hasValue(1);
        assertThat(run.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(step(run, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.COMPLETED);
        assertThat(step(run, "alert-workflow").approvalResult()).isEqualTo("APPROVED");
    }

    @Test
    void approvalWaitProjectionFailureKeepsTheDurableRecoverableState() {
        AtomicReference<String> workflowStatus = new AtomicReference<>("WAITING_APPROVAL");
        AtomicInteger cancellations = new AtomicInteger();
        AtomicInteger retentionReleases = new AtomicInteger();
        OrchestrationPorts.WorkflowRunner workflow = new OrchestrationPorts.WorkflowRunner() {
            @Override public WorkflowOutcome start(String alertId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome get(String workflowId) { return workflowOutcome(workflowStatus.get()); }
            @Override public WorkflowOutcome cancel(String workflowId) {
                cancellations.incrementAndGet();
                return workflowOutcome("CANCELLED");
            }
            @Override public void releaseRetention(String workflowId) {
                retentionReleases.incrementAndGet();
            }
        };
        InMemoryExecutionEventPublisher events = new InMemoryExecutionEventPublisher() {
            @Override public ExecutionEvent publish(ExecutionEvent event) {
                if (event.eventType() == ExecutionEventType.WAITING_APPROVAL) {
                    throw new IllegalStateException("simulated projection failure");
                }
                return super.publish(event);
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, true), Runnable::run,
                input -> availableSecurity(), workflow,
                question -> {
                    UUID id = UUID.randomUUID();
                    return new StartedChild(id, CompletableFuture.completedFuture(completedChild(id)));
                }, alertId -> "B1", new InMemoryOrchestrationRunStore(), events);
        OrchestrationInput input = new OrchestrationInput("处置告警", "ALT-001", List.of(),
                false, false, false, true);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                input, "approval-wait-projection-failure", null, "OPERATOR").run();

        assertThat(run.status()).isEqualTo(OrchestrationStatus.WAITING_APPROVAL);
        assertThat(step(run, "alert-workflow").status()).isEqualTo(OrchestrationStepStatus.WAITING_APPROVAL);
        assertThat(cancellations).hasValue(0);
        assertThat(retentionReleases).hasValue(0);

        workflowStatus.set("COMPLETED");
        OrchestrationRun completed = harness.service.get(run.id());

        assertThat(completed.status()).isEqualTo(OrchestrationStatus.COMPLETED);
        assertThat(retentionReleases).hasValue(1);
        List<ExecutionEvent> history = events.history(run.id());
        assertThat(history).extracting(ExecutionEvent::sequence).containsExactlyElementsOf(
                java.util.stream.LongStream.rangeClosed(1, history.size()).boxed().toList());
        assertThat(history).extracting(ExecutionEvent::eventType)
                .contains(ExecutionEventType.WAITING_APPROVAL, ExecutionEventType.APPROVAL_RESUMED)
                .endsWith(ExecutionEventType.RUN_COMPLETED);
    }

    @Test
    void referencePersistenceFailureCancelsAStartedAsyncChild() {
        FailNextUpdateStore store = new FailNextUpdateStore();
        UUID childId = UUID.randomUUID();
        AtomicReference<UUID> cancelledChild = new AtomicReference<>();
        OrchestrationPorts.OperationsRunner operations = new OrchestrationPorts.OperationsRunner() {
            @Override public StartedChild start(String question) {
                store.failNextUpdate();
                return new StartedChild(childId,
                        CompletableFuture.completedFuture(completedChild(childId)));
            }
            @Override public void cancel(UUID runId) {
                cancelledChild.set(runId);
            }
        };
        Harness harness = harness(new Capabilities(true, false, false, false, false), Runnable::run,
                input -> availableSecurity(), completedWorkflow(), operations, alertId -> "B1", store);

        OrchestrationRun run = harness.service.start(OrchestrationDefinition.JOINT_ANOMALY_ASSESSMENT,
                simpleInput(), "async-reference-write-failure", null, "OPERATOR").run();

        assertThat(cancelledChild).hasValue(childId);
        assertThat(step(run, "operations-analysis").status()).isEqualTo(OrchestrationStepStatus.BLOCKED);
        assertThat(step(run, "operations-analysis").runReference()).isNull();
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
        return harness(capabilities, executor, security, workflow, operations, alertId -> "B1");
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                             OrchestrationPorts.SecurityReader security,
                             OrchestrationPorts.WorkflowRunner workflow,
                             OrchestrationPorts.OperationsRunner operations,
                             OrchestrationPorts.AlertScopeReader alertScope) {
        return harness(capabilities, executor, security, workflow, operations, alertScope,
                new InMemoryOrchestrationRunStore());
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                            OrchestrationPorts.SecurityReader security,
                            OrchestrationPorts.WorkflowRunner workflow,
                            OrchestrationPorts.OperationsRunner operations,
                            OrchestrationPorts.AlertScopeReader alertScope,
                            OrchestrationRunStore store) {
        return harness(capabilities, executor, security, workflow, operations, alertScope, store,
                new InMemoryExecutionEventPublisher());
    }

    private Harness harness(Capabilities capabilities, java.util.concurrent.Executor executor,
                            OrchestrationPorts.SecurityReader security,
                            OrchestrationPorts.WorkflowRunner workflow,
                            OrchestrationPorts.OperationsRunner operations,
                            OrchestrationPorts.AlertScopeReader alertScope,
                            OrchestrationRunStore store,
                            InMemoryExecutionEventPublisher events) {
        OrchestrationPorts.EnergyReader energy = buildings -> new EvidenceOutcome("AVAILABLE",
                "真实能耗证据", List.of("energy:B1:120/120"), List.of("OPERATIONS_ANALYTICS:energy_kwh"),
                List.of(), null);
        OrchestrationPorts.CollaborationRunner collaboration = question -> {
            UUID id = UUID.randomUUID();
            return new StartedChild(id, CompletableFuture.completedFuture(new ChildOutcome(id,
                    "COMPLETED", "跨域结论", List.of("expert:evidence-1"), null)));
        };
        OrchestrationService service = new OrchestrationService(store,
                () -> capabilities, operations, energy, collaboration, security, alertScope, workflow,
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

    private static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }

    private static final class FailNextUpdateStore implements OrchestrationRunStore {
        private final InMemoryOrchestrationRunStore delegate = new InMemoryOrchestrationRunStore();
        private final AtomicInteger updateCount = new AtomicInteger();
        private final AtomicInteger failingUpdate = new AtomicInteger(-1);

        void failNextUpdate() {
            failAfterSuccessfulUpdates(0);
        }

        void failAfterSuccessfulUpdates(int successfulUpdates) {
            failingUpdate.set(updateCount.get() + successfulUpdates + 1);
        }

        @Override
        public StartResult createOrGet(String idempotencyKey, String requestFingerprint,
                                       Supplier<OrchestrationRun> factory) {
            return delegate.createOrGet(idempotencyKey, requestFingerprint, factory);
        }

        @Override
        public Optional<OrchestrationRun> find(UUID runId) {
            return delegate.find(runId);
        }

        @Override
        public OrchestrationRun update(UUID runId, UnaryOperator<OrchestrationRun> transition) {
            int currentUpdate = updateCount.incrementAndGet();
            if (failingUpdate.compareAndSet(currentUpdate, -1)) {
                throw new IllegalStateException("simulated reference persistence failure");
            }
            return delegate.update(runId, transition);
        }

        @Override
        public List<OrchestrationRun> nonTerminalRuns() {
            return delegate.nonTerminalRuns();
        }
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
