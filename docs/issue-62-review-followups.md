# Issue #62 评审跟进清单（Review Follow-ups）

> 交接文档。用于在新会话中继续处理 `codex/issue-62-security-event-model` 的代码评审批注。
> 本文自包含：包含上下文、问题、触发路径、修复建议与验收标准，无需回看原评审对话。

## 0. 上下文

- 仓库：`E:/code/smart-park-ai`
- 分支：`codex/issue-62-security-event-model`
- 评审基线：`origin/main`，merge-base `902286df043fd0460dc4b4dffcbfe8183cbf2f60`
- 评审时 HEAD：`89e1c200f8b587db52fee683d5d9ce04f9743496`
- 主题：安防事件模型标准化 + 「模型支持 / 已接入来源」分离 + 可审计研判（disposition）
- 先读这两份，理解设计意图与已声明的偏离：
  - `docs/issue-62-capability-map.md`
  - `docs/issue-62-browser-verification.md`
- 设计计划：`docs/superpowers/plans/2026-09-14-security-event-model-and-disposition.md`

### 已知的环境噪声（不要追）

- `./mvnw test` 全量会有 4 个与本分支无关的失败：
  - `AnalyticsSchemaMigrationTest`、`OperationsAnalysisGraphTest`、`ReadOnlyQueryExecutorTest`：需要 Docker（Testcontainers），本机不可用。
  - `OperationsAnalysisServiceTest.boundsAdmissionWhenAWorkerIgnoresThePreviousTimeout`：偶发超时，单独重跑通过（28/28）。
- `ui` 侧 `npx vue-tsc -b` 与相关 vitest spec 均通过。

### 建议处理顺序

`#1 → #2 → #3`，每条改完跑聚焦测试并单独提交。`#4/#5/#6` 为可选/讨论项，不要与 `#1` 混在一个提交里。

---

## #1 [中] 从 handoff 投影恢复时会丢失 disposition（审计一致性）

### 现象

一个曾被人工判为 `FALSE_POSITIVE` 的事件，在事件存储淘汰后可能以
`status=HANDOFF`、`disposition=UNREVIEWED` 返回；前端会同时显示「已转协同」和「未复核」，
误报计数漏计。与「已登记复核记录可统计」的承诺冲突。

### 触发路径

1. 事件存储 `SecurityIncidentStore` 容量 100，handoff 存储 `SecurityIncidentHandoffStore` 容量 100（见 `collaborationcenter/CollaborationCenterConfiguration.java:46`）。
2. 当条目从事件存储被淘汰、但对应 handoff 仍保留时，`SecurityIncidentService.correlate/restoreStates` 会：
   - `SecurityIncidentService.java:318` `restoreState`：`if (candidates.isEmpty()) return fresh;` → 返回 `disposition=UNREVIEWED` 的 fresh。
   - `SecurityIncidentService.java:233` 调用 `restoreHandoffProjection`。
   - `SecurityIncidentService.java:270-279` `restoreHandoffProjection`：只回填 `status/reviewedAt/handoffWorkItemId`，disposition 仍取 `fresh.disposition()`（`UNREVIEWED`）。
3. 根因：`SecurityIncidentHandoff`（`src/main/java/com/example/smartpark/port/collaboration/SecurityIncidentHandoff.java:10`）不携带 disposition，恢复时无从还原。

### 现成测试为何没兜住

`src/test/java/com/example/smartpark/securityincident/SecurityIncidentServiceTest.java:416`
`preservesReviewTimestampWhenRestoringAnEvictedHandoff` 正好走了这条路径，但只断言了
`status` 和 `reviewedAt`，未断言 `disposition`。

### 修复建议

- 首选：给 `SecurityIncidentHandoff` 增加 disposition 投影（至少 `disposition`、`dispositionSource`、`dispositionDecidedAt`），在 `SecurityIncidentHandoffStore` 的 `createOrGet`/`refresh` 中随事件一起投影，`restoreHandoffProjection` 恢复时回填。
- 备选：已 handoff 的事件条目不在事件存储中淘汰；或对恢复路径保留已有 disposition 而不降级。
- 不要用「默认 `CONFIRMED_INCIDENT`」兜底——那会凭空制造结论，违背本分支的 fail-closed 原则。

