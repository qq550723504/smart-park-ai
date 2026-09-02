# 协同中心 SLA 会话趋势 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 在协同中心增加真实的会话级 SLA 状态趋势，服务端按完整队列采样，前端用 ECharts 展示趋势，并保持现有只读权限和队列行为。

**Architecture:** 在应用层增加线程安全、有界的内存 `CollaborationSlaSnapshotStore`，由协同中心服务在完整未筛选队列生成后按 30 秒窗口采样。新增受角色保护的聚合趋势接口；前端通过独立 API 请求获取趋势并复用现有 ECharts 深色主题，趋势失败不影响队列读取。

**Tech Stack:** Java 17、Spring Boot MVC、JUnit 5、AssertJ、Mockito、Vue 3、TypeScript、Vitest、Vue Test Utils、ECharts 6。

**Spec:** `docs/superpowers/specs/2026-09-02-collaboration-center-sla-trend-design.md`

## Global Constraints

- 仅提供会话级内存历史；服务重启后快照清空，不新增数据库或 analytics 数据库依赖。
- 快照只包含采样时间和 SLA 状态数量，不暴露工作项详情。
- 采样基于完整未筛选队列，在 source/status/sort/limit 过滤前执行。
- 采样间隔为 30 秒，最多保留 120 点；趋势接口 `limit` 默认为 60，允许 1–120。
- 趋势接口和前端请求只允许 `CUSTOMER_AGENT`、`ADMIN`；普通查看者不发起趋势请求。
- 趋势读取失败必须保持队列可用；不得用默认值或当前状态伪造历史点。
- 保留现有协同中心筛选、排序、自动刷新、详情抽屉、原场景跳转和读权限边界。

---

### Task 1: 建立 SLA 快照模型与内存仓

**Files:**
- Create: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshot.java`
- Create: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshotStore.java`
- Test: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshotStoreTest.java`

**Interfaces:**
- `CollaborationSlaSnapshot` 为 record：`Instant capturedAt, int total, int overdue, int dueSoon, int onTrack, int completed, int notApplicable`。
- `CollaborationSlaSnapshotStore.recordIfDue(Instant capturedAt, List<CollaborationWorkItem> items)`：30 秒内不追加，返回是否追加。
- `CollaborationSlaSnapshotStore.list(int limit)`：校验 1–120，返回按时间升序排列的不可变最近点。

- [ ] **Step 1: Write the failing tests**

在 `CollaborationSlaSnapshotStoreTest` 覆盖：五种 SLA 状态计数；同一时间窗口不重复、超过 30 秒追加；追加 121 个点后只保留 120 个且淘汰最旧点；`list` 按时间升序返回并拒绝 0、121；返回列表和快照不可修改。

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `./mvnw.cmd -q -Dtest=CollaborationSlaSnapshotStoreTest test`

Expected: FAIL because the snapshot record and store do not exist.

- [ ] **Step 3: Implement the minimal model and store**

使用 `ArrayDeque` 保存快照并用 `synchronized` 保护追加、读取和容量淘汰。统计时按 `CollaborationWorkItem.slaState()` 计数；未知状态不能绕过总数，统一落入 `notApplicable`。对 `capturedAt`、items 和返回列表使用不可变副本，不暴露内部集合。

- [ ] **Step 4: Run the focused test to verify it passes**

Run: `./mvnw.cmd -q -Dtest=CollaborationSlaSnapshotStoreTest test`

Expected: PASS with all store tests green.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshot.java src/main/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshotStore.java src/test/java/com/example/smartpark/collaborationcenter/CollaborationSlaSnapshotStoreTest.java
git commit -m "feat: add collaboration SLA snapshot store"
```

### Task 2: 在完整队列采样并暴露趋势接口

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/web/CollaborationCenterController.java`
- Modify: `src/main/java/com/example/smartpark/web/CollaborationCenterDtos.java`
- Test: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java`
- Test: `src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java`

**Interfaces:**
- `CollaborationCenterService` 增加构造注入 `CollaborationSlaSnapshotStore`，保留既有构造器并为测试提供默认内存仓。
- `CollaborationCenterService.listTrend(int limit)` 返回 `snapshotStore.list(limit)`。
- `CollaborationCenterController` 新增 `GET /api/collaboration/sla-trend`，使用 `@RequestParam(defaultValue = "60") int limit` 和现有 `DemoRole.require`。
- `CollaborationCenterDtos.SlaTrendResponse.from(CollaborationSlaSnapshot)` 输出 `capturedAt,total,overdue,dueSoon,onTrack,completed,notApplicable`。

- [ ] **Step 1: Write the failing service and controller tests**

服务测试准备一个包含 source/status 筛选条件的查询，断言快照总数仍来自完整队列；控制器测试断言默认 limit、`limit=120`、`limit=0`/`121` 的 400、普通查看者 403，以及聚合 JSON 字段。

- [ ] **Step 2: Run the focused Java tests to verify they fail**

Run: `./mvnw.cmd -q -Dtest=CollaborationCenterServiceTest,CollaborationCenterControllerTest test`

Expected: FAIL because service has no snapshot dependency and the trend route/DTO do not exist.

- [ ] **Step 3: Implement sampling and route**

将现有 `list` 重构为先构造完整 work-item 列表，再调用 `snapshotStore.recordIfDue(clock.instant(), allItems)`，之后继续执行 query 过滤、排序和 limit。趋势 route 只读快照仓，不触发新的工作项详情查询；非法 limit 用现有异常处理返回 400。

- [ ] **Step 4: Run focused Java tests**

