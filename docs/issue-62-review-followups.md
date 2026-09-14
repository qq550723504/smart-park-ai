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


第八轮（对 `bf1c13f`）补 4 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 16 | `securityincident/SecurityIncidentService.authoritativeEvent()` | P1 | 两条 ingestion 路径都给出已决记录时，原实现只看 `receivedAt`，可能丢掉 `decidedAt` 更晚的修正。现复用 `reconcileDisposition()`：存储的 `HUMAN_REVIEW` 仍权威，否则最新 `decidedAt` 胜出，仅在都未决时才回退 `fresherEvent()` |
| 17 | `securityincident/SecurityIncidentService.restoreStates()` | P1 | `overlaps()` 的 legacy 无源别名此前是无限制通配符，会把同一份存储状态/状态位/handoff 恢复到多个具体源。现用 `sharesExactIdentity()` 区分“身份相等”与“仅别名”，仅别名匹配由单条 fresh 认领（`claimedAliasOnlyIncidentIds` / `claimedAliasOnlyHandoffWorkItemIds`），精确匹配仍可跨 correlation resplit 传播 |
| 18 | `securityincident/SecurityIncidentService.alertsByReference()` + `orchestration/OrchestrationConfiguration` + `workflow/AlertWorkflowNodes` | P1 | 告警证据 token 只带裸 event id，无法区分复用同一 id 的源，A 源告警会抬高 B 源风险。现 `SecurityEventIdentity` 拥有 source-qualified token（`reference()`/`legacyReference()`/`eventIdOfReference()`），告警按 `AlertReferenceKey(reference, parkId, buildingId)` 关联并兼容 legacy 别名；时间线投影 `reference` |
| 19 | `ui/src/components/security/SecurityIncidentCenter.vue` | P2 | 多源复用同一 event id 时证据行/时间线出现重复 Vue key。现证据行按 `sourceType-eventSourceId-sourceId`、时间线按 `reference` 兜底 `sourceType-sourceId` 生成 key |

每个修复对应独立提交：`2830022`（#16）、`7d8d56c`（#17）、`3a04113`（#18）、`a7ae629`（#19）。

第九轮（对 `ea09ea9` / `3037531`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 20 | `securityincident/SecurityIncidentService.restoreHandoffProjection()` | P1 | 被驱逐的已交接事件（含 model disposition）与仍存储的人工复核事件合并时，`restoreState()` 已在 `restored` 中选出 `HUMAN_REVIEW`，但原 reconciliation 只看 fresh 源记录 + 保留 handoff，会用 model 决策覆盖权威人工决策。现 `reconcileDisposition()` 同时纳入 `restored.dispositionRecord()`，人工复核继续 first-wins |
| 21 | `workflow/AlertWorkflowNodes.securityReview()` + `port/security/SecurityEventCatalog` | P1 | 安全评审此前把 source-qualified 引用降级为裸 event id 再查 `SecurityPort`，两个 adapter 复用同一 id 时会评审错误源；且仅 adapter 部署下注入的是 `EmptySecurityEventReader`，adapter 事件完全取不到。现引入 `SecurityEventResolver`/`SecurityEventCatalog` 聚合并优先具体源，`AlertWorkflowNodes`（及 showcase `AlertPreflightWorkflowFactory`）从引用 token 重建 `SecurityEventIdentity` 后解析 |
| 22 | `securityincident/SecurityIncidentService.authoritativeEvent()` | P2 | legacy reader 与 adapter 返回同一逻辑事件且携带相同已决记录时，`reconciled.equals(left.disposition())` 恒真而丢弃 enriched 表示。现两侧都承载 reconciled 决策时回退 `fresherEvent()`，保留 adapter 的具体源、severity、confidence |

每个修复对应独立提交：`e778fac`（#21）、`2a0d546`（#20）、`43f4abc`（#22）。

