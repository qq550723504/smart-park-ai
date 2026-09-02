# 运营日报会话快照 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with review checkpoints.

**Goal:** 在运营看板中增加一个由真实只读分析结果驱动的、可手动触发的会话级运营日报。

**Architecture:** `OperationsDailyReportService` 只负责固定章节编排、报告生命周期和报告级执行事件；章节查询通过 `OperationsAnalysisService.startAndAwait` 复用现有时间解析、指标目录、SQL AST 安全校验、成本门禁和结果脱敏。报告和章节结果存放在有界进程内存储中，不新增 SQL 通道、调度器或业务写操作。

**Tech Stack:** Java 17, Spring Boot 4, Spring MVC, JUnit 5/AssertJ/MockMvc, Vue 3, TypeScript, Vitest, 现有 SSE `ExecutionEventPublisher` 与 `useExecutionTrace`。

**Spec:** `docs/superpowers/specs/2026-09-02-operations-daily-report-design.md`

## Global Constraints

- 报告固定且只包含 `ENERGY_BASELINE`、`PARKING_UTILIZATION`、`ALERT_RISK` 三个章节及其服务端固定问题。
- 不允许客户端提交 SQL、任意报告问题或章节选择；空对象以外的创建请求返回参数错误。
- 章节必须复用现有运营分析链路；不得新增 SQL 生成器、报表数据库、定时任务、邮件、通知或写端口调用。
- 报告为进程内会话快照；报告存储和结果行有界，服务重启后清空，同一进程同一时刻只允许一个编排运行。
- 角色只允许 `OPERATOR`、`ADMIN`；错误、超时、澄清和无数据必须公开稳定状态，不得用默认数字或模型原文补齐。
- 报告级事件沿用现有 `ExecutionEvent` 字段和 `ExecutionEventPublisher`，章节失败使用非终态事件表达，报告终态统一关闭事件流。
- 所有 API、DTO 和 UI 只展示安全摘要、时间范围、白名单列、有限结果行和稳定阶段名，不暴露 SQL、Prompt、供应商响应、凭据或原始敏感文本。

---

### Task 1: Define fixed report sections and bounded report state

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsReportSection.java`
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsDailyReportDefinition.java`
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsReportSectionStatus.java`
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsDailyReport.java`
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsDailyReportStore.java`
- Create: `src/test/java/com/example/smartpark/analytics/report/OperationsDailyReportDefinitionTest.java`
- Create: `src/test/java/com/example/smartpark/analytics/report/OperationsDailyReportStoreTest.java`

**Interfaces:**
- `OperationsReportSection(String id, String title, String question)` is immutable and rejects blank fields.
- `OperationsDailyReportDefinition.sections()` returns an immutable list in the order `ENERGY_BASELINE`, `PARKING_UTILIZATION`, `ALERT_RISK`.
- `OperationsReportSectionStatus` contains `PENDING`, `RUNNING`, `COMPLETED`, `FAILED`.
- `OperationsDailyReport` contains `UUID runId`, report status (`RUNNING|COMPLETED|PARTIAL|FAILED`), `Instant createdAt`, `Instant updatedAt`, and immutable `List<SectionResult>`; `SectionResult` contains section definition fields, status, safe summary, row count, truncated flag, columns, rows, optional time-resolution metadata and failure stage.
- `OperationsDailyReportStore` exposes `create(UUID, Instant)`, `get(UUID)`, `update(OperationsDailyReport)`, `tryAcquireRun()`, `releaseRun()`, and `activeRun()`; it retains at most 10 terminal reports and never returns mutable collections.

- [ ] **Step 1: Write the failing tests**

Add tests asserting:

```java
assertThat(OperationsDailyReportDefinition.sections())
        .extracting(OperationsReportSection::id)
        .containsExactly("ENERGY_BASELINE", "PARKING_UTILIZATION", "ALERT_RISK");
assertThat(OperationsDailyReportDefinition.sections())
        .allSatisfy(section -> assertThat(section.question()).isNotBlank());
assertThat(store.tryAcquireRun()).isTrue();
assertThat(store.tryAcquireRun()).isFalse();
store.releaseRun();
assertThat(store.tryAcquireRun()).isTrue();
```

Cover blank section values, immutable sections/results, 10-report terminal retention, unknown IDs returning empty, and timestamps preserved during updates.

- [ ] **Step 2: Run tests to verify they fail**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=OperationsDailyReportDefinitionTest,OperationsDailyReportStoreTest" test
```