Run: `./mvnw.cmd -q -Dtest=CollaborationSlaSnapshotStoreTest,CollaborationCenterServiceTest,CollaborationCenterControllerTest test`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterConfiguration.java src/main/java/com/example/smartpark/web/CollaborationCenterController.java src/main/java/com/example/smartpark/web/CollaborationCenterDtos.java src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java
git commit -m "feat: expose collaboration SLA trend endpoint"
```

### Task 3: 增加趋势 API 客户端与 ECharts 组件

**Files:**
- Modify: `ui/src/types/collaborationCenter.ts`
- Modify: `ui/src/services/workflowApi.ts`
- Create: `ui/src/components/collaboration/CollaborationSlaTrendChart.vue`
- Create: `ui/src/components/collaboration/CollaborationSlaTrendChart.spec.ts`

**Interfaces:**
- `CollaborationSlaSnapshot` 前端类型与后端字段一一对应。
- `listCollaborationSlaTrend(role, limit = 60)` 请求 `/api/collaboration/sla-trend?limit=60` 并附加 `X-Demo-Role`。
- `CollaborationSlaTrendChart` props：`snapshots: CollaborationSlaSnapshot[]`，空数组不创建伪造数据。
- 导出纯函数 `buildSlaTrendOption(snapshots)`，生成时间轴和 `overdue`、`dueSoon`、`onTrack` 三条 line series。

- [ ] **Step 1: Write the failing tests**

API 测试断言 limit query 和角色 header；组件测试断言快照映射为三条线、时间轴使用 `capturedAt`、空数组返回空态且 ECharts 不接收默认点、ResizeObserver 缺失时不抛异常。

- [ ] **Step 2: Run focused frontend tests to verify they fail**

Run: `npm run test:unit -- src/components/collaboration/CollaborationSlaTrendChart.spec.ts src/services/workflowApi.spec.ts`

Expected: FAIL because the type, API function, component and option builder do not exist.

- [ ] **Step 3: Implement the API and chart**

复用 `AnalyticsChart.vue` 的 ECharts 初始化、ResizeObserver 生命周期和 `withDarkTheme`；图表配置使用 `xAxis.type = 'time'`，每条 series 的 data 为 `[capturedAt, count]`，不在组件内生成数据。将容器高度和现有协同中心风格写入协同 CSS 或组件 scoped CSS。

- [ ] **Step 4: Run focused frontend tests**

Run: `npm run test:unit -- src/components/collaboration/CollaborationSlaTrendChart.spec.ts src/services/workflowApi.spec.ts`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add ui/src/types/collaborationCenter.ts ui/src/services/workflowApi.ts ui/src/components/collaboration/CollaborationSlaTrendChart.vue ui/src/components/collaboration/CollaborationSlaTrendChart.spec.ts
git commit -m "feat: add collaboration SLA trend chart"
```

### Task 4: 集成协同中心的趋势状态和交互

**Files:**
- Modify: `ui/src/components/collaboration/CollaborationCenter.vue`
- Modify: `ui/src/components/collaboration/CollaborationCenter.spec.ts`
- Modify: `ui/src/components/collaboration/collaboration-center.css`

**Interfaces:**
- 页面维护 `trendSnapshots`, `trendLoading`, `trendFailed` 三个独立状态。
- 初次队列成功后读取趋势；后台刷新成功后替换趋势，趋势失败不清空可用队列。
- `canRead=false`、`active=false`、队列初次读取失败时清空趋势并不发请求。

- [ ] **Step 1: Write the failing integration tests**

在现有协同中心测试增加：管理员看到“本次会话 SLA 趋势”和采样点数量；队列响应后调用趋势 API；趋势空数组显示“正在采样”；趋势接口失败显示独立错误且仍显示工作项；角色改为 VIEWER 或 active=false 后不再请求趋势；后台刷新保留旧趋势直到新趋势成功。

- [ ] **Step 2: Run the focused test to verify it fails**

Run: `npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`

Expected: FAIL because the page has no trend state, API call or chart panel.

- [ ] **Step 3: Implement the integration**

将队列请求和趋势请求拆成独立函数，使用现有 `requestGeneration` 防止过期队列覆盖；趋势请求使用同一 generation，但其失败只设置 `trendFailed`。模板在可读角色下增加趋势 panel，展示 `CollaborationSlaTrendChart`、采样点数、空态和错误态；保持原有 SLA 总览在后台刷新时可见。

- [ ] **Step 4: Run the focused integration test**

Run: `npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`

Expected: PASS with all existing and new collaboration tests green.

- [ ] **Step 5: Commit**

```bash
git add ui/src/components/collaboration/CollaborationCenter.vue ui/src/components/collaboration/CollaborationCenter.spec.ts ui/src/components/collaboration/collaboration-center.css
git commit -m "feat: integrate collaboration SLA trend"
```

### Task 5: 文档与全量验证

**Files:**
- Modify: `README.md`
- Modify: `docs/customer-capabilities.md`

- [ ] **Step 1: Document session-only semantics**

在协同中心能力说明中写明趋势来自 30 秒会话采样、最多 120 点、服务重启清空、不是生产 SLA 历史报表；保留“生产化需要持久化快照”的边界。

- [ ] **Step 2: Run full verification**

Run from `ui`: `npm run typecheck`, `npm run test:unit`, `npm run build`。

Run from repository root: `./mvnw.cmd -q test`。

Expected: all commands exit 0. Build may retain the existing large-chunk warning, which must be reported rather than treated as a test failure.

- [ ] **Step 3: Inspect the final diff**

Run: `git diff main...HEAD --check; git status --short; git log --oneline -8`

Confirm only snapshot/trend implementation, tests, docs, and this plan are present; no API write operation, credentials, raw diagnostic fields, or unrelated refactor was introduced.

- [ ] **Step 4: Commit documentation**

```bash
git add README.md docs/customer-capabilities.md
git commit -m "docs: describe collaboration SLA session trend"
```