第十轮（对 `8ab2ab0`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 23 | `securityincident/SecurityIncidentConfiguration` | P1 | 仅 adapter 部署时合成的 `securityEventReader` 是空的 `EmptySecurityEventReader`，于是共享的 `SecurityQueryTool`（诊断/专家工具）永远查不到事件的 adapter 事件，尽管同一事件对事件关联可见。现将聚合 `SecurityEventCatalog` 注册为可注入的 `SecurityEventReader`/`SecurityPort`（`SecurityEventCatalog` 同时实现 `SecurityEventReader`），并让工作流工厂通过 `SecurityEventCatalog.aggregating` 复用该聚合而非二次包装，避免重复读取 |
| 24 | `securityincident/SecurityIncidentService.restoreStates()` | P1 | 无源事件被两个复用同一 id 的具体源事件替换时，别名只由遍历顺序第一个 fresh 事件认领：一个更早发生但无关的 camera 事件会继承人工处置，而匹配的 access 事件保持 OPEN。现按 `lastOccurredAt` 距离预计算每个被别名遮蔽的存储事件的「首选认领者」，仅该 fresh 事件可认领其 incident 与 handoff |

每个修复对应独立提交：`c11e15a`（#23）、`3168da3`（#24）。

第十一轮（对 `e1a3d5a`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 25 | `securityincident/SecurityIncidentService.deduplicate()` | P1 | 单次 correlation pass 中，无源 legacy 事件会被折叠进**第一个**复用其 event id 的具体 adapter（不看 eventType 或 occurrence time），于是无关源会吞掉 legacy 表示（或其元数据），后续 `restoreStates` 的首选认领者逻辑也无法补救。现改为两阶段去重：先按 source-aware identity 归并具体源副本，再把每个无源别名折叠进**最佳**具体候选（具体源优先 → eventType 相同 → `occurredAt` 距离最小），仅当没有任何具体候选时才与另一别名合并 |
| 26 | `tool/security/SecurityQueryTool` | P1 | 混合部署（legacy `SecurityEventReader` + 独立 `SecuritySourceAdapter`）下 early return 让注入的 `SecurityPort` 仍是 legacy reader，诊断/安全专家工具查不到事件中心已展示的 adapter 事件。现工具用 `SecurityEventCatalog.aggregating(reader, adapters)` 在消费侧聚合（`aggregating` 幂等，adapter-only 部署复用配置安装的聚合，mock 部署仍只有单一 `SecurityPort` bean）；`getEvent` 的 `NoSuchElementException` 映射为安全的 `Unknown security event` 结果 |
| 27 | `model/security/SecurityEventIdentity.decodeMaterial()` | P2 | 畸形 qualified 引用（如 `security-event:source:2147483647#x`）的 `start + length` 会溢出为负数，越界检查被绕过、`substring` 抛 `StringIndexOutOfBoundsException`，使外部告警中断安全流程。现先校验 `length < 0 \|\| length > material.length() - start` 再计算 `end`，令牌按 malformed 回退为 legacy 引用 |

每个修复对应独立提交：`437075e`（#25）、`4aae553`（#26）、`563e5d7`（#27）。

第十二轮（对 `f3f91d0`）补 1 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 28 | `port/security/SecurityEventCatalog.getEvent(String)` / `getEvent(SecurityEventIdentity)` | P1 | 两个 adapter 复用同一 source-local event id 时，裸 id 重载会匹配两者的全部副本并静默返回 `receivedAt` 最新的具体事件（可能属于另一源/园区/楼栋）。`SecurityQueryTool.lookupSecurityEvent()` 与只提取裸 `SEC-*` 的协作取证器都走这条路径，诊断/专家结论可能锚定错误事件。现按候选背后「具体源 + 仅无源别名的位置」计数逻辑事件，>1 时抛 `ambiguous security event id ...; use a source-qualified reference` 而非猜测；同一逻辑事件的多份表示（具体源 + 无源别名、同源重复读取）仍会折叠，source-qualified identity 仍可无歧义解析 |

对应独立提交：`62242c6`（#28）。

第十三轮（对 `a2daf31`）补 1 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 29 | `securityincident/SecurityIncidentService.preferredAliasClaimants()` | P1 | 无源已复核 incident 被有界 incident store 淘汰、但其 handoff 仍保留时，别名首选认领者 map 只由 `stored` 构建（`reservedForAnotherClaimant` 用 `handoff.incidentId()` 查 map 落空），于是遍历顺序第一个复用该 id 的具体源会继承保留的人工 disposition，即使另一条才匹配。现把 incident 的 occurrence time 投影到 `SecurityIncidentHandoff`（`SecurityIncidentHandoffStore` 传 `incident.lastOccurredAt()`），并让不在 `stored` 中的 retained-only handoff 用同一「fact + 距离」规则预计算首选认领者 |

