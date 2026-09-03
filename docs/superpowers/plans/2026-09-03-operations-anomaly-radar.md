# 运营异常雷达与告警证据链 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在运营看板中增加基于真实只读聚合数据的异常雷达，并支持按楼宇查看告警、设备、能耗和执行轨迹证据链。

**Architecture:** 保留现有 `AlertPort`、`DevicePort`、`EnergyPort` 的业务操作边界，新增三个只读分析查询端口，由现有 analytics 数据库视图适配器实现。`OperationsAnomalyService` 在应用层合并各域结果，`OperationsController` 暴露总览/证据接口，Vue 侧以独立雷达和抽屉组件消费安全 DTO，并通过事件回到工作台复用分析页和执行轨迹。

**Tech Stack:** Java 17、Spring Boot、现有 `ReadOnlyQueryExecutor`/Spring JDBC、JUnit 5/AssertJ、Vue 3 `<script setup>`、TypeScript、Vitest、Vue Test Utils。

**Spec:** `docs/superpowers/specs/2026-09-03-operations-anomaly-radar-design.md`

## Global Constraints

- 不在前端写死或推算“实时”业务数字，不生成未经定义的综合健康分或风险分。
- 不把告警、设备、能耗直接拼成未经验证的跨域 SQL Join；各域先独立读取，再在应用层按 `buildingId` 合并。
- 不复制安全事件中心的事件模型、归并规则或审批动作；安全事件只提供授权后的跳转/摘要。
- 不新增关闭告警、派单、通知、设备控制、预测性维护或模型推理能力。
- 不返回原始告警证据、个人信息、视频/音频内容或未脱敏自由文本。
- 默认时间窗为过去 7 天；离线设备继续使用最近 1 天快照口径，并在响应中返回 `asOf`。
- 聚合结果只读、无副作用；部分域失败返回 `domainStatus`，不把失败显示为零。

---