### 验收

- 在 `preservesReviewTimestampWhenRestoringAnEvictedHandoff` 增加断言：恢复后 `disposition == FALSE_POSITIVE` 且 `dispositionRecord().source() == HUMAN_REVIEW`。
- 新增用例：`FALSE_POSITIVE` + handoff + 诱使事件存储淘汰后再 `get()`，disposition 仍为 `FALSE_POSITIVE`。

---

## #2 [低] `SecurityIncidentWebConfiguration` 注册条件脆弱 + 工具方法重复

### 位置

`src/main/java/com/example/smartpark/web/SecurityIncidentWebConfiguration.java:31-41`

```java
boolean runtimeDependenciesPresent = hasBean(registry, SecurityEventReader.class)
        && hasBean(registry, AlertPort.class)
        && hasBean(registry, SecurityIncidentHandoffPort.class);
if ((!hasBean(registry, SecurityIncidentService.class)
        && (!hasBean(registry, SecurityIncidentConfiguration.class)
        || !runtimeDependenciesPresent))
        || registry.containsBeanDefinition("securityIncidentController")) return;
String serviceBeanName = beanNameFor(registry, SecurityIncidentService.class);
... new RuntimeBeanReference(serviceBeanName) ...
```

### 问题

- 当前 order 约定（service registrar `HIGHEST_PRECEDENCE+1` 先于 controller registrar `+2`）下，这个双重否定条件实际退化为「service 存在才注册」，`runtimeDependenciesPresent`/config 分支是死逻辑，可读性差、易误改。
- 若顺序被改动或 service registrar 未执行，会进入「service 不存在但仍继续」的分支，用 `null` beanName 构造 `RuntimeBeanReference`，在 refresh 期抛出难懂的断言错误。
- `beanNameFor`/`resolveType`（含新增的 `getType(name, false)` 回退）在 `SecurityIncidentConfiguration` 与 `SecurityIncidentWebConfiguration` 中逐字重复。

### 修复建议

- 条件简化为 `if (registry.containsBeanDefinition("securityIncidentController") || !hasBean(registry, SecurityIncidentService.class)) return;`，并对 `serviceBeanName == null` 做一次防御（但正常不应发生）。
- 把 `beanNameFor`/`resolveType` 抽到共享包私有工具（例如 `web` 与 `securityincident` 都能访问的一个 `@Internal` helper），保留注释说明 `@Bean` 方法定义需 `getType` 回退。
- 保持 `SmartParkApplicationTest.registersTheSecurityIncidentRuntimeWhenCapabilityPortsArePresent` 通过。

### 验收

- 现有测试全部通过，特别是 `SmartParkApplicationTest` 与 `SecurityIncidentControllerTest`。
- 新条件语义与修改前一致（可用一个缺失 `SecurityIncidentService` 的上下文测试确认控制器不被注册且不抛异常）。

---

## #3 [低] `SecurityEvent` 校验错误信息用错字段名

### 位置

`src/main/java/com/example/smartpark/model/security/SecurityEvent.java:34`

```java
rawEventType = requireText(rawEventType, "eventType");
```

字段名应为 `"rawEventType"`。`src/test/java/com/example/smartpark/model/security/SecurityEventTest.java:14`
`rejectsBlankBoundaryText` 目前沿用了错误名，需同步更新（把断言里对应字段改为 `rawEventType`）。

### 验收

- 空 `rawEventType` 抛出的异常信息包含 `rawEventType`。
- `SecurityEventTest` 更新并通过。

---

## #4 [低/讨论] `dispositionEnabled()` 语义混用（可选）