对应独立提交：`cce05d3`（#29）。

第十四轮（对 `8438865`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 30 | `securityincident/SecurityIncidentService.bucketKey()` | P1 | 枚举映射未覆盖的 vendor 类型都标准化为 `SecurityEventType.UNKNOWN`，于是同一园区/楼栋/15 分钟窗口内两个互不相关的未知 vendor 事件会被并入同一 incident，静默合并证据、计数、处置与 handoff。现当标准类型为 `UNKNOWN` 时用 `rawEventType` 作为相关桶（及 incident identity）的额外判别维度；重复出现的同一未知 vendor 类型仍归为同一 incident |
| 31 | `securityincident/SecurityIncidentService.authoritativeEvent()` | P2 | 无源 reader 副本承载选中处置、其匹配的具体 adapter 副本未复核时，该分支整体返回无源记录，丢弃 adapter 的 source、severity、confidence 与 ingest 元数据并把事件投影为 `UNKNOWN`。现把 reconciled 处置附加到「最丰富」的表示上（具体源优先，其次 severity/confidence/adapter ingest 元数据，再次 freshness），而非选择恰好承载决策的副本；`SecurityEvent` 新增 `withDisposition()` |

每个修复对应独立提交：`ed99287`（#30）、`fe92d5a`（#31）。

第十五轮（对 `c1c1774`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 32 | `securityincident/SecurityIncidentService.restoreHandoffProjection()` | P1 | 两个被有界 store 淘汰的已交接窗口随后经 bridge event 合并时，`matchingRetainedHandoffs` 可能含两条投影，但原 reconciliation 只纳入按最早 `createdAt` 选中的那条 handoff 的 disposition。若被忽略的投影承载权威人工复核、选中的承载注册模型决策，合并后的 incident 会静默丢失人工 disposition，且该 handoff 随后被 retire。现把每条 matching handoff 的 disposition record 一并折叠进 `reconcileDisposition()`，仍只选一个 work-item ID |
| 33 | `port/security/SecurityEventCatalog.getEvent(SecurityEventIdentity)` | P1 | source-qualified 身份此前用对称的 `matches()` 别名谓词过滤：当所请求的源不存在、只存在同 id/位置的 source-less legacy 副本（或另一个复用同一 id 的具体源）时，`select()` 只看到一个候选事件并返回，工作流便以一个从未归属到所请求源的事件继续。现具体源请求要求精确身份相等（无精确匹配则 `NoSuchElementException`），仅 source-less legacy 请求继续使用别名谓词与歧义检查 |

每个修复对应独立提交：`102e4b5`（#32）、`5afeda2`（#33）。

第十六轮（对 `d306e92`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 34 | `port/security/SecurityEventCatalog` 的 `select()` / `getEvent(SecurityEventIdentity)` | P2 | reader 与 adapter 暴露同一逻辑事件的不同快照时，处置修正可能 `decidedAt` 更晚、但 `receivedAt` 不变或更旧；原 `PREFERRED` 只按「具体源优先 → receivedAt 最新」选副本，于是把陈旧或 `UNREVIEWED` 的副本返回给 `SecurityQueryTool` 等消费方，与 incident service 的 reconciliation 结果不一致。现对同一逻辑事件的副本 reconciling 处置记录（`HUMAN_REVIEW` first-wins，否则 `max(decidedAt)`），并把胜出决策附加到首选表示上（`SecurityEvent.withDisposition`）；两侧（裸 id 的 `select()` 与 source-qualified 查找）都适用 |
| 35 | `collaborationcenter/SecurityIncidentHandoffStore.projectedFieldsChanged()` | P2 | 已交接事件的 occurrence time 被 adapter 修正、而身份/风险/摘要/类型/处置都不变时，替换后的 handoff 虽带新的 `lastOccurredAt`，但 `projectedFieldsChanged()` 未比较该字段而返回 false，保留了旧 `updatedAt`；协同中心按 `updatedAt` 排序安全工单，修正后的投影继续显示为陈旧。现把 `lastOccurredAt` 作为又一个投影字段参与比较 |

