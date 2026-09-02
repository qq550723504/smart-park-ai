# Security Incident Center Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在已确认的脱敏 Mock 安全数据上实现可归并、可研判、可幂等转协同的安全事件研判中心。

**Architecture:** 扩展安全读取端口提供有限事件集合，由独立 `SecurityIncidentService` 按稳定规则生成不可变事件投影；Controller 负责角色和参数校验。研判动作写入有界的事件状态存储，转协同动作写入独立的协同转出存储，`CollaborationCenterService` 只把转出记录投影成工作项，不承担事件归并。

**Tech Stack:** Java 21, Spring Boot MVC, JUnit 5, Vue 3, TypeScript, Vitest, Element Plus, npm。

**Spec:** `docs/superpowers/specs/2026-09-02-security-incident-center-design.md`

## Global Constraints

- 只展示 `REDACTED:` 脱敏摘要，不接收或返回原始视频、图片、音频、身份信息或模型原文。
- 归并规则固定为同 `parkId + buildingId + eventType` 且相邻时间差不超过 15 分钟；输入乱序必须得到稳定结果。
- 安全事件列表和动作仅允许 `APPROVER`、`ADMIN`；`CUSTOMER_AGENT` 只能查看已转出的协同工作项。
- 研判页仅提供“标记已研判”和“转为协同工作项”；不直接关闭告警、控制设备、派单或发送通知。
- 不新增模型、事件总线或持久化数据库；进程内状态必须有界并明确会话级语义。
- 每个任务先写失败测试，再实现最小代码，完成后运行任务列出的聚焦测试并提交一次。

---

### Task 1: 扩展安全读取端口与 Mock 数据投影

**Files:**
- Modify: `src/main/java/com/example/smartpark/port/security/SecurityPort.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockSecurityAdapter.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockParkDataStore.java`
- Modify: `src/main/java/com/example/smartpark/port/alert/AlertPort.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockAlertAdapter.java`
- Create: `src/test/java/com/example/smartpark/adapter/mock/MockSecurityAdapterTest.java`
- Create: `src/test/java/com/example/smartpark/adapter/mock/MockAlertAdapterTest.java`

**Interfaces:**
- Produces `List<SecurityEvent> listEvents()` on `SecurityPort`; returned list is immutable and deterministically sorted by `occurredAt`, then `eventId`.
- `MockSecurityAdapter.listEvents()` delegates to a new package-visible `MockParkDataStore.listSecurityEvents()` and returns a copy.
- Produces `List<Alert> listActive()` on `AlertPort`; `MockAlertAdapter` delegates to a sorted immutable store projection so correlation never relies on hard-coded IDs.

- [ ] **Step 1: Write the failing test** — add tests asserting seeded `SEC-ACCESS-001` and `ALT-ACCESS-001` are returned, both lists are immutable, and reset does not leak mutable backing collections.
- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw.cmd -q -Dtest=MockSecurityAdapterTest,MockAlertAdapterTest test`

Expected: FAIL because `listEvents()`, `listActive()` and the store readers do not exist.

- [ ] **Step 3: Write minimal implementation** — add the port method, store reader, and adapter delegation; preserve `getEvent` behavior and do not expose the map.
- [ ] **Step 4: Run test to verify it passes**

Run: `./mvnw.cmd -q -Dtest=MockSecurityAdapterTest,MockAlertAdapterTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/port/security/SecurityPort.java src/main/java/com/example/smartpark/adapter/mock/MockSecurityAdapter.java src/main/java/com/example/smartpark/adapter/mock/MockParkDataStore.java src/test/java/com/example/smartpark/adapter/mock/MockSecurityAdapterTest.java
git commit -m "feat: expose safe security event collection"
```

### Task 2: 实现安全事件领域模型、归并与有界状态存储

**Files:**
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncident.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentQuery.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentPage.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentEvidence.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentStatus.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentTimelineEntry.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentStore.java`
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentService.java`
- Create: `src/main/java/com/example/smartpark/port/collaboration/SecurityIncidentHandoff.java`
- Create: `src/main/java/com/example/smartpark/port/collaboration/SecurityIncidentHandoffPort.java`
- Create: `src/test/java/com/example/smartpark/securityincident/SecurityIncidentServiceTest.java`
- Create: `src/test/java/com/example/smartpark/securityincident/SecurityIncidentStoreTest.java`

**Interfaces:**
- `SecurityIncidentService.list(SecurityIncidentQuery query): SecurityIncidentPage`.
- `SecurityIncidentService.get(String incidentId): SecurityIncident`.
- `SecurityIncidentService.review(String incidentId): SecurityIncident`.
- `SecurityIncidentService.handoff(String incidentId): SecurityIncident`.
- The service constructor consumes `SecurityPort`, `AlertPort`, `SecurityIncidentStore`, `SecurityIncidentHandoffPort`, and `Clock`; it never depends on a Mock adapter or collaboration implementation.
- `SecurityIncidentStore` exposes `get`, `save`, `findAll`, and `findByHandoff`; all returned models are immutable snapshots.
- `SecurityIncidentHandoffPort.createOrGet(SecurityIncident incident, Instant now): SecurityIncidentHandoff` is the only write boundary used by the service; `list(): List<SecurityIncidentHandoff>` supplies the collaboration projection.
- `SecurityIncident` exposes stable IDs, risk/status, counts, safe summary, evidence, timeline, recommendations, `reviewedAt`, and `handoffWorkItemId`.
- `SecurityIncidentQuery` accepts nullable `SecurityIncidentStatus status` and bounded `int limit`; `SecurityIncidentPage` contains immutable `items` and `total`.
- `SecurityIncidentEvidence` contains only `sourceId`, `occurredAt`, and validated `summary`; `SecurityIncidentTimelineEntry` contains only `sourceType`, `sourceId`, `occurredAt`, and a safe label.

- [ ] **Step 1: Write the failing tests** — cover same bucket within 15 minutes, 15-minute-plus split, different building/type split, shuffled input stability, risk escalation from an associated high-risk alert, empty input, `OPEN → REVIEWED`, `REVIEWED → HANDOFF`, repeated review/handoff idempotency, and store capacity eviction.
- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentServiceTest,SecurityIncidentStoreTest test`

