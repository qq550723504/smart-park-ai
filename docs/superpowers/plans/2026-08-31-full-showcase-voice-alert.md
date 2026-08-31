# Full Showcase Voice and Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable and prove the real alert, expert collaboration, operations analysis, and realtime voice showcase chains through one opt-in Docker Compose stack and one admin preflight.

**Architecture:** Each showcase scenario owns a small `ShowcasePreflightProbe`; a generic coordinator runs registered probes in enum order with bounded execution, records or clears the existing readiness receipts, and exposes only customer-safe results. Alert verification creates a fresh isolated workflow with a rejecting work-order boundary, while voice verification calls the existing ASR, answer Agent, and TTS ports directly. An additive `compose.showcase.yaml` enables the full online runtime without changing the credential-free default stack.

**Tech Stack:** Java 17, Spring Boot 4, Spring AI Alibaba 2.0, Spring MVC, JUnit 5, AssertJ, Mockito, Docker Compose, PowerShell 7/Windows PowerShell, Vue 3/Vitest for regression verification.

**Spec:** `docs/superpowers/specs/2026-08-31-full-showcase-voice-alert-design.md`

## Global Constraints

- The successful verification receipt TTL remains exactly 15 minutes by default; a process restart has no receipt.
- The per-probe timeout is `smartpark.showcase.preflight-timeout`, defaults to 90 seconds, and must be positive.
- The public failure reason is exactly `在线验证未通过`; provider text, stack traces, prompts, model output, SQL, raw audio, and credentials never enter the HTTP response.
- `POST /api/showcase/preflight` requires `X-Demo-Role: ADMIN`.
- Alert preflight uses `ALT-POWER-001`, must end at `WAITING_APPROVAL`, must have a non-null diagnosis and no errors, and must never create a work order.
- Voice preflight sends at most one second of 16 kHz, mono, signed 16-bit silence; it requires clean ASR termination, a validated evidence-bearing Agent answer, and non-empty TTS audio followed by completion.
- The tracked Compose files contain variable references only. The full stack requires `AI_DASHSCOPE_API_KEY`, `SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD`, and `SMARTPARK_ANALYTICS_DB_RO_PASSWORD` from the environment or an ignored `.env` file.
- `compose.yaml` remains credential-free and offline. Full showcase mode is opt-in through `compose.analytics.yaml` plus `compose.showcase.yaml` and the `analytics` profile.
- The full-showcase verifier requires exactly `ALERT_WORKFLOW`, `EXPERT_COLLABORATION`, `OPERATIONS_ANALYSIS`, and `VOICE_ASSISTANT`, once each, all `READY`.
- Reuse the existing `AlertWorkflow`, `VoiceAnswerAgent`, `StreamingAsrPort`, `StreamingTtsPort`, `ExpertCollaborationService`, and `OperationsAnalysisService`; do not add another provider client or duplicate a business workflow.
- Do not stage or rewrite the concurrent uncommitted files under `ui/`; every commit uses explicit paths.

---

## File Structure and Responsibilities

- `src/main/java/com/example/smartpark/showcase/ShowcasePreflightProbe.java`: scenario-owned online verification SPI.
- `src/main/java/com/example/smartpark/showcase/ShowcaseProbeResult.java`: closed internal `PASSED`/`FAILED` result.
- `src/main/java/com/example/smartpark/showcase/ShowcasePreflightStatus.java`: public `READY`/`NOT_READY` status.
- `src/main/java/com/example/smartpark/showcase/ShowcasePreflightResult.java`: one customer-safe scenario result.
- `src/main/java/com/example/smartpark/showcase/ShowcasePreflightReport.java`: immutable report with start/end timestamps.
- `src/main/java/com/example/smartpark/showcase/ShowcasePreflightService.java`: fixed-order, single-flight coordinator with timeout and receipt invalidation.
- `src/main/java/com/example/smartpark/showcase/ShowcaseProbeAwaiter.java`: interruption-aware polling shared only by asynchronous collaboration and analytics runs.
- `src/main/java/com/example/smartpark/showcase/ExpertCollaborationPreflightProbe.java`: real collaboration run probe.
- `src/main/java/com/example/smartpark/showcase/OperationsAnalysisPreflightProbe.java`: real read-only analytics run probe.
- `src/main/java/com/example/smartpark/showcase/AlertShowcaseCondition.java`: exact online alert-mode condition.
- `src/main/java/com/example/smartpark/showcase/RejectingPreflightWorkOrderPort.java`: preflight work-order read-empty/write-reject boundary.
- `src/main/java/com/example/smartpark/showcase/AlertPreflightWorkflowFactory.java`: creates a fresh workflow, store, publisher, and rejecting work-order port for every run.
- `src/main/java/com/example/smartpark/showcase/AlertWorkflowPreflightProbe.java`: validates the isolated workflow's approval-boundary snapshot.
- `src/main/java/com/example/smartpark/showcase/VoiceAssistantPreflightProbe.java`: bounded ASR → Agent → TTS server-side probe with cleanup.
- `src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java`: generic executor and coordinator wiring only.
- `src/main/java/com/example/smartpark/showcase/ShowcaseProperties.java`: existing receipt TTL plus positive 90-second preflight timeout.
- `src/main/java/com/example/smartpark/web/ShowcasePreflightController.java`: admin-only POST boundary.
- `src/main/resources/application.yml`: environment mapping for preflight timeout.
- `compose.showcase.yaml`: additive full-showcase capability switches and local voice origins.
- `scripts/verify-showcase.ps1`: calls preflight and rejects incomplete, duplicate, stale, or non-ready reports.
- `scripts/verify-showcase.tests.ps1`: dependency-free behavioral tests for the PowerShell verifier.
- `.github/workflows/ci.yml`: runs the verifier's PowerShell contract tests on Linux.
- `README.md`: full-showcase startup, preflight, security boundary, and voice manual-check instructions.

### Task 1: Add the Generic Probe Contract and Bounded Coordinator

**Files:**

- Create: `src/main/java/com/example/smartpark/showcase/ShowcasePreflightProbe.java`
- Create: `src/main/java/com/example/smartpark/showcase/ShowcaseProbeResult.java`
- Create: `src/main/java/com/example/smartpark/showcase/ShowcasePreflightStatus.java`
- Create: `src/main/java/com/example/smartpark/showcase/ShowcasePreflightResult.java`
- Create: `src/main/java/com/example/smartpark/showcase/ShowcasePreflightReport.java`
- Create: `src/main/java/com/example/smartpark/showcase/ShowcasePreflightService.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseProperties.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java`
- Test: `src/test/java/com/example/smartpark/showcase/ShowcasePreflightServiceTest.java`
- Test: `src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java`

**Interfaces:**

- Produces `ShowcaseScenarioId scenarioId()` and `ShowcaseProbeResult probe()` on `ShowcasePreflightProbe`.
- Produces `ShowcaseProbeResult.PASSED` and `ShowcaseProbeResult.FAILED`; no diagnostic string crosses this interface.
- Produces `ShowcasePreflightReport run()` with enum-ordered `ShowcasePreflightResult` values.
- Produces `Duration ShowcaseProperties.getPreflightTimeout()`; default `Duration.ofSeconds(90)`.
- Consumes only `ScenarioVerificationRegistry`, `Clock`, `Duration`, `ExecutorService`, and `List<ShowcasePreflightProbe>`; it does not import any scenario subsystem.