每个修复对应独立提交：`bbcade5`（#34）、`a787698`（#35）。

第十七轮（对 `fa8e2d3`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 36 | `securityincident/SecurityIncidentService.restoreHandoffProjection()` | P1 | 人工复核的已交接窗口被有界 incident store 淘汰、而较新的注册模型已交接窗口仍留在 store 时，bridge event 合并二者会走 `restored.handoffWorkItemId() != null` 的早返回，使上一轮新增的 reconciliation 段不可达：保留的人工决策随其 handoff 一起被 retire，合并结果只剩模型 disposition（尽管人工复核权威）。现即使在已有 stored handoff ID 的分支，也先把每条 matching retained handoff 的 disposition record 折叠进 `reconcileDisposition()`，再决定是否重建（沿用 stored 的 work-item ID、incident ID 与状态；仅当 reconciliation 改变记录时才重建） |
| 37 | `securityincident/SecurityIncidentService.preferredAliasClaimants()` 及别名认领集合 | P1 | 一个已复核的 source-less incident 可含多个不同 event id；adapter enrichment 加 occurrence time 修正后这些事件被拆进不同窗口，每个窗口都唯一别名匹配其中一个 stored 身份。但首选认领者 reservation 与 `claimAliasOnly*` 认领集合都以「整个 stored incident」为键、只选一个 fresh incident，另一个窗口在 restore 时被排除，丢失人工 disposition 或 handoff。现两者的键都改为「stored incident + stored event identity」：仅在某个身份被多个窗口争用时才 reservation，且只有当 fresh 窗口共享的**全部**别名身份都已被认领时才排除它，因此每个唯一匹配的拆分窗口都能保留 finalized state；单身份别名（无关源仅排序靠前）行为不变 |

每个修复对应独立提交：`9f9202d`（#36）、`9b58e60`（#37）。

第十八轮（对 `7c57870`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 38 | `securityincident/SecurityIncidentService.preferredAliasClaimants()` | P1 | reservation 的键虽已按 stored event identity 细分，但排序仍以整条 incident/handoff 的 `lastOccurredAt` 为基准，而非该身份自身的 occurrence time。当较早的 event id 随后被多个具体源在不同窗口暴露时，距 incident 末尾事件最近的无关副本仍会被 reserved 并继承人工 disposition/handoff，真正的 enrichment 反而丢失。现从 stored incident 的 evidence 派生每个身份的发生时间，并把 per-identity occurrence time 投影到 `SecurityIncidentHandoff`（`identityOccurredAt` map，`SecurityIncidentHandoffStore` 从 incident evidence 构建并按投影字段参与 `projectedFieldsChanged`），使被淘汰、仅存 handoff 的 incident 也能按身份时间排序；fresh 侧同样以匹配身份的自身时间计算距离 |
| 39 | `model/security/SecurityEventIdentity.fromReference()` | P2 | alert 携带语法合法、但 source type 未知或拼错的 source-qualified reference 时，`fromName()` 返回 `UNKNOWN`，原实现把它降级为 source-less wildcard 并仅保留解码出的 event id。`SecurityEventCatalog.getEvent(identity)` 于是走 legacy-alias 分支，可能解析到同 id/位置的另一源事件，绕过安全流程依赖的 exact-source 保证。由于 `reference()` 从不输出编码的 `UNKNOWN` source，现直接以 `IllegalArgumentException` 拒绝此类 qualified token，而非降级 |

每个修复对应独立提交：`46fb10f`（#38）、`0b7d93e`（#39）。