- 位置：`src/main/java/com/example/smartpark/port/security/SecurityEventCapabilityRegistry.java:49`
- 现状：只要存在任一 production adapter 且声明了类型，就置 `dispositionEnabled=true`。但「有生产事件源」不等于「有 disposition 数据源」。
- 文案「存在生产数据源与已登记复核记录」（`GovernanceCenter.vue` / `OperationsBoard.vue`）因此轻微过度承诺。
- 建议：绑定到显式 disposition feed/开关，或把文案限定为「存在生产数据源，误报统计可评估」。属设计讨论，不阻塞合入。

---

## #5 [信息] 需要点明的行为变更（非缺陷）

- 关联归并由原始类型改为标准类型分桶（`SecurityIncidentService.java` 的 `bucketKey` 用 `eventType().name()`），原先不同的原始类型（如 `UNAUTHORIZED_ACCESS_ATTEMPT` 与 `ACCESS_DENIED`）现在会并入同一 incident。属预期，但对外可见，建议写进 PR 描述。
- `src/main/java/com/example/smartpark/workflow/AlertWorkflowNodes.java:219` 的
  `"SECURITY_REDACTED_REVIEW: " + event.eventType()` 由原始类型变为标准类型名。
  `SecurityWorkflowTest` 只看前缀未感知。如下游 AI 诊断文案依赖原始类型需确认。

---

## #6 [信息] 一致性与收尾（可选）

- `src/main/java/com/example/smartpark/securityincident/SecurityIncident.java:66` 允许 `REVIEWED/HANDOFF` 搭配 `UNREVIEWED`（legacy 兼容，`SecurityIncidentTest.java:20` 明确覆盖）。这是 #1 展示组合的成因之一；#1 修好后可考虑收紧为「非 OPEN 必须有已决 disposition」。
- 同一产品中 `SecurityIncidentCenter` 显示「1 误报」，而治理/看板显示「误报数不可统计」，两处「误报」含义不同（人工结论计数 vs 生产统计能力）。建议文案区分「复核结论」与「误报统计」。

---

## 验证命令

```bash
# 后端聚焦
./mvnw -o test -Dtest='SecurityEventCapabilityRegistryTest,SecurityDispositionRecordTest,SecurityEventTest,SecurityEventTypeTest,SecurityIncidentServiceTest,SecurityIncidentTest,SecurityCapabilityControllerTest,SecurityIncidentControllerTest,OperationsCapabilitiesServiceTest,SmartParkApplicationTest' -DfailIfNoTests=false

# 前端
cd ui && npx vue-tsc -b && npx vitest run
```

## 完成定义（DoD）

- [x] #1 修复 + 回归测试通过（含 disposition 保持断言）。
- [x] #2 条件简化且行为不变，`SmartParkApplicationTest` 通过；工具方法去重。
- [x] #3 字段名修正 + `SecurityEventTest` 更新。
- [x] #4 已在 `da75aa3` 绑定到显式 `dispositionFeed`，#5 为行为说明已写入 PR。
- [x] 聚焦测试与 `ui` 检查全绿（Docker 相关用例除外）。
- [x] 每个问题独立提交，commit message 用 conventional commits（`fix(security): ...` / `refactor(web): ...` / `test(...)`）。

---

## 附：PR #82 第二轮评审批注处理

评审机器人对 `89e1c20` / `017c7d5` 提出的 5 条 inline 意见及处理：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 1 | `port/security/SecuritySourceAdapter` | P1 | `96493d4`：`SecurityIncidentService` 合并 `SecurityEventReader` 与所有已注册 `SecuritySourceAdapter` 的事件并按记录去重，adapter 真正进入关联路径 |
| 2 | `securityincident/SecurityIncidentService.build()` | P1 | `ffb295c`：继承最近一次已决事件 disposition，`REVIEWED` + `decidedAt`；全未决保持 `OPEN` |
| 3 | `securityincident/SecurityIncidentService` handoff 恢复 | P1 | 已在 `3977e1e` 修复 |
| 4 | `port/security/SecurityEventCapabilityRegistry.dispositionEnabled()` | P2 | `da75aa3`：Require 显式 `dispositionFeed` + 生产源，并收敛前后端文案 |
| 5 | `ui/.../SecurityIncidentCenter.vue` toast | P2 | `6e2916f`：改用接口返回的已持久化 disposition |