- [ ] **Step 1: Write failing coordinator and property tests**

Create tests with explicit probes and a dedicated executor that is shut down in `@AfterEach`:

```java
private final ExecutorService executor = Executors.newFixedThreadPool(2);

@AfterEach
void stopExecutor() {
    executor.shutdownNow();
}

@Test
void runsProbesInScenarioIdentifierOrderAndRecordsSuccess() {
    var registry = new InMemoryScenarioVerificationRegistry();
    var service = service(registry, Duration.ofSeconds(1), List.of(
            probe(ShowcaseScenarioId.VOICE_ASSISTANT, ShowcaseProbeResult.PASSED),
            probe(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseProbeResult.PASSED)));

    ShowcasePreflightReport report = service.run();

    assertThat(report.results()).extracting(ShowcasePreflightResult::scenarioId)
            .containsExactly(ShowcaseScenarioId.ALERT_WORKFLOW, ShowcaseScenarioId.VOICE_ASSISTANT);
    assertThat(report.results()).extracting(ShowcasePreflightResult::status)
            .containsOnly(ShowcasePreflightStatus.READY);
}

@Test
void clearsOldReceiptAndMasksProbeException() {
    var registry = new InMemoryScenarioVerificationRegistry();
    registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, NOW.minusSeconds(1));
    ShowcasePreflightProbe failing = new ShowcasePreflightProbe() {
        @Override public ShowcaseScenarioId scenarioId() {
            return ShowcaseScenarioId.OPERATIONS_ANALYSIS;
        }
        @Override public ShowcaseProbeResult probe() {
            throw new IllegalStateException("vendor response must not leak");
        }
    };

    ShowcasePreflightResult result = service(registry, Duration.ofSeconds(1), List.of(failing))
            .run().results().get(0);

    assertThat(result.status()).isEqualTo(ShowcasePreflightStatus.NOT_READY);
    assertThat(result.reason()).isEqualTo("在线验证未通过");
    assertThat(result.verifiedAt()).isNull();
    assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
            NOW, Duration.ofMinutes(15))).isEmpty();
}

@Test
void cancelsAProbeThatExceedsTheConfiguredTimeout() {
    var interrupted = new AtomicBoolean();
    ShowcasePreflightProbe blocking = new ShowcasePreflightProbe() {
        @Override public ShowcaseScenarioId scenarioId() {
            return ShowcaseScenarioId.VOICE_ASSISTANT;
        }
        @Override public ShowcaseProbeResult probe() {
            try {
                new CountDownLatch(1).await();
                return ShowcaseProbeResult.PASSED;
            } catch (InterruptedException expected) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
                return ShowcaseProbeResult.FAILED;
            }
        }
    };

    ShowcasePreflightResult result = service(new InMemoryScenarioVerificationRegistry(),
            Duration.ofMillis(25), List.of(blocking)).run().results().get(0);

    assertThat(result.status()).isEqualTo(ShowcasePreflightStatus.NOT_READY);
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (!interrupted.get() && System.nanoTime() < deadline) {
        Thread.onSpinWait();
    }
    assertThat(interrupted).isTrue();
}
```

Add these exact duplicate/property assertions:

```java
@Test
void rejectsDuplicateScenarioProbeIds() {
    ShowcasePreflightProbe first = probe(
            ShowcaseScenarioId.OPERATIONS_ANALYSIS, ShowcaseProbeResult.PASSED);
    ShowcasePreflightProbe duplicate = probe(
            ShowcaseScenarioId.OPERATIONS_ANALYSIS, ShowcaseProbeResult.FAILED);

    assertThatThrownBy(() -> service(new InMemoryScenarioVerificationRegistry(),
            Duration.ofSeconds(1), List.of(first, duplicate)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("duplicate preflight probe");
}

@Test
void defaultsPreflightTimeoutToNinetySecondsAndRejectsNonPositiveValues() {
    ShowcaseProperties properties = new ShowcaseProperties();
    assertThat(properties.getVerificationTtl()).isEqualTo(Duration.ofMinutes(15));
    assertThat(properties.getPreflightTimeout()).isEqualTo(Duration.ofSeconds(90));

    properties.setPreflightTimeout(Duration.ZERO);
    assertThatThrownBy(properties::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("smartpark.showcase.preflight-timeout must be positive");
}
```

- [ ] **Step 2: Run the tests to verify the contract is absent**

Run:

```powershell
.\mvnw.cmd -B "-Dtest=ShowcasePreflightServiceTest,ShowcaseScenarioCatalogTest" test
```

Expected: compilation fails because the preflight types and `getPreflightTimeout()` do not exist.

- [ ] **Step 3: Implement the closed contract and timeout-safe coordinator**

Define the interfaces and records exactly:

```java
public interface ShowcasePreflightProbe {
    ShowcaseScenarioId scenarioId();
    ShowcaseProbeResult probe();
}

public enum ShowcaseProbeResult { PASSED, FAILED }
public enum ShowcasePreflightStatus { READY, NOT_READY }

public record ShowcasePreflightResult(
        ShowcaseScenarioId scenarioId,
        ShowcasePreflightStatus status,
        String reason,
        Instant verifiedAt) { }

public record ShowcasePreflightReport(
        Instant startedAt,
        Instant completedAt,
        List<ShowcasePreflightResult> results) {
    public ShowcasePreflightReport {
        results = List.copyOf(results);
    }
}
```

In `ShowcasePreflightService`, copy probes into an `EnumMap`, reject duplicate IDs, and implement `public synchronized ShowcasePreflightReport run()`. For each probe submit `probe::probe`, call `future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)`, and treat null, `FAILED`, `TimeoutException`, `ExecutionException`, interruption, and task rejection as failure. Always call `future.cancel(true)` when it is unfinished. Record success at `clock.instant()` only after `PASSED`; otherwise call `registry.recordFailure(id)`. Log only `scenarioId`, elapsed milliseconds, and exception class name.

Add the property exactly:

```java
private Duration preflightTimeout = Duration.ofSeconds(90);

public Duration getPreflightTimeout() { return preflightTimeout; }
public void setPreflightTimeout(Duration value) { preflightTimeout = value; }
```

Reject null, zero, or negative values in `ShowcaseProperties.validate()`.

Wire a bounded executor and the generic service in `ShowcaseConfiguration`:

```java
@Bean(name = "showcasePreflightExecutor", destroyMethod = "shutdownNow")
ExecutorService showcasePreflightExecutor() {
    AtomicInteger sequence = new AtomicInteger();
    ThreadFactory threads = task -> {
        Thread thread = new Thread(task, "showcase-preflight-" + sequence.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    return new ThreadPoolExecutor(0, ShowcaseScenarioId.values().length,
            60, TimeUnit.SECONDS, new SynchronousQueue<>(), threads,
            new ThreadPoolExecutor.AbortPolicy());
}

@Bean
ShowcasePreflightService showcasePreflightService(
        ScenarioVerificationRegistry registry,
        @Qualifier("showcaseClock") Clock clock,
        ShowcaseProperties properties,
        @Qualifier("showcasePreflightExecutor") ExecutorService executor,
        List<ShowcasePreflightProbe> probes) {
    return new ShowcasePreflightService(
            registry, clock, properties.getPreflightTimeout(), executor, probes);
}
```