Expected: compilation failure because the report definition, state model and store do not exist.

- [ ] **Step 3: Implement the minimal fixed definition and store**

Use `List.of(...)` for the three exact sections. Store immutable report copies under a synchronized lock, enforce the active-run boolean, and evict the oldest terminal report only after inserting a newer terminal report. Do not add persistence or a scheduler.

- [ ] **Step 4: Run focused tests**

Run the same Maven command and expect all definition/store tests to pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/analytics/report src/test/java/com/example/smartpark/analytics/report
git commit -m "feat: define operations daily report state"
```

### Task 2: Add a safe awaitable entry point to the existing analysis lifecycle

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java`
- Modify: `src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java`

**Interfaces:**
- Add `public CompletableFuture<AnalysisRunStore.RunRecord> startAndAwait(String question)`.
- Keep `start`, `get`, `abort` and clarification behavior source-compatible.
- Maintain a private `Map<UUID, CompletableFuture<RunRecord>> completionWaiters` guarded by `lifecycleLock`.

- [ ] **Step 1: Write the failing tests**

Add tests with the existing scripted graph runner and executor seams:

```java
CompletableFuture<RunRecord> future = service.startAndAwait("过去5天各楼宇能耗基线偏差");
assertThat(future).succeedsWithin(Duration.ofSeconds(1))
        .extracting(RunRecord::status).isEqualTo("COMPLETED");
```

Add a race test where the executor completes synchronously before the waiter is registered, a failed run completing with `FAILED`, and a clarification result completing with `NEEDS_CLARIFICATION` without leaving a waiter in the map. Existing single-active-run and timeout tests must remain unchanged.

- [ ] **Step 2: Run focused tests to verify the new contract fails**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=OperationsAnalysisServiceTest" test
```

Expected: compilation failure because `startAndAwait` is missing.

- [ ] **Step 3: Implement completion waiters without changing the public start lifecycle**

Refactor the current `start` admission path only as needed to register a future under `lifecycleLock` after the run is accepted. In `persistOutcome`, `persistFailure`, `terminate`, and clarification-expiry failure paths, complete and remove the matching waiter after the terminal record wins. If the record is already terminal when `startAndAwait` installs the waiter, complete immediately. On exceptional setup failure, complete exceptionally and remove the waiter. Do not block an executor thread by polling or sleeping.

- [ ] **Step 4: Run focused and compatibility tests**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=OperationsAnalysisServiceTest,OperationsAnalysisGraphTest" test
```

Expected: all existing analysis lifecycle tests and the new await tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java
git commit -m "feat: expose awaitable operations analysis runs"
```

### Task 3: Implement report orchestration and report-level execution events

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/report/OperationsDailyReportService.java`
- Create: `src/test/java/com/example/smartpark/analytics/report/OperationsDailyReportServiceTest.java`
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java`

**Interfaces:**
- Constructor dependencies: `OperationsAnalysisService`, `OperationsDailyReportStore`, `ExecutionEventPublisher`, `Clock`.
- `public OperationsDailyReport start()` creates a report UUID, stores `RUNNING`, publishes report `RUN_STARTED`, and asynchronously chains the three fixed sections.
- `public OperationsDailyReport get(UUID runId)` returns the immutable current report or throws `NoSuchElementException`.
- The service uses `analysisService.startAndAwait(section.question())` sequentially. It maps `COMPLETED` to safe section data, `NEEDS_CLARIFICATION` to `FAILED/REPORT_CLARIFICATION_REQUIRED`, and other terminal/error states to `FAILED` with a stable stage.

- [ ] **Step 1: Write the failing service tests**

Use a fake analysis service seam to assert:

```java
OperationsDailyReport report = service.start();
assertThat(report.status()).isEqualTo("RUNNING");
assertThat(runner.questions()).containsExactly(
        "过去5天各楼宇能耗基线偏差",
        "过去5天各停车区域停车利用率",
        "过去5天高风险告警数量");