第三轮（对 `b209ea0`）补 1 条 P1：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 6 | `securityincident/SecurityIncidentService.restoreState()` | P1 | `2` 号修复未覆盖 restore 路径：存量的 `OPEN`/`UNREVIEWED` 候选会覆盖新一轮已带 disposition 的 fresh。现 `effectiveDisposition()` 将 fresh 记录纳入优先级，并保留人工复核的 first-wins |

第四轮（对 `c6a7709`）补 1 条 P1：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 7 | `securityincident/SecurityIncidentService.restoreHandoffProjection()` | P1 | handoff 投影无条件用旧记录覆盖 fresh 的新判定，且 `refresh()` 会把旧值写回。现抽出 `reconcileDisposition()` 供 restore 与 handoff 投影共用，人工 handoff 记录的 first-wins 保持不变 |

第五轮（对 `79c2fe1`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 8 | `securityincident/SecurityIncidentService.correlate()` | P1 | 跨读取路径按 `(eventId, parkId, buildingId)` 逻辑身份去重；同一事件不同表示时优先已决 disposition，否则取 `receivedAt` 最新者 |
| 9 | `web/SecurityIncidentController.review()` | P1 | 新增 `SecurityIncidentService.ReviewOutcome`/`applyReview()`，审计 outcome 记为 `SUCCESS:<disposition>` 或 `NO_CHANGE:<persisted disposition>`，可区分幂等 no-op 并重建已持久化结论 |
| 10 | `collaborationcenter/SecurityIncidentHandoffStore` | P2 | `projectedFieldsChanged()` 纳入 `dispositionRecord`，仅 disposition 变化时也推进 `updatedAt` |

第六轮（对 `f733805`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 11 | `securityincident/SecurityIncidentService.mergeEvent()` | P1 | 身份键改为 source-aware（含 `SecuritySourceRef`）；无源的 legacy 表示可别名具体源的同一事件，避免不同 adapter 复用同一 source-local id 时丢事件 |
| 12 | `securityincident/SecurityIncidentConfiguration` | P2 | 仅有 `SecuritySourceAdapter`（无 legacy reader）时也注册 service/controller；未配置 reader 时安装 `EmptySecurityEventReader` |

第七轮（对 `c8fa834`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 13 | `securityincident/SecurityIncidentService.overlaps()` | P1 | 去重时的 source-aware 键此前未进入持久化投影：`build()` 只投影裸 `eventId`，`overlaps()`/`matchesCorrelation()` 便按裸 id 匹配，导致 A 源的已复核/已交接结论被恢复到复用同一 id 的 B 源。现引入一等模型 `SecurityEventIdentity`（`matches`/`material`），`SecurityIncident` 与 `SecurityIncidentHandoff` 各自显式携带 `eventIdentities` 投影；去重、`incidentId` 派生、`overlaps()`、handoff 关联全部走同一身份类型，`UNKNOWN` 源作为显式 legacy 别名保留 |
| 14 | `securityincident/SecurityIncidentService.authoritativeEvent()` | P2 | adapter 在未推进 `receivedAt` 的情况下丰富 legacy 事件时时间戳相同，原实现恒取左侧（reader 的 legacy 副本）。现增加显式 tie-breaker，优先具体源（非 `UNKNOWN`）的丰富表示 |
| 15 | `adapter/mock/MockSecurityAdapter` | P2 | 默认部署下 `MockParkDataStore` 通过兼容构造器播种事件，source 为 `UNKNOWN`，与适配器声明的 `ACCESS_CONTROL/mock-access-control-feed` 不符。现 `getEvent()`/`listEvents()` 将事件映射为适配器声明的源，能力上报、事件证据与去重身份一致 |

每个修复对应独立提交：`6ffb2ab`（#15）、`831b7b9`（#14）、`58ccf6d`（#13）。