- [ ] **Step 4: Run the focused tests and inspect the public types**

Run:

```powershell
.\mvnw.cmd -B "-Dtest=ShowcasePreflightServiceTest,ShowcaseScenarioCatalogTest" test
git diff --check
```

Expected: both test classes pass; timeout cancellation finishes within one second; `git diff --check` prints nothing.

- [ ] **Step 5: Commit only the coordinator slice**

```powershell
git add -- src/main/java/com/example/smartpark/showcase/ShowcasePreflightProbe.java src/main/java/com/example/smartpark/showcase/ShowcaseProbeResult.java src/main/java/com/example/smartpark/showcase/ShowcasePreflightStatus.java src/main/java/com/example/smartpark/showcase/ShowcasePreflightResult.java src/main/java/com/example/smartpark/showcase/ShowcasePreflightReport.java src/main/java/com/example/smartpark/showcase/ShowcasePreflightService.java src/main/java/com/example/smartpark/showcase/ShowcaseProperties.java src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java src/test/java/com/example/smartpark/showcase/ShowcasePreflightServiceTest.java src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java
git commit -m "feat: add bounded showcase preflight coordinator"
```

### Task 2: Add Real Collaboration and Analytics Probes

**Files:**

- Create: `src/main/java/com/example/smartpark/showcase/ShowcaseProbeAwaiter.java`
- Create: `src/main/java/com/example/smartpark/showcase/ExpertCollaborationPreflightProbe.java`
- Create: `src/main/java/com/example/smartpark/showcase/OperationsAnalysisPreflightProbe.java`
- Test: `src/test/java/com/example/smartpark/showcase/ExpertCollaborationPreflightProbeTest.java`
- Test: `src/test/java/com/example/smartpark/showcase/OperationsAnalysisPreflightProbeTest.java`

**Interfaces:**

- Collaboration probe uses `ExpertCollaborationService.start(String)` and `get(UUID)` with the exact question `A2 夜间能耗升高且门禁告警、冷机离线，是否有关联`.
- Analytics probe uses `OperationsAnalysisService.start(String)` and `get(UUID)` with the exact question `过去5天各楼宇能耗`.
- `ShowcaseProbeAwaiter.await(Supplier<T>, Function<T, ShowcaseProbeResult>)` polls every 200 milliseconds and returns `FAILED` when interrupted; the coordinator owns the absolute timeout.
- Both probes return their fixed `ShowcaseScenarioId` and never write a receipt directly.

- [ ] **Step 1: Write failing terminal-state tests**

Use Mockito only around the existing services:

```java
@Test
void collaborationPassesOnlyWithCompletedNonEmptyFindings() {
    ExpertCollaborationService service = mock(ExpertCollaborationService.class);
    UUID runId = UUID.randomUUID();
    when(service.start(anyString())).thenReturn(new CollaborationRun(
            runId, "question", CollaborationRun.RunStatus.RUNNING,
            null, List.of(), null, null, Instant.EPOCH));
    when(service.get(runId)).thenReturn(new CollaborationRun(
            runId, "question", CollaborationRun.RunStatus.COMPLETED,
            null, List.of(mock(ExpertFinding.class)), null, null, Instant.EPOCH));

    assertThat(new ExpertCollaborationPreflightProbe(service).probe())
            .isEqualTo(ShowcaseProbeResult.PASSED);
    verify(service).start("A2 夜间能耗升高且门禁告警、冷机离线，是否有关联");
}

@Test
void analyticsRejectsACompletedRunWithNoRows() {
    OperationsAnalysisService service = mock(OperationsAnalysisService.class);
    AnalysisRunStore.RunRecord started = mock(AnalysisRunStore.RunRecord.class);
    AnalysisRunStore.RunRecord completed = mock(AnalysisRunStore.RunRecord.class);
    UUID runId = UUID.randomUUID();
    when(started.runId()).thenReturn(runId);
    when(completed.status()).thenReturn("COMPLETED");
    when(completed.rowCount()).thenReturn(0);
    when(service.start("过去5天各楼宇能耗")).thenReturn(started);
    when(service.get(runId)).thenReturn(completed);

    assertThat(new OperationsAnalysisPreflightProbe(service).probe())
            .isEqualTo(ShowcaseProbeResult.FAILED);
}
```

Add exact negative cases: collaboration `FAILED`, `NEEDS_CLARIFICATION`, or completed with an empty finding list returns `FAILED`; analytics `FAILED`, `NEEDS_CLARIFICATION`, or completed with zero rows returns `FAILED`.

- [ ] **Step 2: Run the probe tests to verify they fail**

```powershell
.\mvnw.cmd -B "-Dtest=ExpertCollaborationPreflightProbeTest,OperationsAnalysisPreflightProbeTest" test
```

Expected: compilation fails because both probes are absent.

- [ ] **Step 3: Implement the interruption-aware probes**

Implement `ShowcaseProbeAwaiter` as a package-private utility. It repeatedly reads the current run, applies the terminal mapper, returns the non-null result, then sleeps 200 milliseconds. On `InterruptedException`, restore the interrupt flag and return `FAILED`.

Register the probes with their existing feature switches:

```java
@Component
@ConditionalOnProperty(name = "spring.ai.dashscope.enabled",
        havingValue = "true", matchIfMissing = true)
public final class ExpertCollaborationPreflightProbe implements ShowcasePreflightProbe {
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.EXPERT_COLLABORATION;
    }
}

@Component
@ConditionalOnProperty(prefix = "smartpark.analytics", name = "enabled",
        havingValue = "true")
public final class OperationsAnalysisPreflightProbe implements ShowcasePreflightProbe {
    public ShowcaseScenarioId scenarioId() {
        return ShowcaseScenarioId.OPERATIONS_ANALYSIS;
    }
}
```

Map `RUNNING` to null, collaboration `COMPLETED` with non-empty findings to `PASSED`, and analytics `COMPLETED` with `rowCount() > 0` to `PASSED`; every other terminal status returns `FAILED`.

- [ ] **Step 4: Run the focused probes and existing service regressions**

```powershell
.\mvnw.cmd -B "-Dtest=ExpertCollaborationPreflightProbeTest,OperationsAnalysisPreflightProbeTest,ExpertCollaborationServiceTest,OperationsAnalysisServiceTest" test
```

Expected: all four classes pass without network access.

- [ ] **Step 5: Commit the two scenario-owned probes**

```powershell
git add -- src/main/java/com/example/smartpark/showcase/ShowcaseProbeAwaiter.java src/main/java/com/example/smartpark/showcase/ExpertCollaborationPreflightProbe.java src/main/java/com/example/smartpark/showcase/OperationsAnalysisPreflightProbe.java src/test/java/com/example/smartpark/showcase/ExpertCollaborationPreflightProbeTest.java src/test/java/com/example/smartpark/showcase/OperationsAnalysisPreflightProbeTest.java
git commit -m "feat: probe collaboration and analytics showcase chains"
```

### Task 3: Prove the Alert Chain Without Writing a Work Order