### Task 1: 建立只读分析查询边界和数据契约

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyQuery.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/AlertAnalyticsReader.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/DeviceAnalyticsReader.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/EnergyAnalyticsReader.java`
- Create: `src/test/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyQueryTest.java`

**Interfaces:**
- Produces `OperationsAnomalyQuery(Instant from, Instant to, String buildingId, String riskLevel, String category, String status, String deviceType)` with `validate(Duration maxWindow)` and `normalized(Clock clock)` methods.
- Produces `AlertAnalyticsReader.read(OperationsAnomalyQuery)` and `.evidence(String buildingId, OperationsAnomalyQuery)` returning immutable `AlertSnapshot`/`AlertReference` records.
- Produces `DeviceAnalyticsReader.read(OperationsAnomalyQuery)` and `.evidence(String buildingId, OperationsAnomalyQuery)` returning immutable `DeviceSnapshot`/`DeviceReference` records.
- Produces `EnergyAnalyticsReader.read(OperationsAnomalyQuery)` and `.evidence(String buildingId, OperationsAnomalyQuery)` returning immutable `EnergySnapshot`/`EnergyReference` records.

- [ ] **Step 1: Write failing query validation tests**

```java
@Test
void rejectsReversedAndOverlongWindows() {
    OperationsAnomalyQuery query = new OperationsAnomalyQuery(
            Instant.parse("2026-09-03T00:00:00Z"),
            Instant.parse("2026-09-02T00:00:00Z"), null, null, null, null, null);
    assertThatThrownBy(() -> query.validate(Duration.ofDays(31)))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./mvnw.cmd -Dtest=OperationsAnomalyQueryTest test`

Expected: FAIL because the query type and validation contract do not exist.

- [ ] **Step 3: Implement the records and validation**

Implement null-safe optional filters, require `from < to`, reject windows longer than the configured maximum, default missing windows to the previous seven days using the injected clock, and preserve `Asia/Shanghai` as the response timezone metadata. Do not silently convert invalid filters to empty strings.

- [ ] **Step 4: Add reader contracts and domain-safe records**

Define bounded list records with only `id`, `buildingId`, `deviceId/deviceType`, category/risk/status, timestamps, numeric measures, and already-redacted summaries. Include `asOf` on device snapshots and an explicit `available`/`failureCode` status on each domain snapshot. Keep these interfaces under `analytics.anomaly`; do not modify the three existing business ports.

- [ ] **Step 5: Run the focused tests**

Run: `./mvnw.cmd -Dtest=OperationsAnomalyQueryTest test`

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/smartpark/analytics/anomaly src/test/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyQueryTest.java
git commit -m "feat: define operations anomaly analytics boundary"
```

### Task 2: Implement analytics-view readers and conditional wiring

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/JdbcAlertAnalyticsReader.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/JdbcDeviceAnalyticsReader.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/JdbcEnergyAnalyticsReader.java`
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java`
- Create: `src/test/java/com/example/smartpark/analytics/anomaly/JdbcAnalyticsReaderTest.java`
- Create: `src/test/java/com/example/smartpark/analytics/AnalyticsAnomalyConfigurationTest.java`

**Interfaces:**
- Consumes the Task 1 reader contracts and the existing `ReadOnlyQueryExecutor` analytics boundary.
- Produces one Spring bean for each reader only when `smartpark.analytics.enabled=true`.

- [ ] **Step 1: Write reader tests against a mocked `ReadOnlyQueryExecutor`**

Cover the exact SQL boundary: alert reader selects only `analytics.v_alert_fact`, device reader selects only `analytics.v_device_snapshot`, energy reader selects only `analytics.v_energy_hourly`; every query binds `from`, `to`, and optional filters as parameters. Assert row mapping and that raw tables are never referenced. Stub `ReadOnlyQueryExecutor.execute(ValidatedSql, Map)` so tests verify the SQL/parameter contract without bypassing the production safety boundary.

- [ ] **Step 2: Run reader tests to verify they fail**

Run: `./mvnw.cmd -Dtest=JdbcAnalyticsReaderTest test`

Expected: FAIL because the JDBC reader implementations do not exist.

- [ ] **Step 3: Implement bounded, parameterized queries**

Use grouped queries for overview data and bounded recent-reference queries for evidence. Keep alert and device/energy queries separate; do not join their views. Route every statement through `ReadOnlyQueryExecutor` so the existing read-only transaction, timeout, row cap and view allow-list remain active. Map SQL `NULL` measures to an unavailable domain field rather than zero.

- [ ] **Step 4: Register the readers in `AnalyticsConfiguration`**

Add beans accepting the existing `ReadOnlyQueryExecutor`. Keep analytics capability conditional so deployments with analytics disabled do not fail startup. Reuse the existing read-only credential and never create a second datasource.

- [ ] **Step 5: Run focused and regression tests**

Run: `./mvnw.cmd -Dtest=JdbcAnalyticsReaderTest,AnalyticsAnomalyConfigurationTest test`

Expected: PASS; analytics-disabled context remains valid.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/smartpark/analytics/anomaly src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java src/test/java/com/example/smartpark/analytics/anomaly/JdbcAnalyticsReaderTest.java
git commit -m "feat: read anomaly aggregates from analytics views"
```

### Task 3: Add application aggregation service and REST contract

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyService.java`
- Create: `src/main/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyDtos.java`
- Modify: `src/main/java/com/example/smartpark/web/OperationsController.java`
- Create: `src/test/java/com/example/smartpark/analytics/anomaly/OperationsAnomalyServiceTest.java`
- Create: `src/test/java/com/example/smartpark/web/OperationsControllerAnomalyTest.java`

**Interfaces:**
- `OperationsAnomalyService.overview(OperationsAnomalyQuery)` returns `OperationsAnomalyDtos.Overview`.
- `OperationsAnomalyService.evidence(String buildingId, OperationsAnomalyQuery)` returns `OperationsAnomalyDtos.Evidence`.
- REST endpoints: `GET /api/operations/anomaly-overview` and `GET /api/operations/anomaly-evidence/{buildingId}`.

- [ ] **Step 1: Write failing aggregation tests**

Test alert/device/energy building IDs are unioned once, each breakdown is stably sorted, high-risk counts come from the risk enum, device `asOf` is preserved, and an unavailable energy reader produces `domainStatus.energy=UNAVAILABLE` without changing alert totals.

- [ ] **Step 2: Run focused service tests to verify failure**

Run: `./mvnw.cmd -Dtest=OperationsAnomalyServiceTest test`

Expected: FAIL because service and DTOs do not exist.

- [ ] **Step 3: Implement application-layer merge and redaction**

Call each reader independently, collect failures per domain, merge by `buildingId`, apply stable ordering (`primary count DESC`, then `buildingId ASC`), and cap recent references. Build DTOs from a whitelist; truncate/redact summaries before serialization. Do not emit zero for unavailable fields.

- [ ] **Step 4: Write controller contract tests**

Cover `200` success, empty data, `200` partial-domain failure, `400` invalid time/filter, `403` unauthorized role, and `503` only when no overview can be constructed. Assert no adapter stack trace or raw payload appears in JSON.

- [ ] **Step 5: Implement controller parameter parsing and status mapping**

Extend the existing `OperationsController`; parse ISO-8601 timestamps and `X-Demo-Role` using the project’s existing role/authorization convention. Return the normalized window and timezone in every success response.

- [ ] **Step 6: Run backend verification**

Run: `./mvnw.cmd -Dtest=OperationsAnomalyServiceTest,OperationsControllerAnomalyTest test`

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/smartpark/analytics/anomaly src/main/java/com/example/smartpark/web/OperationsController.java src/test/java/com/example/smartpark/analytics/anomaly src/test/java/com/example/smartpark/web/OperationsControllerAnomalyTest.java
git commit -m "feat: expose operations anomaly overview and evidence"
```

### Task 4: Build the frontend anomaly radar and API client

**Files:**
- Create: `ui/src/types/operationsAnomaly.ts`
- Create: `ui/src/services/operationsAnomalyApi.ts`
- Create: `ui/src/components/operations/AnomalyRadar.vue`
- Create: `ui/src/components/operations/AnomalyRadar.spec.ts`
- Modify: `ui/src/components/operations/OperationsBoard.vue`
- Modify: `ui/src/components/operations/OperationsBoard.spec.ts`

**Interfaces:**
- `getAnomalyOverview(role: DemoRole, filters?: AnomalyFilters): Promise<AnomalyOverview>`.
- `AnomalyRadar` emits `open-building(buildingId, filters)`, `open-analysis(question)`, and `open-trace(runId)`.
- `OperationsBoard` remains responsible for the existing controlled-question list and passes its `role`/`active` state to the radar.

- [ ] **Step 1: Write failing component/API tests**

Assert the radar renders four fact cards with explicit windows, displays partial-domain status instead of zero, emits a building event from the ranking, and does not render fabricated values when the API rejects.

- [ ] **Step 2: Run focused frontend tests to verify failure**

Run from `ui`: `npm run test:unit -- AnomalyRadar.spec.ts OperationsBoard.spec.ts`

Expected: FAIL because the new types, service, and component do not exist.

- [ ] **Step 3: Implement typed API client and loading/error states**

Send `X-Demo-Role`, encode all query parameters, parse the safe DTO, and surface `400/403/503` as typed errors. Keep request generation tokenized so switching views or roles cannot overwrite a newer response.

- [ ] **Step 4: Implement `AnomalyRadar.vue`**

Render summary cards, risk/category/status/device-type breakdowns, building ranking, domain status badges, loading state, empty state, partial-failure state, and retry action. Labels must distinguish “近 7 天告警” from “最近 1 天离线设备”. Do not add map coordinates or a composite score.

- [ ] **Step 5: Integrate into `OperationsBoard.vue`**

Mount the radar before the daily report, preserve the existing 14 controlled questions, forward `open-analysis`, and forward building/trace events to the workbench. Refresh on activation and role change; hide the radar with a clear unavailable state when analytics capability is disabled.

- [ ] **Step 6: Run frontend verification**

Run from `ui`: `npm run test:unit -- AnomalyRadar.spec.ts OperationsBoard.spec.ts`, then `npm run typecheck`.

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add ui/src/types/operationsAnomaly.ts ui/src/services/operationsAnomalyApi.ts ui/src/components/operations/AnomalyRadar.vue ui/src/components/operations/AnomalyRadar.spec.ts ui/src/components/operations/OperationsBoard.vue ui/src/components/operations/OperationsBoard.spec.ts
git commit -m "feat: add operations anomaly radar"
```

### Task 5: Add evidence-chain drawer and workbench navigation

**Files:**
- Create: `ui/src/components/operations/AnomalyEvidenceDrawer.vue`
- Create: `ui/src/components/operations/AnomalyEvidenceDrawer.spec.ts`
- Modify: `ui/src/components/operations/OperationsBoard.vue`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/OperationsWorkbench.spec.ts`

**Interfaces:**
- `getAnomalyEvidence(role: DemoRole, buildingId: string, filters?: AnomalyFilters): Promise<AnomalyEvidence>`.
- Drawer emits `close`, `open-analysis(question)`, and `open-trace(runId)`.
- Workbench handler `openTraceFromBoard(runId: string): void` calls the existing `trace.subscribe(runId)` and keeps the user in the operations view until the explicit trace action.

- [ ] **Step 1: Write failing drawer/workbench tests**

Cover drawer loading, empty, partial-domain, forbidden, and retry states; assert redacted references are rendered without raw payload fields; assert “打开执行轨迹” subscribes to the returned run ID and “进入分析” emits a controlled question.

- [ ] **Step 2: Run focused tests to verify failure**

Run from `ui`: `npm run test:unit -- AnomalyEvidenceDrawer.spec.ts OperationsWorkbench.spec.ts`

Expected: FAIL because the drawer and navigation event do not exist.

- [ ] **Step 3: Implement typed evidence API and drawer**

Open the drawer from a building row, fetch evidence with the current normalized filters, render separate alert/device/energy sections, show `UNAVAILABLE` per domain, and expose only stable IDs, timestamps, types, risk/status and safe summaries. Close/reset stale requests when the selected building changes.

- [ ] **Step 4: Wire trace and analysis navigation**

Add the `open-trace` event path from drawer → `OperationsBoard` → `OperationsWorkbench`; call `trace.subscribe(runId)` only for a non-empty server-provided ID. Reuse the existing controlled question routing for analysis and never synthesize a run ID.

- [ ] **Step 5: Run frontend verification**

Run from `ui`: `npm run test:unit -- AnomalyEvidenceDrawer.spec.ts OperationsWorkbench.spec.ts`, then `npm run typecheck`.

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add ui/src/components/operations/AnomalyEvidenceDrawer.vue ui/src/components/operations/AnomalyEvidenceDrawer.spec.ts ui/src/components/operations/OperationsBoard.vue ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts
git commit -m "feat: add anomaly evidence chain drilldown"
```

### Task 6: Documentation, full verification, and handoff

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Create: `docs/superpowers/verification/2026-09-03-operations-anomaly-radar.md`

- [ ] **Step 1: Document the capability and data caveats**

Add the two endpoints, role/read-only behavior, default windows, device `asOf` semantics, partial-domain status, and the fact that the radar is an application-layer projection rather than a cross-domain SQL view.

- [ ] **Step 2: Run the complete backend suite**

Run: `./mvnw.cmd test`

Expected: PASS with no new warnings beyond existing project warnings.

- [ ] **Step 3: Run the complete frontend suite and build**

Run from `ui`: `npm run test:unit`, `npm run typecheck`, `npm run build`.

Expected: all unit tests pass, typecheck passes, and production build completes. Record any pre-existing chunk-size warning without treating it as a feature failure.

- [ ] **Step 4: Perform a manual read-only smoke check**

With analytics enabled, open 运营看板, verify the four cards show server values and explicit windows, click a building, verify all four evidence sections handle data/partial failure, open an execution trace with a server-provided run ID, and enter one existing controlled analysis. Verify analytics-disabled deployments show an unavailable state rather than a blank or fabricated dashboard.

- [ ] **Step 5: Record verification evidence**

Write commands, pass counts, endpoint examples, and the manual smoke result to `docs/superpowers/verification/2026-09-03-operations-anomaly-radar.md`.

- [ ] **Step 6: Commit documentation and final verification**

```bash
git add README.md docs/architecture.md docs/superpowers/verification/2026-09-03-operations-anomaly-radar.md
git commit -m "docs: document operations anomaly radar"
```