```

Cover all-success → `COMPLETED`, one failed section → `PARTIAL` while later sections still run, all failed → `FAILED`, empty completed results retaining empty rows/summary, concurrent `start()` throwing `IllegalStateException`, and unknown report IDs.

Assert the publisher receives report-level events in sequence: `RUN_STARTED`, three section starts, three section outcomes, and exactly one terminal `COMPLETED` or `FAILED`. Section failure events must use `NODE_COMPLETED` with `ExecutionStatus.FAILED` and never include the thrown exception message.

- [ ] **Step 2: Run the focused service test to verify it fails**

Run:

```powershell
.\mvnw.cmd -q "-Dtest=OperationsDailyReportServiceTest" test
```

Expected: compilation failure because the orchestration service and production bean do not exist.

- [ ] **Step 3: Implement sequential orchestration**

Create the report record before starting any section. Chain `CompletableFuture` continuations so only one analysis run is active at a time. Update the immutable report after each section, publish a safe section event, and continue even after a section failure. At the end derive the report status from section statuses, release the store’s active-run slot, and publish one report terminal event. Catch setup/runtime failures into `FAILED` with a stable stage and release the slot in a final continuation.

Use the existing `ExecutionScenario.OPERATIONS_ANALYSIS`, `ExecutionStage.INITIALIZATION`, `ExecutionStage.ANALYSIS` and `ExecutionStage.COMPLETION`/`FAILURE`; do not add a new event schema.

- [ ] **Step 4: Run focused backend tests**

```powershell
.\mvnw.cmd -q "-Dtest=OperationsDailyReportServiceTest,OperationsAnalysisServiceTest" test
```

Expected: all report and analysis lifecycle tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/analytics/report/OperationsDailyReportService.java src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java src/test/java/com/example/smartpark/analytics/report/OperationsDailyReportServiceTest.java
git commit -m "feat: orchestrate operations daily report"
```

### Task 4: Expose the safe report REST contract

**Files:**
- Create: `src/main/java/com/example/smartpark/web/OperationsDailyReportController.java`
- Create: `src/main/java/com/example/smartpark/web/OperationsDailyReportDtos.java`
- Create: `src/test/java/com/example/smartpark/web/OperationsDailyReportControllerTest.java`

**Interfaces:**
- `POST /api/operations-reports/runs` accepts only an absent/empty JSON object and requires `X-Demo-Role: OPERATOR|ADMIN`; returns `202` with `runId` and `statusUrl`.
- `GET /api/operations-reports/runs/{runId}` requires the same roles and maps `OperationsDailyReport` to a stable JSON DTO.
- Controller is conditional on `smartpark.analytics.enabled=true`, matching `OperationsAnalysisController` behavior.

- [ ] **Step 1: Write the failing MockMvc tests**

Assert `202` creation, `200` status mapping, full section fields, `403` for `VIEWER` and `CUSTOMER_AGENT`, `400` for non-empty request body, `404` for unknown IDs, and `409` for a concurrent report. Serialize a report containing a fake SQL/Prompt-like internal value in the service seam and assert those fields cannot appear in the JSON.

- [ ] **Step 2: Run the focused controller test to verify it fails**

```powershell
.\mvnw.cmd -q "-Dtest=OperationsDailyReportControllerTest" test
```

Expected: compilation failure because the controller and DTO mapper do not exist.

- [ ] **Step 3: Implement controller and DTO mapping**

Use `DemoRole.require(role, DemoRole.OPERATOR, DemoRole.ADMIN)`. Validate the request body as an empty JSON object before calling the service. Map only the fields defined in the spec, preserve `null` for absent `failureStage`, and return no domain object directly.

- [ ] **Step 4: Run focused controller and service tests**

```powershell
.\mvnw.cmd -q "-Dtest=OperationsDailyReportControllerTest,OperationsDailyReportServiceTest" test
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/web/OperationsDailyReportController.java src/main/java/com/example/smartpark/web/OperationsDailyReportDtos.java src/test/java/com/example/smartpark/web/OperationsDailyReportControllerTest.java
git commit -m "feat: expose operations daily report API"
```

### Task 5: Add the operations-board report interaction