**Files:**

- Create: `src/main/java/com/example/smartpark/showcase/AlertShowcaseCondition.java`
- Create: `src/main/java/com/example/smartpark/showcase/RejectingPreflightWorkOrderPort.java`
- Create: `src/main/java/com/example/smartpark/showcase/AlertPreflightWorkflowFactory.java`
- Create: `src/main/java/com/example/smartpark/showcase/AlertWorkflowPreflightProbe.java`
- Test: `src/test/java/com/example/smartpark/showcase/RejectingPreflightWorkOrderPortTest.java`
- Test: `src/test/java/com/example/smartpark/showcase/AlertPreflightWorkflowFactoryTest.java`
- Test: `src/test/java/com/example/smartpark/showcase/AlertWorkflowPreflightProbeTest.java`

**Interfaces:**

- `AlertShowcaseCondition` matches only when `spring.ai.dashscope.enabled` is true or absent, `smartpark.knowledge.mode` is `rag`, and `smartpark.customer-service.answer-mode` is `dashscope`.
- `AlertPreflightWorkflowFactory.create()` returns a new `AlertWorkflow` every time with `WorkflowExecutionStore.inMemory()`, `WorkflowEventPublisher.inMemory()`, and a new `RejectingPreflightWorkOrderPort`.
- `RejectingPreflightWorkOrderPort.findByWorkflowId(String)` returns an empty list; `create(String, String, String)` throws `IllegalStateException("preflight work-order writes are forbidden")`.
- Alert probe starts exactly `ALT-POWER-001` and passes only for the approved snapshot invariants.

- [ ] **Step 1: Write failing rejection, isolation, and snapshot tests**

```java
@Test
void workOrderBoundaryAllowsEmptyReadAndRejectsEveryWrite() {
    WorkOrderPort port = new RejectingPreflightWorkOrderPort();

    assertThat(port.findByWorkflowId("showcase-preflight")).isEmpty();
    assertThatThrownBy(() -> port.create("wf", "ALT-POWER-001", "summary"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("preflight work-order writes are forbidden");
}

@Test
void passesOnlyAtTheHumanApprovalBoundary() {
    AlertPreflightWorkflowFactory factory = mock(AlertPreflightWorkflowFactory.class);
    AlertWorkflow workflow = mock(AlertWorkflow.class);
    Diagnosis diagnosis = mock(Diagnosis.class);
    when(factory.create()).thenReturn(workflow);
    when(workflow.start("ALT-POWER-001")).thenReturn(new WorkflowSnapshot(
            "preflight-wf", "ALT-POWER-001", WorkflowStatus.WAITING_APPROVAL,
            Map.of(), diagnosis, Optional.empty(), null, List.of(), 1));

    assertThat(new AlertWorkflowPreflightProbe(factory).probe())
            .isEqualTo(ShowcaseProbeResult.PASSED);
    verify(workflow).start("ALT-POWER-001");
}
```

Add the invalid snapshots through one helper and assert each fails:

```java
private ShowcaseProbeResult runWith(WorkflowSnapshot snapshot) {
    AlertPreflightWorkflowFactory factory = mock(AlertPreflightWorkflowFactory.class);
    AlertWorkflow workflow = mock(AlertWorkflow.class);
    when(factory.create()).thenReturn(workflow);
    when(workflow.start("ALT-POWER-001")).thenReturn(snapshot);
    return new AlertWorkflowPreflightProbe(factory).probe();
}

@Test
void rejectsEverySnapshotOutsideTheNoWriteApprovalBoundary() {
    Diagnosis diagnosis = mock(Diagnosis.class);
    WorkOrder workOrder = mock(WorkOrder.class);
    assertThat(runWith(snapshot(WorkflowStatus.COMPLETED, diagnosis, List.of(), null)))
            .isEqualTo(ShowcaseProbeResult.FAILED);
    assertThat(runWith(snapshot(WorkflowStatus.WAITING_APPROVAL, null, List.of(), null)))
            .isEqualTo(ShowcaseProbeResult.FAILED);
    assertThat(runWith(snapshot(WorkflowStatus.WAITING_APPROVAL, diagnosis,
            List.of("safe public error"), null)))
            .isEqualTo(ShowcaseProbeResult.FAILED);
    assertThat(runWith(snapshot(WorkflowStatus.WAITING_APPROVAL, diagnosis,
            List.of(), workOrder)))
            .isEqualTo(ShowcaseProbeResult.FAILED);
}
```

In `AlertPreflightWorkflowFactoryTest`, construct the factory with `mock(AlertTriageAgent.class)`, `mock(AlertDiagnosisAgent.class)`, `mock(DevicePort.class)`, `mock(AlertPort.class)`, `mock(KnowledgePort.class)`, `mock(EnergyPort.class)`, and `mock(SecurityPort.class)`. Call `create()` twice, assert the returned workflows are distinct, and verify the seven mocks had no interactions during construction.

- [ ] **Step 2: Run the alert preflight tests to verify they fail**

```powershell
.\mvnw.cmd -B "-Dtest=RejectingPreflightWorkOrderPortTest,AlertPreflightWorkflowFactoryTest,AlertWorkflowPreflightProbeTest" test
```

Expected: compilation fails because the alert preflight boundary and probe do not exist.

- [ ] **Step 3: Implement the isolated factory and exact snapshot gate**

Implement the condition exactly:

```java
public final class AlertShowcaseCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Environment environment = context.getEnvironment();
        boolean modelEnabled = Boolean.parseBoolean(
                environment.getProperty("spring.ai.dashscope.enabled", "true"));
        return modelEnabled
                && "rag".equals(environment.getProperty("smartpark.knowledge.mode", "mock"))
                && "dashscope".equals(environment.getProperty(
                        "smartpark.customer-service.answer-mode", "mock"));
    }
}
```

Register `AlertPreflightWorkflowFactory` with `@Component` and `@Conditional(AlertShowcaseCondition.class)`. The factory stores only the existing read/model dependencies. Its `create()` method must construct the workflow in this form:

```java
public AlertWorkflow create() {
    return new AlertWorkflow(
            triageAgent,
            diagnosisAgent,
            devicePort,
            alertPort,
            new RejectingPreflightWorkOrderPort(),
            knowledgePort,
            WorkflowExecutionStore.inMemory(),
            WorkflowEventPublisher.inMemory(),
            energyPort,
            securityPort);
}
```

The probe must be registered as:

```java
@Component
@Conditional(AlertShowcaseCondition.class)
public final class AlertWorkflowPreflightProbe implements ShowcasePreflightProbe {
    private static final String ALERT_ID = "ALT-POWER-001";

    @Override
    public ShowcaseProbeResult probe() {
        WorkflowSnapshot snapshot = factory.create().start(ALERT_ID);
        boolean passed = snapshot.status() == WorkflowStatus.WAITING_APPROVAL
                && snapshot.diagnosis() != null
                && snapshot.errors().isEmpty()
                && snapshot.workOrder() == null;
        return passed ? ShowcaseProbeResult.PASSED : ShowcaseProbeResult.FAILED;
    }
}
```

Do not inject the production `AlertWorkflow`, production `WorkflowExecutionStore`, production `WorkflowEventPublisher`, or production `WorkOrderPort` into this probe. Do not call `approve()`.