Expected: FAIL because the domain types and service are absent.

- [ ] **Step 3: Write minimal implementation** — use `SecurityPort.listEvents()` and `AlertPort.listActive()`; correlate only alert evidence tokens matching `security-event:<eventId>`, group after sorting, generate an ID from bucket key plus first event ID, build only redacted evidence and fixed recommendations, and synchronize state transitions with stable handoff keys.
- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentServiceTest,SecurityIncidentStoreTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/securityincident src/test/java/com/example/smartpark/securityincident
git commit -m "feat: correlate safe security incidents"
```

### Task 3: 建立协同转出投影，不把归并逻辑塞进协同中心

**Files:**
- Create: `src/main/java/com/example/smartpark/collaborationcenter/SecurityIncidentHandoffStore.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationWorkItem.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterConfiguration.java`
- Create: `src/test/java/com/example/smartpark/collaborationcenter/SecurityIncidentHandoffStoreTest.java`
- Modify: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java`

**Interfaces:**
- `SecurityIncidentHandoffStore` implements `SecurityIncidentHandoffPort`; `createOrGet(SecurityIncident incident, Instant now): SecurityIncidentHandoff` is keyed by `incidentId`.
- `SecurityIncidentHandoffStore.list(): List<SecurityIncidentHandoff>` returns bounded immutable records.
- `CollaborationWorkItem.Source` gains `SECURITY_INCIDENT`; `CollaborationCenterService` receives the optional handoff store and projects handoffs as safe work items with detail path `/security/incidents/{incidentId}`.

- [ ] **Step 1: Write the failing tests** — assert repeated `createOrGet` returns the same work-item ID, high risk maps to `Priority.HIGH`, handoffs appear in collaboration listings, and existing workflow/ticket projections remain unchanged.
- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentHandoffStoreTest,CollaborationCenterServiceTest test`

Expected: FAIL because the new source and projection are absent.

- [ ] **Step 3: Write minimal implementation** — add a synchronized bounded store, inject it through configuration, append its projections in `list`, and keep event correlation entirely in `SecurityIncidentService`.
- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentHandoffStoreTest,CollaborationCenterServiceTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/collaborationcenter src/test/java/com/example/smartpark/collaborationcenter
git commit -m "feat: project security handoffs in collaboration center"
```

### Task 4: 接入 Spring 配置和安全事件 REST API

**Files:**
- Create: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentConfiguration.java`
- Create: `src/main/java/com/example/smartpark/web/SecurityIncidentDtos.java`
- Create: `src/main/java/com/example/smartpark/web/SecurityIncidentController.java`
- Create: `src/test/java/com/example/smartpark/web/SecurityIncidentControllerTest.java`

**Interfaces:**
- `GET /api/security/incidents?status=&limit=` returns `{items,total}` summary projections.
- `GET /api/security/incidents/{incidentId}` returns the detailed safe projection.
- `POST /api/security/incidents/{incidentId}/review` returns the updated projection.
- `POST /api/security/incidents/{incidentId}/handoff` returns the updated projection including `handoffWorkItemId`.
- Controller uses `DemoRole.require(role, DemoRole.APPROVER, DemoRole.ADMIN)` and existing exception handling; `SecurityIncidentConfiguration` wires `SecurityPort`, `AlertPort`, store, and service only when required ports exist.

- [ ] **Step 1: Write the failing tests** — use `@WebMvcTest`/existing MVC test style to cover allowed roles, denied roles, unknown incident, invalid status/limit, redacted DTO fields, review idempotency, and handoff idempotency.
- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentControllerTest test`

Expected: FAIL because the controller and DTOs are absent.