第十九轮（对 `4136c0b`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 40 | `model/security/SecurityEventIdentity.isSourceLess()` | P1 | `isSourceLess()` 只按 `sourceType == UNKNOWN` 判定，于是 adapter 回退到 `UNKNOWN` 却带不同逻辑 id（如 `UNKNOWN/vendor-a` 与 `UNKNOWN/vendor-b`）时，两者都被当作 source-less；同 event id 与 location 的事件被折叠成 legacy 别名，其 reference 丢弃 source id，污染 incident evidence 与处置。由于 `reference()` 从不编码 `UNKNOWN`、且 canonical source-less 表示只能有一个，现于 `SecuritySourceRef`（`SecuritySourceDescriptor` 经其校验）拒绝 `UNKNOWN` 搭配非 `"unknown"` 的 source id，与 Fix #39 的拒绝策略一致 |
| 41 | `model/security/SecurityEvent` 的 ingestion provenance | P1 | adapter 把 connection URL、凭据型 label 或 token 写进 `ingestedBy`/`ingestVersion` 时，原实现只做 trim 便原样接受，而其余安全标识符都走 `SecurityIdentifierPolicy`；`SecurityQueryTool.SecurityLookupResult` 会返回完整 `SecurityEvent`，把这些 provenance 暴露给模型/工具消费方。现两个字段都走 `SecurityIdentifierPolicy.optionalSafe`，复用与其它标识符相同的凭据/URL 拒绝规则，并保留空值默认 `unspecified`/`null` |
| 42 | `tool/security/SecurityQueryTool.SecurityLookupResult` | P2 | notice 硬编码为 “Mock redacted security data only”，但该工具已聚合 legacy reader 与全部 `SecuritySourceAdapter`；`productionSource=true` 的新增 adapter 的结果仍被标为 mock data。现改用在 mock 与生产源下都准确的文案（说明数据已脱敏，不声称是 mock），避免误导模型/工具消费方 |

每个修复对应独立提交：`73932f3`（#40）、`a00c859`（#41）、`39a11a0`（#42）。

第二十轮（对 `58cb69c`）补 1 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 43 | `model/security/SecurityEventIdentity.reference()` / `fromReference()` | P2 | 无源事件的 id 若本身形如合法 qualified payload（如 `source:14#ACCESS_CONTROL:4#feed:3#evt`），`reference()` 输出的 `security-event:` + 裸 id 会被 `fromReference()` 重新解码为具体源 `ACCESS_CONTROL/feed` + 事件 `evt`，工作流因此查找错误的身份或 404。现于模型边界拒绝「会被解码为 qualified reference」的 event id（`SecurityEventIdentity` 与 `SecurityEvent` 共同校验），使裸 legacy reference 不再有歧义；仅以 `source:` 开头但无法解码的 id 仍按 legacy 处理 |

对应独立提交：`e43e3da`（#43）。

第二十一轮（对 `d9409f5`）补 3 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 44 | `securityincident/SecurityIncidentService.aliasPreference()` | P1 | 第十四轮的 raw-type 判别只覆盖了 correlation 分桶，别名首选认领者的比较器仍按标准化 `eventType` 判定「同类型」，于是无源事件与多个具体源事件复用同一 id、且其未知 vendor code 都归一为 `UNKNOWN` 时全部并列，退化为按时间戳就近选择：无关的具体事件（不同 `rawEventType`）可能先吸收 legacy 事件及其 disposition。现套用同一 `correlationType()` 判别（`UNKNOWN` 时包含 `rawEventType`）比较候选 |
| 45 | `tool/security/SecurityQueryTool.SecurityLookupResult` | P1 | 返回完整 `SecurityEvent` 会把 adapter 提供的人工/模型处置所带的嵌套 `actor`、`modelId`、`modelVersion`、`evidenceRef` 序列化给 AI 工具消费方，尽管工具契约明确承诺不含身份记录、HTTP DTO 也刻意省略这些 provenance 字段。现改为白名单脱敏投影 `SecurityEventSummary`：保留处置状态、来源与决策时间，丢弃 actor 及注册模型标识，并出于同样原因省略 adapter ingest 元数据 |
| 46 | `securityincident/SecurityIncidentService.preferredRepresentation()` | P2 | 表示选择按「丰富度」（severity/confidence/ingest 加分）偏向较旧快照，于是同一具体源的两个快照都带已决处置、较新快照有意清空错误的 severity/confidence/ingest-version 时，仍选较旧更丰富者，incident 保留陈旧元数据。现只保留「具体源 vs 无源 legacy」的丰富度优先级，同一具体源的副本按 `receivedAt` 取最新，删除 `enrichmentScore()` |

每个修复对应独立提交：`55771a0`（#44）、`e42e564`（#45）、`36685c0`（#46）。