- [ ] **Step 4: Run alert preflight and workflow regression tests**

```powershell
.\mvnw.cmd -B "-Dtest=RejectingPreflightWorkOrderPortTest,AlertPreflightWorkflowFactoryTest,AlertWorkflowPreflightProbeTest,AlertWorkflowTest,AlertWorkflowFailureTest" test
```

Expected: all tests pass; existing approval and normal Mock work-order behavior remains unchanged.

- [ ] **Step 5: Commit only the isolated alert slice**

```powershell
git add -- src/main/java/com/example/smartpark/showcase/AlertShowcaseCondition.java src/main/java/com/example/smartpark/showcase/RejectingPreflightWorkOrderPort.java src/main/java/com/example/smartpark/showcase/AlertPreflightWorkflowFactory.java src/main/java/com/example/smartpark/showcase/AlertWorkflowPreflightProbe.java src/test/java/com/example/smartpark/showcase/RejectingPreflightWorkOrderPortTest.java src/test/java/com/example/smartpark/showcase/AlertPreflightWorkflowFactoryTest.java src/test/java/com/example/smartpark/showcase/AlertWorkflowPreflightProbeTest.java
git commit -m "feat: add side-effect-free alert showcase probe"
```

### Task 4: Probe ASR, Voice Agent, and TTS With Guaranteed Cleanup

**Files:**

- Create: `src/main/java/com/example/smartpark/showcase/VoiceAssistantPreflightProbe.java`
- Test: `src/test/java/com/example/smartpark/showcase/VoiceAssistantPreflightProbeTest.java`

**Interfaces:**

- Consumes the existing `StreamingAsrPort`, `VoiceAnswerAgent`, and `StreamingTtsPort` only.
- Uses `"showcase-preflight-" + UUID.randomUUID()` to create unique session and turn identifiers.
- ASR success means terminal close without `onError`; silence is not treated as a speech-accuracy assertion.
- Agent success requires nonblank answer text, non-empty evidence references, non-empty tool calls, and at least one successful tool completion callback for `DEV-ENERGY-001 现在用了多少电？`.
- TTS success requires at least one non-empty audio chunk and `onCompleted`, with neither `onError` nor `onInterrupted`.
- `cancel` is invoked for every started ASR or TTS turn in `finally`.

- [ ] **Step 1: Write failing success, failure, and cleanup tests**

Create test fakes that invoke listeners synchronously so the tests never sleep:

```java
@Test
void passesACompleteProviderChainAndCancelsBothTurns() {
    CompletingAsrPort asr = new CompletingAsrPort();
    CompletingTtsPort tts = new CompletingTtsPort(new byte[] { 1, 2 });
    VoiceAnswerAgent agent = mock(VoiceAnswerAgent.class);
    VoiceAnswer answer = new VoiceAnswer(
            "当前用电正常",
            List.of("DEV-ENERGY-001"),
            List.of(new ToolCallRecord(
                    "lookupEnergyConsumption", "meterId=DEV-ENERGY-001", "currentKwh=120")));
    when(agent.answer(anyString(), anyString(), eq("DEV-ENERGY-001 现在用了多少电？"), any()))
            .thenAnswer(invocation -> {
                VoiceAnswerAgent.Listener listener = invocation.getArgument(3);
                listener.onToolStarted("lookupEnergyConsumption", "meterId=DEV-ENERGY-001");
                listener.onToolCompleted("lookupEnergyConsumption", true);
                listener.onTextDelta(answer.text());
                return answer;
            });

    ShowcaseProbeResult result = new VoiceAssistantPreflightProbe(asr, agent, tts).probe();

    assertThat(result).isEqualTo(ShowcaseProbeResult.PASSED);
    assertThat(asr.cancelled).isTrue();
    assertThat(tts.cancelled).isTrue();
}
```

Add explicit test fakes and assertions for these cases:

- ASR emits `onError` then `onClosed`: result is `FAILED`, Agent and TTS are not called, ASR is cancelled.
- Agent throws: result is `FAILED`, ASR is cancelled, TTS is not started.
- Agent returns blank text, no evidence, no tool calls, or no successful tool callback: result is `FAILED`.
- TTS completes with only zero-length chunks: result is `FAILED` and TTS is cancelled.
- TTS emits `onError` or `onInterrupted`: result is `FAILED` and TTS is cancelled.

- [ ] **Step 2: Run the voice probe test to verify it fails**

```powershell
.\mvnw.cmd -B "-Dtest=VoiceAssistantPreflightProbeTest" test
```

Expected: compilation fails because `VoiceAssistantPreflightProbe` is absent.

- [ ] **Step 3: Implement the bounded three-stage probe**

Register it only for enabled voice:

```java
@Component
@ConditionalOnProperty(prefix = "smartpark.voice", name = "enabled", havingValue = "true")
public final class VoiceAssistantPreflightProbe implements ShowcasePreflightProbe {
    private static final String QUESTION = "DEV-ENERGY-001 现在用了多少电？";
    private static final int SILENCE_FRAMES = 50;
    private static final int PCM_BYTES_PER_FRAME = 640;
}
```

For ASR, call `start`, send 50 newly allocated zero-filled 640-byte frames, call `commit`, and await a `CountDownLatch` released by `onClosed`. Track any `onError` in an `AtomicBoolean`. For Agent, use a listener that records successful tool completion without retaining answer deltas. For TTS, release a second latch only on terminal callbacks, record whether any chunk has `audio.length > 0`, immediately clear callback audio with `Arrays.fill(audio, (byte) 0)`, and require `onCompleted`.

Use interruptible `CountDownLatch.await()` rather than a second timeout value; Task 1 owns the one 90-second deadline and interrupts the probe on expiry. Wrap all three stages in `try/finally`, track whether ASR/TTS started, and cancel only started turns. Restore interruption before returning `FAILED` from an interrupted wait. Do not log transcript text, answer text, or audio.

- [ ] **Step 4: Run voice probe, privacy, and provider-adapter regressions**

```powershell
.\mvnw.cmd -B "-Dtest=VoiceAssistantPreflightProbeTest,VoicePrivacyBoundariesTest,VoiceAudioPrivacyTest,VoiceProviderConfigurationTest,VoiceAnswerAgentTest" test
```

Expected: all tests pass offline; no DashScope network call occurs.

- [ ] **Step 5: Commit the voice probe**

```powershell
git add -- src/main/java/com/example/smartpark/showcase/VoiceAssistantPreflightProbe.java src/test/java/com/example/smartpark/showcase/VoiceAssistantPreflightProbeTest.java
git commit -m "feat: probe realtime voice provider chain"
```

### Task 5: Expose the Admin-Only Preflight Boundary

**Files:**

- Create: `src/main/java/com/example/smartpark/web/ShowcasePreflightController.java`
- Test: `src/test/java/com/example/smartpark/web/ShowcasePreflightControllerTest.java`
- Test: `src/test/java/com/example/smartpark/showcase/ShowcasePreflightRegistrationTest.java`

**Interfaces:**

- Produces `POST /api/showcase/preflight` and returns `ShowcasePreflightReport`.
- Requires the existing `DemoRole.require(role, DemoRole.ADMIN)` guard.
- Probe registration tests prove the four classes expose the expected unique IDs and that default disabled properties do not create alert, analytics, or voice probe beans.