- [ ] **Step 3: Write minimal implementation** — validate query bounds, map only whitelisted values, return safe resource errors, and call service actions without accepting client-supplied status or text.
- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw.cmd -q -Dtest=SecurityIncidentControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/securityincident src/main/java/com/example/smartpark/web/SecurityIncidentDtos.java src/main/java/com/example/smartpark/web/SecurityIncidentController.java src/test/java/com/example/smartpark/web/SecurityIncidentControllerTest.java
git commit -m "feat: expose security incident review api"
```

### Task 5: 添加前端类型、API 客户端和研判页面

**Files:**
- Create: `ui/src/types/securityIncident.ts`
- Create: `ui/src/services/securityIncidentApi.ts`
- Create: `ui/src/components/security/SecurityIncidentCenter.vue`
- Create: `ui/src/components/security/security-incident-center.css`
- Create: `ui/src/components/security/SecurityIncidentCenter.spec.ts`

**Interfaces:**
- `listSecurityIncidents(role, query): Promise<SecurityIncidentPage>`.
- `getSecurityIncident(role, incidentId): Promise<SecurityIncident>`.
- `reviewSecurityIncident(role, incidentId): Promise<SecurityIncident>`.
- `handoffSecurityIncident(role, incidentId): Promise<SecurityIncident>`.
- Vue props: `role: DemoRole`; emits `open-collaboration` with `{ incidentId, workItemId }` after successful handoff.

- [ ] **Step 1: Write the failing tests** — mount the page with ADMIN/APPROVER/CUSTOMER_AGENT, assert list/detail rendering, loading/error/empty states, role-hidden actions, review status update, handoff button idempotency, and stale-response protection when selecting another incident.
- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix ui run test:unit -- SecurityIncidentCenter.spec.ts`

Expected: FAIL because the component and API module are absent.

- [ ] **Step 3: Write minimal implementation** — use existing fetch/error conventions, keep all risk/grouping decisions server-side, render redacted summaries only, and guard async updates with a request generation counter.
- [ ] **Step 4: Run tests to verify they pass**

Run: `npm --prefix ui run test:unit -- SecurityIncidentCenter.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/types/securityIncident.ts ui/src/services/securityIncidentApi.ts ui/src/components/security
git commit -m "feat: add security incident review view"
```

### Task 6: 将页面接入运营工作台和协同跳转

**Files:**
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/OperationsWorkbench.spec.ts`
- Modify: `ui/src/components/operations-workbench.css`

**Interfaces:**
- Add nav item `security-incidents` visible only for `APPROVER`/`ADMIN`.
- Render `SecurityIncidentCenter` for that view and handle `open-collaboration` by selecting the existing collaboration-center view with a refresh token or selected work-item hint.

- [ ] **Step 1: Write the failing tests** — assert nav visibility by role, page rendering, handoff event switching to collaboration center, and role changes do not leave the security page visible to `CUSTOMER_AGENT`.
- [ ] **Step 2: Run tests to verify they fail**

Run: `npm --prefix ui run test:unit -- OperationsWorkbench.spec.ts`

Expected: FAIL because the nav entry and view are absent.

- [ ] **Step 3: Write minimal implementation** — follow existing workbench component registration and view-switch conventions; do not duplicate collaboration API calls in the security page.
- [ ] **Step 4: Run tests to verify they pass**

Run: `npm --prefix ui run test:unit -- OperationsWorkbench.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/OperationsWorkbench.vue ui/src/components/OperationsWorkbench.spec.ts ui/src/components/operations-workbench.css
git commit -m "feat: integrate security incidents into operations workbench"
```

### Task 7: 全量验证、安全回归与文档同步

**Files:**
- Modify: `docs/superpowers/specs/2026-09-02-security-incident-center-design.md` only if implementation discoveries require a clarified contract.
- Modify: relevant `docs/` capability or architecture index identified by existing documentation conventions.
- Create/Modify: `src/test/java/com/example/smartpark/architecture/SecurityIncidentArchitectureTest.java` if no existing architecture-test location covers dependency boundaries.

**Interfaces:**
- No new public interface; this task verifies the contracts from Tasks 1–6 and records any final implementation notes.

- [ ] **Step 1: Write architecture regression tests** — assert security incident classes do not depend on Mock adapters, controllers, frontend packages, or device-control ports; assert collaboration projection is the only cross-package handoff dependency.
- [ ] **Step 2: Run focused backend and frontend suites**

Run: `./mvnw.cmd -q -Dtest=MockSecurityAdapterTest,SecurityIncidentServiceTest,SecurityIncidentStoreTest,SecurityIncidentHandoffStoreTest,CollaborationCenterServiceTest,SecurityIncidentControllerTest test`

Run: `npm --prefix ui run test:unit -- SecurityIncidentCenter.spec.ts OperationsWorkbench.spec.ts`

Expected: PASS with no security DTO leakage.

- [ ] **Step 3: Run full verification**

Run: `./mvnw.cmd -q test`

Run: `npm --prefix ui run typecheck`

Run: `npm --prefix ui run build`

Expected: all commands exit 0; existing warnings may be reported but must not be converted into feature behavior.

- [ ] **Step 4: Review the diff** — confirm no raw evidence, model prompt, arbitrary status mutation, unbounded store, or duplicated collaboration write logic was introduced.
- [ ] **Step 5: Commit documentation and verification updates**

```bash
git add docs src/test/java/com/example/smartpark/architecture
git commit -m "test: verify security incident center boundaries"
```