**Files:**
- Create: `ui/src/types/operationsReport.ts`
- Create: `ui/src/services/operationsReportApi.ts`
- Create: `ui/src/composables/useOperationsDailyReport.ts`
- Create: `ui/src/components/operations/OperationsDailyReport.vue`
- Create: `ui/src/components/operations/OperationsDailyReport.spec.ts`
- Modify: `ui/src/components/operations/OperationsBoard.vue`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/OperationsWorkbench.spec.ts`

**Interfaces:**
- `OperationsReportDto`, `OperationsReportSectionDto`, and `OperationsReportStatus` mirror the safe backend DTO exactly.
- `createOperationsDailyReport(role)` posts `{}` with `X-Demo-Role`; `getOperationsDailyReport(runId, role)` polls the status endpoint.
- `useOperationsDailyReport({ role, trace, pollIntervalMs, maxPolls })` exposes `report`, `phase`, `error`, `start()`, and `reset()`; `start()` subscribes to the report `runId` once accepted.
- `OperationsBoard` receives `role` and `trace`, renders the report component, and preserves the existing `open-analysis` event. `OperationsWorkbench` passes the existing role and trace.

- [ ] **Step 1: Write failing frontend tests**

Mock the report API and assert:

```ts
expect(wrapper.get('[data-report-generate]').isVisible()).toBe(true)
await wrapper.get('[data-report-generate]').trigger('click')
expect(trace.subscribe).toHaveBeenCalledWith('report-1')
expect(wrapper.get('[data-report-status]').text()).toContain('已完成')
```

Add cases for `OPERATOR`/`ADMIN` visibility, `VIEWER`/`CUSTOMER_AGENT` hidden button, partial failure retaining successful sections, no default numbers for empty data, retry replacing the old report, stale poll response suppression, and switching away from the page stopping polling.

- [ ] **Step 2: Run focused Vitest to verify it fails**

```powershell
Set-Location ui
npm run test:unit -- src/components/operations/OperationsDailyReport.spec.ts src/components/OperationsWorkbench.spec.ts
```

Expected: failure because the API, composable, component, and board integration do not exist.

- [ ] **Step 3: Implement API, composable, component, and integration**

Keep the board’s current real-analysis cards unchanged. Add a clearly labelled “本次会话运营日报” panel with an explicit empty state, per-section status cards, safe result tables and error/retry controls. Poll only while a report is non-terminal and the board is active. Use an operation generation counter so an old report cannot overwrite a newer one. Subscribe to the existing trace only after `202 Accepted`; do not fabricate progress from timers.

- [ ] **Step 4: Run focused and compatibility frontend tests**

```powershell
Set-Location ui
npm run test:unit -- src/components/operations/OperationsDailyReport.spec.ts src/components/operations/OperationsBoard.spec.ts src/components/OperationsWorkbench.spec.ts
npm run typecheck
```

Expected: all focused tests and typecheck pass.

- [ ] **Step 5: Commit**

```powershell
git add ui/src/types/operationsReport.ts ui/src/services/operationsReportApi.ts ui/src/composables/useOperationsDailyReport.ts ui/src/components/operations/OperationsDailyReport.vue ui/src/components/operations/OperationsDailyReport.spec.ts ui/src/components/operations/OperationsBoard.vue ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts
git commit -m "feat: add operations daily report panel"
```

### Task 6: Document boundaries and run final verification

**Files:**
- Modify: `README.md`
- Modify: `docs/customer-capabilities.md`
- Modify: `docs/architecture.md`
- Test/inspect: repository-wide backend and frontend suites

- [ ] **Step 1: Update documentation**

Document the three fixed report sections, manual session-only generation, OPERATOR/ADMIN role boundary, partial-failure semantics, no default values, and the fact that production scheduling/persistence/notifications require a separate design.

- [ ] **Step 2: Run complete verification**

```powershell
.\mvnw.cmd -q test
Set-Location ui
npm run typecheck
npm run test:unit
npm run build
Set-Location ..
git diff --check
```

Expected: all tests and build exit 0. Report the existing Vite large-chunk warning separately if it remains.

- [ ] **Step 3: Inspect the final diff**

Run `git status --short`, `git diff --stat origin/main...HEAD`, and a sensitive-field scan for `SQL`, `Prompt`, credentials and raw vendor responses in the new public DTOs. Confirm only intended report files and documentation changed.

- [ ] **Step 4: Commit documentation**

```powershell
git add README.md docs/customer-capabilities.md docs/architecture.md
git commit -m "docs: describe operations daily report boundaries"
```