- [ ] **Step 1: Write failing MVC and registration tests**

```java
@WebMvcTest(ShowcasePreflightController.class)
@Import(PreflightFixture.class)
class ShowcasePreflightControllerTest {
    @Test
    void rejectsMissingOrNonAdminRole() throws Exception {
        mockMvc.perform(post("/api/showcase/preflight"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/showcase/preflight").header("X-Demo-Role", "OPERATOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    void returnsOnlySafeResultsForAdmin() throws Exception {
        mockMvc.perform(post("/api/showcase/preflight").header("X-Demo-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].scenarioId").value("OPERATIONS_ANALYSIS"))
                .andExpect(jsonPath("$.results[0].status").value("NOT_READY"))
                .andExpect(jsonPath("$.results[0].reason").value("在线验证未通过"))
                .andExpect(jsonPath("$.results[0].verifiedAt").doesNotExist());
    }
}
```

Build the MVC fixture with a real coordinator and a probe that throws `IllegalStateException("provider-secret-body")`; assert the serialized body does not contain that string.

In `ShowcasePreflightRegistrationTest`, define the runner and imported probe set exactly:

```java
private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(ExpertCollaborationService.class,
                () -> mock(ExpertCollaborationService.class))
        .withBean(OperationsAnalysisService.class,
                () -> mock(OperationsAnalysisService.class))
        .withBean(AlertTriageAgent.class, () -> mock(AlertTriageAgent.class))
        .withBean(AlertDiagnosisAgent.class, () -> mock(AlertDiagnosisAgent.class))
        .withBean(DevicePort.class, () -> mock(DevicePort.class))
        .withBean(AlertPort.class, () -> mock(AlertPort.class))
        .withBean(KnowledgePort.class, () -> mock(KnowledgePort.class))
        .withBean(EnergyPort.class, () -> mock(EnergyPort.class))
        .withBean(SecurityPort.class, () -> mock(SecurityPort.class))
        .withBean(StreamingAsrPort.class, () -> mock(StreamingAsrPort.class))
        .withBean(VoiceAnswerAgent.class, () -> mock(VoiceAnswerAgent.class))
        .withBean(StreamingTtsPort.class, () -> mock(StreamingTtsPort.class))
        .withUserConfiguration(ProbeFixture.class);

@Test
void defaultModesRegisterOnlyTheOnlineCollaborationProbe() {
    runner.run(context -> assertThat(context.getBeansOfType(ShowcasePreflightProbe.class).values())
            .extracting(ShowcasePreflightProbe::scenarioId)
            .containsExactly(ShowcaseScenarioId.EXPERT_COLLABORATION));
}

@Test
void fullShowcaseModesRegisterExactlyFourUniqueProbes() {
    runner.withPropertyValues(
            "spring.ai.dashscope.enabled=true",
            "smartpark.knowledge.mode=rag",
            "smartpark.customer-service.answer-mode=dashscope",
            "smartpark.analytics.enabled=true",
            "smartpark.voice.enabled=true")
            .run(context -> assertThat(context.getBeansOfType(ShowcasePreflightProbe.class).values())
                    .extracting(ShowcasePreflightProbe::scenarioId)
                    .containsExactlyInAnyOrder(ShowcaseScenarioId.values()));
}

@Configuration(proxyBeanMethods = false)
@Import({
        ExpertCollaborationPreflightProbe.class,
        OperationsAnalysisPreflightProbe.class,
        AlertPreflightWorkflowFactory.class,
        AlertWorkflowPreflightProbe.class,
        VoiceAssistantPreflightProbe.class
})
static class ProbeFixture { }
```

- [ ] **Step 2: Run the boundary tests to verify the controller is absent**

```powershell
.\mvnw.cmd -B "-Dtest=ShowcasePreflightControllerTest,ShowcasePreflightRegistrationTest" test
```

Expected: compilation fails because the controller is absent.

- [ ] **Step 3: Implement the controller and preserve safe serialization**

```java
@RestController
@RequestMapping("/api/showcase")
public final class ShowcasePreflightController {
    private final ShowcasePreflightService preflight;

    public ShowcasePreflightController(ShowcasePreflightService preflight) {
        this.preflight = Objects.requireNonNull(preflight, "preflight");
    }

    @PostMapping("/preflight")
    public ShowcasePreflightReport preflight(
            @RequestHeader(value = "X-Demo-Role", required = false) String role) {
        DemoRole.require(role, DemoRole.ADMIN);
        return preflight.run();
    }
}
```

Do not add a request body, query parameter, scenario selector, skip flag, or exception diagnostic field.

- [ ] **Step 4: Run web, catalog, and registration regressions**

```powershell
.\mvnw.cmd -B "-Dtest=ShowcasePreflightControllerTest,ShowcasePreflightRegistrationTest,ShowcaseScenarioControllerTest,ShowcaseScenarioCatalogTest" test
```

Expected: all tests pass; GET catalog behavior is unchanged except that successful preflight receipts can now make enabled scenarios READY.

- [ ] **Step 5: Commit the HTTP boundary**

```powershell
git add -- src/main/java/com/example/smartpark/web/ShowcasePreflightController.java src/test/java/com/example/smartpark/web/ShowcasePreflightControllerTest.java src/test/java/com/example/smartpark/showcase/ShowcasePreflightRegistrationTest.java
git commit -m "feat: expose admin showcase preflight"
```

### Task 6: Add the Opt-In Full-Showcase Compose Overlay

**Files:**

- Create: `compose.showcase.yaml`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/java/com/example/smartpark/showcase/ShowcaseComposeConfigurationTest.java`

**Interfaces:**

- `compose.showcase.yaml` changes only the backend environment.
- Produces exact values `rag`, `dashscope`, `true`, and `http://localhost:5173,http://127.0.0.1:5173`.
- Consumes DashScope and analytics credentials from `compose.analytics.yaml`; no credential is duplicated in the showcase overlay.
- Maps `SMARTPARK_SHOWCASE_PREFLIGHT_TIMEOUT` to `smartpark.showcase.preflight-timeout`, default `90s`.

- [ ] **Step 1: Write the failing Compose contract test**

```java
@Test
void showcaseOverlayEnablesEveryOnlineModeAndBothLocalOrigins() throws Exception {
    String overlay = Files.readString(Path.of("compose.showcase.yaml"));

    assertThat(overlay)
            .contains("SMARTPARK_KNOWLEDGE_MODE: rag")
            .contains("SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope")
            .contains("SMARTPARK_VOICE_ENABLED: \"true\"")
            .contains("SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173")
            .doesNotContain("AI_DASHSCOPE_API_KEY:")
            .doesNotContain("SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD:")
            .doesNotContain("SMARTPARK_ANALYTICS_DB_RO_PASSWORD:");
}

@Test
void defaultComposeRemainsOfflineAndApplicationMapsThePreflightTimeout() throws Exception {
    assertThat(Files.readString(Path.of("compose.yaml")))
            .contains("SPRING_AI_DASHSCOPE_ENABLED: \"false\"")
            .contains("SMARTPARK_ANALYTICS_ENABLED: \"false\"")
            .doesNotContain("SMARTPARK_VOICE_ENABLED: \"true\"");
    assertThat(Files.readString(Path.of("src/main/resources/application.yml")))
            .contains("preflight-timeout: ${SMARTPARK_SHOWCASE_PREFLIGHT_TIMEOUT:90s}");
}
```