第二十二轮（对 `bee8bc7`）补 1 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 47 | `tool/security/SecurityQueryTool.lookupSecurityEvent()` | P1 | 工具把任意 `IllegalArgumentException` 的 message 回填进 `SecurityLookupResult.error`，而该结果既交给 AI 工具消费方、又进入公开的 expert findings。生产 adapter 在 `SecurityEventCatalog.readEvents()` 抛出的错误可能含连接 URL 或带凭据的配置 label，于是恰好泄露脱敏契约要隐藏的厂商私有细节。现新增用户安全的专用异常 `SecurityEventLookupException`（裸 id 歧义仍带可操作提示），其它失败一律返回固定的公开错误，adapter 异常 message 不再外泄 |

对应独立提交：`b85fb97`（#47）。

第二十三轮（对 `8afa4b3`）补 1 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 48 | `tool/security/SecurityQueryTool.lookupSecurityEvent()` | P1 | 两个源复用同一 event id 时目录返回歧义错误并提示「use a source-qualified reference」，但工具始终把入参转发给裸 id 重载：`SecurityEventIdentity.reference()` 产出的 `security-event:source:...` token 与 `event.eventId()` 不相等，于是返回 "Unknown security event"，诊断与安全专家消费方都无法取回任一冲突事件。现 `SecurityEventIdentity` 新增 `isQualifiedReference()`，resolver 新增 `getEventByReference()`（qualified token 精确解析到该源，legacy token 保持裸 id 别名语义，跨位置匹配仍判为歧义），工具对引用 token 走该路径、裸 id 维持原查找 |

对应独立提交：`d54ff32`（#48）。

第二十四轮（对 `69de66f`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 49 | `model/security/SecurityEvent` 紧凑构造器 | P1 | `rawEventType` 仅 trim，而 `SecurityIncidentDtos.evidence()` 与 `SecurityQueryTool.SecurityEventSummary` 会原样序列化它；生产适配器若把 URL 或带凭据的标签放进 vendor code，就会绕过 source/location/ingest 标识所施加的安全策略，泄露给有权限的 UI 用户与 AI 工具消费方。现 `rawEventType` 走 `SecurityIdentifierPolicy.requireSafe`：无害的未知 vendor code（如 `VENDOR_PRIVATE_CODE_42`）仍原样保留，URL/凭据在入库时即被拒 |
| 50 | `collaboration/CollaborationRuntimeConfiguration.collectPrimaryEvidence()` | P2 | 该提取器只匹配 `\bSEC-[A-Z0-9-]+\b` 并大写裸子串，合作问题中的 source-qualified 引用被截成裸 id，目录随即以歧义拒绝复用 id，确定性的服务端安全证据丢失。现安全模式同时匹配完整的 `security-event:` 引用，`SecurityEventIdentity.canonicalQualifiedReference` 重新编码（容忍句尾标点），归一化保留大小写敏感的材料而非大写；裸 id 与 legacy 引用维持原有大写查找 |

对应独立提交：`dab4974`（#49）、`4cc5b6a`（#50）。

第二十五轮（对 `ae38fb0`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 51 | `model/security/SecurityEvent` 紧凑构造器 | P1 | `eventId`/`parkId`/`buildingId` 只校验非空，生产适配器若提供含连接 URL 或凭据的 event id（如 `https://internal.example/event`、`token=abc`），该值会作为 `SecurityEventSummary.eventId` 与事件证据 `sourceId` 暴露给 AI 工具消费方与事件 API。现三个边界标识均走 `SecurityIdentifierPolicy.requireSafe`，并删除不再使用的 `requireText` |
| 52 | `collaboration/CollaborationRuntimeConfiguration` 安全提取 | P2 | 原模式把 qualified 引用限制在 `[A-Za-z0-9_#:.\-]+`，而 `SecuritySourceRef` 接受 `access/feed` 这类 id 且 `reference()` 原样编码；模式在 `/` 处截断，残缺 token 无法解码后被转成错误的 legacy 查找，引用的证据被静默丢弃。现 `SecurityEventIdentity.canonicalQualifiedReference` 按长度前缀文法从 token 起始严格解析三段并重新编码（忽略后续散文），任何 source/event id 允许的字符都能保留；`CollaborationRuntimeConfiguration` 扫描 `security-event:` 前缀并据此解析，仅在不属于任何 qualified 引用时报告裸 id |