- [ ] **Step 2: Run the Compose test to verify the overlay is absent**

```powershell
.\mvnw.cmd -B "-Dtest=ShowcaseComposeConfigurationTest" test
```

Expected: FAIL because `compose.showcase.yaml` does not exist.

- [ ] **Step 3: Add the overlay and application mapping**

Create exactly:

```yaml
services:
  backend:
    environment:
      SMARTPARK_KNOWLEDGE_MODE: rag
      SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE: dashscope
      SMARTPARK_VOICE_ENABLED: "true"
      SMARTPARK_VOICE_ALLOWED_ORIGINS: http://localhost:5173,http://127.0.0.1:5173
```

Add under `smartpark.showcase` in `application.yml`:

```yaml
showcase:
  preflight-timeout: ${SMARTPARK_SHOWCASE_PREFLIGHT_TIMEOUT:90s}
```

- [ ] **Step 4: Validate tests and the merged Compose model with safe temporary values**

```powershell
.\mvnw.cmd -B "-Dtest=ShowcaseComposeConfigurationTest,AnalyticsComposeSecurityTest,VoiceConfigurationReviewTest" test
$env:AI_DASHSCOPE_API_KEY = 'compose-contract-only'
$env:SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD = 'compose-contract-admin-only'
$env:SMARTPARK_ANALYTICS_DB_RO_PASSWORD = 'compose-contract-reader-only'
docker compose -f compose.yaml -f compose.analytics.yaml -f compose.showcase.yaml --profile analytics config --quiet
Remove-Item Env:AI_DASHSCOPE_API_KEY,Env:SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD,Env:SMARTPARK_ANALYTICS_DB_RO_PASSWORD
```

Expected: all tests pass and Compose exits zero. The temporary strings are process-only contract values and are never written to a file.

- [ ] **Step 5: Commit the runtime configuration slice**

```powershell
git add -- compose.showcase.yaml src/main/resources/application.yml src/test/java/com/example/smartpark/showcase/ShowcaseComposeConfigurationTest.java
git commit -m "feat: add full showcase compose overlay"
```

### Task 7: Add an Exact Four-Scenario Verifier and Operator Documentation

**Files:**

- Create: `scripts/verify-showcase.ps1`
- Create: `scripts/verify-showcase.tests.ps1`
- Modify: `.github/workflows/ci.yml`
- Modify: `README.md`

**Interfaces:**

- `Assert-ShowcaseReport([object] $Report)` returns normally only for four unique expected IDs, all `READY`, each with an ISO-8601 `verifiedAt`.
- Running `verify-showcase.ps1` calls `POST $BaseUrl/api/showcase/preflight` with `X-Demo-Role: ADMIN` and exits nonzero on any invalid report.
- Dot-sourcing the script defines the assertion function without making an HTTP request.
- CI runs `pwsh -NoProfile -File ./scripts/verify-showcase.tests.ps1`.

- [ ] **Step 1: Write failing behavioral tests for the script contract**

Create a dependency-free test script that dot-sources the verifier and invokes it with in-memory objects:

```powershell
. "$PSScriptRoot/verify-showcase.ps1"

$expected = @('ALERT_WORKFLOW', 'EXPERT_COLLABORATION', 'OPERATIONS_ANALYSIS', 'VOICE_ASSISTANT')
$ready = [pscustomobject]@{
    results = @($expected | ForEach-Object {
        [pscustomobject]@{ scenarioId = $_; status = 'READY'; verifiedAt = '2026-08-31T10:00:00Z' }
    })
}

Assert-ShowcaseReport -Report $ready

function Assert-Rejected([object] $report, [string] $caseName) {
    try {
        Assert-ShowcaseReport -Report $report
        throw "Verifier accepted invalid case: $caseName"
    } catch {
        if ($_.Exception.Message -like 'Verifier accepted invalid case:*') { throw }
    }
}

Assert-Rejected ([pscustomobject]@{ results = @($ready.results | Select-Object -First 3) }) 'missing id'
Assert-Rejected ([pscustomobject]@{ results = @($ready.results + $ready.results[0]) }) 'duplicate id'
$notReady = @($ready.results | ForEach-Object { $_.PSObject.Copy() })
$notReady[0].status = 'NOT_READY'
Assert-Rejected ([pscustomobject]@{ results = $notReady }) 'not ready'
$badTime = @($ready.results | ForEach-Object { $_.PSObject.Copy() })
$badTime[0].verifiedAt = 'not-a-time'
Assert-Rejected ([pscustomobject]@{ results = $badTime }) 'invalid timestamp'
```

- [ ] **Step 2: Run the PowerShell tests to verify the verifier is absent**

```powershell
powershell.exe -NoProfile -File .\scripts\verify-showcase.tests.ps1
```

Expected: FAIL because `verify-showcase.ps1` does not exist.

- [ ] **Step 3: Implement exact set validation and the live POST**

The verifier starts with parameters and a pure assertion function:

```powershell
[CmdletBinding()]
param([string]$BaseUrl = 'http://127.0.0.1:8080')

function Assert-ShowcaseReport {
    param([Parameter(Mandatory)][object]$Report)
    $expected = @('ALERT_WORKFLOW', 'EXPERT_COLLABORATION', 'OPERATIONS_ANALYSIS', 'VOICE_ASSISTANT')
    $results = @($Report.results)
    $ids = @($results | ForEach-Object { [string]$_.scenarioId })
    if ($results.Count -ne 4 -or @($ids | Sort-Object -Unique).Count -ne 4) {
        throw 'Showcase preflight must return four unique scenarios.'
    }
    if (@(Compare-Object ($expected | Sort-Object) ($ids | Sort-Object)).Count -ne 0) {
        throw 'Showcase preflight scenario set is incomplete.'
    }
    foreach ($result in $results) {
        if ($result.status -ne 'READY') {
            throw "Showcase scenario is not ready: $($result.scenarioId)"
        }
        $parsed = [DateTimeOffset]::MinValue
        if (-not [DateTimeOffset]::TryParse([string]$result.verifiedAt, [ref]$parsed)) {
            throw "Showcase scenario has no valid verification time: $($result.scenarioId)"
        }
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $uri = "$($BaseUrl.TrimEnd('/'))/api/showcase/preflight"
    $report = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ 'X-Demo-Role' = 'ADMIN' }
    Assert-ShowcaseReport -Report $report
    $report.results | Select-Object scenarioId, status, verifiedAt | Format-Table -AutoSize
}
```

Do not print the full report, reason internals, environment, or headers.

- [ ] **Step 4: Add CI and README instructions, then run script tests**

Add this backend-job step after Maven tests:

```yaml
- name: Test showcase verifier
  shell: pwsh
  run: ./scripts/verify-showcase.tests.ps1
```

Update README in four exact places:

- Change the realtime-voice capability row from “reserved/disabled” to “opt-in full-showcase mode”.
- Document the three-layer Compose command with `--env-file .env` and the three required ignored environment values.
- Document `.\scripts\verify-showcase.ps1` and that READY lasts 15 minutes in the current process.
- State that alert preflight never approves or creates a work order, and server voice preflight does not replace browser microphone permission and one manual spoken round trip.

Run:

```powershell
powershell.exe -NoProfile -File .\scripts\verify-showcase.tests.ps1
git diff --check
```

Expected: script exits zero and `git diff --check` prints nothing.

- [ ] **Step 5: Commit the verifier and operating guide**

```powershell
git add -- scripts/verify-showcase.ps1 scripts/verify-showcase.tests.ps1 .github/workflows/ci.yml README.md
git commit -m "docs: add full showcase verification workflow"
```

### Task 8: Verify the Full Stack and Update the Existing Pull Request

**Files:**

- Verify only; do not edit or stage concurrent `ui/` changes during this task.

**Interfaces:**

- Consumes all seven implementation commits.
- Produces passing offline CI checks, a healthy three-layer Docker stack, an exact four-READY report, no preflight-created work order, one manual browser voice round trip, and an updated `codex/immersive-workbench-redesign` branch for PR #33.

- [ ] **Step 1: Run complete offline verification before making success claims**

Invoke the required `superpowers:verification-before-completion` skill, then run:

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run typecheck
node.exe scripts/verify-layout.mjs
npm.cmd run build
Pop-Location
powershell.exe -NoProfile -File .\scripts\verify-showcase.tests.ps1
git diff --check
```

Expected: Maven, all frontend tests, typecheck, responsive layout verification, frontend build, PowerShell behavior tests, and whitespace checks pass. If an existing concurrent UI change fails, diagnose it separately and do not absorb it into the showcase commits without confirming ownership.

- [ ] **Step 2: Validate the real environment without exposing secret values**

Check only presence, never values, for the three required variables in the ignored environment source. If the two database passwords are not persisted, create task-scoped random values in the current PowerShell process:

```powershell
$showcaseAdminBytes = New-Object byte[] 32
$showcaseReaderBytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($showcaseAdminBytes)
[Security.Cryptography.RandomNumberGenerator]::Fill($showcaseReaderBytes)
$env:SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD = [Convert]::ToHexString($showcaseAdminBytes)
$env:SMARTPARK_ANALYTICS_DB_RO_PASSWORD = [Convert]::ToHexString($showcaseReaderBytes)
```

Load `AI_DASHSCOPE_API_KEY` through the ignored `.env` with Docker Compose; do not echo it or copy it into the worktree.

- [ ] **Step 3: Rebuild and start the full stack from this worktree**

```powershell
docker compose -p springaialibaba -f C:\Users\Henry\code\springaialibaba\compose.yaml down
docker compose --env-file C:\Users\Henry\code\springaialibaba\.env -f compose.yaml -f compose.analytics.yaml -f compose.showcase.yaml --profile analytics up --build -d
docker compose --env-file C:\Users\Henry\code\springaialibaba\.env -f compose.yaml -f compose.analytics.yaml -f compose.showcase.yaml --profile analytics ps
```

Expected: the previous root-workspace containers stop without deleting volumes; PostgreSQL, time parser, backend, and frontend from the feature worktree are healthy with no host-port conflict. If task-scoped database passwords were needed, run the start and status commands in the same PowerShell process so environment precedence supplies them.

- [ ] **Step 4: Prove all four server chains and the no-write alert boundary**

```powershell
.\scripts\verify-showcase.ps1
Invoke-RestMethod http://127.0.0.1:8080/api/operations/capabilities
Invoke-RestMethod http://127.0.0.1:8080/api/showcase/scenarios
```

Expected: the verifier prints exactly four READY rows; capabilities report RAG, DashScope customer answers, analytics enabled, collaboration available, and voice enabled; the catalog makes all four scenarios launchable. Re-run `RejectingPreflightWorkOrderPortTest,AlertPreflightWorkflowFactoryTest,AlertWorkflowPreflightProbeTest` after the live preflight; a READY alert result plus those structural tests proves that the preflight factory never receives the production `WorkOrderPort` and every attempted write fails.

- [ ] **Step 5: Perform browser-specific acceptance at `http://127.0.0.1:5173/`**

Use the in-app browser control skill. Refresh the homepage, enter each of the four scenes, and verify they are distinct. In alert demo, confirm the flow stops at human approval. In voice demo, grant microphone permission and complete one spoken question → transcript → evidence-backed answer → audio playback round trip. Treat microphone denial or missing audio hardware as a browser-environment blocker, not as a server preflight success.

- [ ] **Step 6: Audit commits, push, and update PR #33**

```powershell
git status --short
git log --oneline origin/codex/immersive-workbench-redesign..HEAD
git diff --name-only origin/codex/immersive-workbench-redesign...HEAD
git push origin codex/immersive-workbench-redesign
Remove-Item Env:SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD,Env:SMARTPARK_ANALYTICS_DB_RO_PASSWORD -ErrorAction SilentlyContinue
```

Expected: only the explicit backend, Compose, script, CI, README, design, and plan files from this feature are committed; concurrent `ui/` changes remain unstaged unless their owner has committed them separately. Confirm PR #33 shows the new commits and both backend/frontend checks pass.

## Plan Self-Review

### Spec coverage

- Root cause and readiness receipts: Tasks 1 and 5 add the bounded coordinator and admin boundary; failed probes clear stale success.
- Scenario-owned architecture: Tasks 2–4 isolate collaboration, analytics, alert, and voice logic behind one shared SPI; the coordinator imports none of those subsystems.
- Alert safety: Task 3 creates a fresh store/publisher and structural write rejection, then asserts `WAITING_APPROVAL`, diagnosis, no errors, and no work order.
- Voice scope and privacy: Task 4 verifies ASR terminal behavior, evidence-constrained Agent output, TTS data/completion, interruption, cancellation, and audio disposal without claiming browser microphone coverage.
- Configuration and secrets: Task 6 leaves default Compose offline and adds only an opt-in capability overlay; Task 8 uses ignored/process-only credentials.
- Exact four-scenario truth: Task 7 behavior-tests missing, duplicate, non-ready, and invalid-time reports; Task 8 runs it against the live stack.
- Manual client boundary: Task 8 separately verifies WebSocket, microphone permission, transcript, and playback.
- PR integration and dirty-worktree safety: every commit uses explicit paths, and Task 8 audits the branch before push.

### Deferred-instruction scan

Every created type, method signature, condition, fixture, command, expected state, commit path, and public string is named. The plan contains no unspecified implementation branch or unnamed error policy.

### Type consistency

- All probes return the same `ShowcaseProbeResult` enum and the coordinator alone translates it to `ShowcasePreflightStatus`.
- The four IDs come from the existing `ShowcaseScenarioId`; Java report records and the PowerShell expected set use the same literals.
- Alert factory returns the existing final `AlertWorkflow`; the probe consumes the existing `WorkflowSnapshot` fields without changing workflow APIs.
- Voice probe consumes the exact current port callback signatures and the existing four-argument `VoiceAnswerAgent.answer` overload.
- `ShowcaseProperties.getPreflightTimeout()` is used once by generic Spring wiring and nowhere as a second scenario-specific timeout.