对应独立提交：`7101c79`（#51）、`1e1c35c`（#52）。

第二十六轮（对 `b20777e`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 53 | `model/security/SecurityIdentifierPolicy` | P2 | 该策略把 `token`/`password`/`secret`/`apikey`/`credential` 当作子串匹配；策略现已覆盖 event id、raw event type、source id 与 ingest 元数据，`ACCESS_TOKEN_REJECTED`、`INVALID_CREDENTIAL` 这类普通领域术语会导致 `SecurityEvent` 构造失败，甚至让整个 adapter 读取失败。现改为匹配「凭据语法」而非词本身：URL scheme（`://`）、`token=`/`password:` 赋值、`apikey-123` 值、或整串就是裸密钥；`token=abc`、`password:secret`、`credential:v2`、`apikey-123`、`secret` 仍被拒 |
| 54 | `model/security/SecurityEventIdentity.reference()` | P2 | 引用只编码 source type、source id、event id，未含位置；同一源在两个 park/building 复用本地 id 时两个身份产生同一引用，`SecurityEventCatalog.getEventByReference()` 以歧义拒绝，而 `SecurityQueryTool` 没有 park/building 参数，导致两个事件都无法经推荐路径取回。现 `reference()` 追加长度前缀编码的 park/building，`canonicalQualifiedReference` 保留它们，`fromReference` 优先使用 token 自带位置、仅对 legacy token 回退到告警位置 |

对应独立提交：`f513707`（#53）、`ae2de8a`（#54）。

第二十七轮（对 `c2e6bae`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 55 | `port/security/SecurityEventCatalog.getEventByReference` | P2 | `reference()` 现在总带 park/building，而 `getEventByReference` 用整串精确比较；调用方传入此前合法的三段（无位置）source-qualified token 时永远匹配不到，`SecurityQueryTool` 会把本可唯一确定的事件报成 unknown。现先把 token 解析为「source + eventId + 可选 location」（新增 `SecurityEventIdentity.parseQualifiedReference` / `QualifiedReference`），按该身份匹配：无位置 token 在源内唯一时解析，仅当候选跨 park/building 分歧时报歧义；命名未知 source type 的 token 仍拒绝，不降级为裸 id 查找 |
| 56 | `collaboration/CollaborationRuntimeConfiguration.collectPrimaryEvidence` | P2 | 提取出的安全引用被直接拼进 `{"eventId":"..."}` 而未做 JSON 转义；长度前缀解析会保留合法 source/event/park/building 标识中的引号或反斜杠，导致回调收到畸形/被篡改的参数，异常被吞后确定性证据被静默丢弃。现用 Jackson 序列化工具参数对象，策略允许的任意标识都能原样往返 |

对应独立提交：`a8bc77c`（#55）、`f3a9b8a`（#56）。

第二十八轮（对 `4aa3935`）补 2 条：

| # | 位置 | 级别 | 处理 |
| --- | --- | --- | --- |
| 57 | `securityincident/SecurityIncidentService.alertsByReference` | P2 | 索引按告警证据里的**原始** token 建键，而 `alertsReferencing` 只查当前五段 `identity.reference()` 与 legacy 裸引用，因此以早前合法的三段（无位置）source-qualified token 持久化的告警永远匹配不上：事件在工作流里能解析，告警 id 与风险却被丢弃，HIGH 事件退化成未关联的 MEDIUM。现于建键前归一化引用——无位置 token 用告警自身的 park/building 补全，带位置 token 保留自身位置，legacy/畸形 token 原样保留（自然匹配不到） |
| 58 | `model/security/SecurityEventIdentity.decodeLocation` | P2 | `decodeLocation` 对「有意支持的无位置形式」与「损坏的后缀」都返回 `null`，于是一段被截断的 park/building 会被静默当作无位置引用，`SecurityEventCatalog` 可能在恰好唯一的位置上错误命中。现只有三段身份后**精确结束**才算无位置形式；若后续材料以长度前缀（`:` + 数字 + `#`）开头却无法解出完整的 park 与 building，则视为畸形：`canonicalQualifiedReference`/`parseQualifiedReference` 返回 `null`，`fromReference` 直接拒绝而不再回退到告警位置 |

对应独立提交：`11e4de3`（#57）、`5302bb0`（#58）。
