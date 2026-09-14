# Issue #62 实施计划：扩展安全事件类型与误报判定数据模型

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Issue:** #62 安防能力：扩展安全事件类型与误报判定数据模型
**Goal:** 在现有 `SecurityEvent` / `SecurityIncident` 能力上建立可扩展、可审计、可解释的安全事件领域模型，标准化事件类型、来源适配器边界、来源能力分离与 `disposition`（含误报）语义；在没有真实数据源时保持 fail-closed，不用 seed/mock 冒充部署能力。

**Architecture:** 事件类型标准化为 `SecurityEventType` 枚举，来源适配器通过 `SecuritySourceAdapter` 端口输出统一 domain event；`SecurityEventCapabilityRegistry` 将「模型支持类型」与「当前部署已接入数据源」分开表达。误报等判定收敛为 `SecurityDisposition` + `SecurityDispositionRecord`，只接受人工复核或已登记模型（含版本/证据）作为 `FALSE_POSITIVE` 的合法来源。服务层扩展现有状态机而不创建平行系统。

**Tech Stack:** Java 21, Spring Boot MVC, JUnit 5 / AssertJ, Vue 3, TypeScript, Vitest, Element Plus, npm。

**Spec（本计划内联设计契约；如需再拆 spec，见文末「设计契约」章节）**：现有设计基线 `docs/superpowers/specs/2026-09-02-security-incident-center-design.md`。

## 前置条件与关键判断

- PR #57 已合并（`Merge commit 0cc4566`），依赖满足；从最新 `main` 创建独立分支，建议 `codex/issue-62-security-event-model`。
- 当前只有 `MockSecurityAdapter` 从 `MockParkDataStore` 读一条 `SEC-ACCESS-001`（`UNAUTHORIZED_ACCESS_ATTEMPT`）。**不存在烟火/越界/聚集/离岗的真实 source。**
- 因此本 Issue 的诚实交付是：模型 + 端口 + capability 分离 + disposition 模型 + 测试 + 明确的 NOT_READY 能力映射。**不新增任何 seed/mock 事件类型来点亮 chip。**
- 复用而非重建：`SecurityIncidentCenter`、Collaboration Center、Workflow/work item、Execution Trace、Governance/audit、`RedactedEvidencePolicy`。

## Global Constraints

- 人员/图像相关字段继续执行脱敏；证据摘要必须 `REDACTED:` 前缀并通过 `RedactedEvidencePolicy.require`。
- 不得用 UI 标签、AI 猜测或 `confidence < x` 阈值生成 `FALSE_POSITIVE`；没有合法来源只能显示 `UNREVIEWED` / `INCONCLUSIVE`。
- 不得硬编码事件数量；capability 必须来自实际注册的 adapter，未接入类型保持 `NOT_READY`。
- 错误信息不得暴露摄像头凭证、内部 URL、token；AI 描述不得覆盖原始事件事实。
- role 权限继续由现有 `SecurityIncidentCenter` 规则控制（读/研判仅 `APPROVER`/`ADMIN`）。
- `SecurityIncidentService` 仍只依赖端口，不依赖 adapter/web/device（`SecurityIncidentArchitectureTest` 必须继续通过）。
- 每个任务先写失败测试，再写最小实现，跑完聚焦测试并提交一次。走完整 `./mvnw.cmd test` 与 `ui` 的 `npm test` / `npm run build`。

---

## 设计契约（实现目标形态）

### 事件类型与来源

```java
public enum SecurityEventType { FIRE_SMOKE, PERIMETER_INTRUSION, CROWDING,
        POST_ABSENCE, ACCESS_ANOMALY, UNKNOWN;
    static SecurityEventType fromRaw(String raw); }   // 未知 raw -> UNKNOWN，不抛异常
public enum SecuritySourceType { CAMERA_ANALYTICS, ACCESS_CONTROL, EXISTING_FEED, UNKNOWN }
public enum SecurityEventSeverity { LOW, MEDIUM, HIGH, CRITICAL, UNKNOWN }
```

- raw→type 映射放在 adapter 层（`SecuritySourceAdapter`），不允许 UI 依赖厂商私有字段。
- 「模型支持某类型」= 枚举存在；「已接入」= 某 adapter 的 `descriptor().connectedEventTypes()` 包含该类型。

### 扩展后的 SecurityEvent（保持兼容构造器）

```java
public record SecurityEvent(
    String eventId, String parkId, String buildingId,
    SecurityEventType eventType, String rawEventType,          // 原始类型仅审计用
    SecuritySourceRef source,                                   // sourceType/sourceId（脱敏）
    SecurityEventLocation location,                             // building/zone/device/camera（可空）
    Instant observedAt, Instant receivedAt,
    SecurityEventSeverity severity,                             // 无来源则为 UNKNOWN
    Double confidence,                                          // 无来源/无模型则为 null
    SecurityPrivacyMetadata privacy,
    SecurityDisposition disposition,
    SecurityIncidentAudit audit) { ... }
```

- 保留旧签名 `SecurityEvent(id, park, building, String type, occurredAt, summary)` 作为便捷构造器：内部 `SecurityEventType.fromRaw(type)`、`severity=UNKNOWN`、`confidence=null`、`disposition=UNREVIEWED`，避免大范围破坏现有测试。
- `evidenceSummary` 继续强制 `RedactedEvidencePolicy`。
- `confidence` 若存在必须 `0.0 <= c <= 1.0`，否则拒绝；绝不合成。
- `SecurityPrivacyMetadata(redactionPolicy, personalDataPresent, mediaStored)`，当前 adapter 一律 `REDACTED_ONLY / false / false`。

### 误报（disposition）模型

```java
public enum SecurityDisposition { UNREVIEWED, CONFIRMED_INCIDENT, FALSE_POSITIVE,
        INCONCLUSIVE, DUPLICATE }
public enum SecurityDispositionSource { NONE, HUMAN_REVIEW, REGISTERED_MODEL }
public record SecurityDispositionRecord(
        SecurityDisposition disposition, SecurityDispositionSource source,
        String actor, String modelId, String modelVersion,
        String evidenceRef, Instant decidedAt) { ... }
```

- 校验规则：`FALSE_POSITIVE` 只允许 `source == HUMAN_REVIEW` 或 `source == REGISTERED_MODEL`；后者必须非空 `modelId` + `modelVersion` + `evidenceRef`。
- `UNREVIEWED` 仅 `source == NONE`；`INCONCLUSIVE` / `DUPLICATE` 允许人工来源。
- `SecurityIncident` 增加 `disposition` 与 `dispositionRecord`；`review(disposition, note, at)` 记录人工来源与 actor，`OPEN→REVIEWED` 语义保留。

---

### Task 0: 分支与前置确认

**Files:** 无代码改动。

- [ ] 确认 `git log` 含 PR #57 合并；从最新 `main` 建分支 `codex/issue-62-security-event-model`。
- [ ] 记录基线：`./mvnw.cmd -q test` 与 `cd ui && npm test` 全绿，作为回归对照。
- [ ] 在 `docs/issue-62-capability-map.md` 写下初始结论：仅有 `ACCESS_ANOMALY`（Demo「已适配」来源），其余四类 `NOT_READY`。

---

### Task 1: 标准化事件类型与 raw 映射

**Files:**
- Create: `src/main/java/com/example/smartpark/model/security/SecurityEventType.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecuritySourceType.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityEventSeverity.java`
- Create: `src/test/java/com/example/smartpark/model/security/SecurityEventTypeTest.java`

**Interfaces:**
- `SecurityEventType.fromRaw(String raw): SecurityEventType`：大小写/别名归一，未知返回 `UNKNOWN`，空白输入返回 `UNKNOWN`，绝不抛异常。
- 已知别名至少覆盖现有 `UNAUTHORIZED_ACCESS_ATTEMPT` → `ACCESS_ANOMALY`，以及 `FIRE`/`SMOKE`、`INTRUSION`/`PERIMETER`、`CROWD`/`GATHERING`、`ABSENCE`/`OFF_POST` 等显式别名。别名表是唯一映射事实来源。

- [ ] **Step 1: 写失败测试** — `fromRaw` 覆盖每个标准类型、别名、未知串、`null`/空白，断言未知一律 `UNKNOWN` 且不抛异常；断言枚举顺序/集合稳定。
- [ ] **Step 2: 跑测试确认失败** — `./mvnw.cmd -q -Dtest=SecurityEventTypeTest test`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 实现三个枚举与不可变别名映射（`Map.of`/switch），`UNKNOWN` 为兜底。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(security): standardize event types and source enums"`。

---

### Task 2: 扩展 SecurityEvent 领域模型（保持兼容）

**Files:**
- Create: `src/main/java/com/example/smartpark/model/security/SecuritySourceRef.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityEventLocation.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityPrivacyMetadata.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityDisposition.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityDispositionSource.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityDispositionRecord.java`
- Create: `src/main/java/com/example/smartpark/model/security/SecurityIncidentAudit.java`
- Modify: `src/main/java/com/example/smartpark/model/security/SecurityEvent.java`
- Modify: `src/main/java/com/example/smartpark/tool/security/SecurityQueryTool.java`（保持只输出脱敏摘要，适配新的 `eventType()` 类型）
- Modify: `src/test/java/com/example/smartpark/model/security/SecurityEventTest.java`
- Create: `src/test/java/com/example/smartpark/model/security/SecurityDispositionRecordTest.java`

**Interfaces:**
- `SecurityEvent` 新字段如设计契约；保留旧 6 参构造器委派到新构造器。
- `SecurityEvent.eventType()` 返回 `SecurityEventType`，`rawEventType()` 保留原始字符串。
- `SecuritySourceRef(sourceType, sourceId)`：`sourceId` 非空但必须是无凭证标识（测试禁止 `://`、`token`、`password`、`secret`）。
- `SecurityDispositionRecord` 校验规则如设计契约。
- `SecurityIncidentAudit(receivedAt, ingestedBy, ingestVersion)`：`ingestedBy` 为 adapter 逻辑名，不含 URL。

- [ ] **Step 1: 写失败测试** — 断言：兼容构造器得到 `ACCESS_ANOMALY`；未知 raw 得到 `UNKNOWN`；`confidence` 越界被拒；`FALSE_POSITIVE` 无合法来源被拒；`REGISTERED_MODEL` 缺 `modelId`/`modelVersion`/`evidenceRef` 被拒；`HUMAN_REVIEW` 合法；证据未脱敏被拒；`sourceId` 含 URL/凭据标记被拒。
- [ ] **Step 2: 跑测试确认失败** — `./mvnw.cmd -q -Dtest=SecurityEventTest,SecurityDispositionRecordTest test`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 新增 record/enum，扩展 `SecurityEvent`，复用 `RedactedEvidencePolicy`，所有校验在紧凑构造器中 fail-closed。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(security): extend security event domain model with disposition"`。

> 注意：`SecurityIncidentService` 中所有 `event.eventType()` 的字符串用法改为 `event.eventType().name()`（Task 5 处理），此任务先保证模型层编译与单测通过。

---

### Task 3: Source Adapter 端口与 capability 分离

**Files:**
- Create: `src/main/java/com/example/smartpark/port/security/SecuritySourceAdapter.java`
- Create: `src/main/java/com/example/smartpark/port/security/SecuritySourceDescriptor.java`
- Create: `src/main/java/com/example/smartpark/port/security/SecurityEventCapability.java`
- Create: `src/main/java/com/example/smartpark/port/security/SecurityEventCapabilityRegistry.java`
- Modify: `src/main/java/com/example/smartpark/port/security/SecurityEventReader.java`
- Modify: `src/main/java/com/example/smartpark/adapter/mock/MockSecurityAdapter.java`
- Create: `src/test/java/com/example/smartpark/port/security/SecurityEventCapabilityRegistryTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/mock/MockSecurityAdapterTest.java`

**Interfaces:**
- `SecuritySourceDescriptor(String sourceId, SecuritySourceType sourceType, Set<SecurityEventType> connectedEventTypes, boolean productionSource)`。
- `SecuritySourceAdapter { SecuritySourceDescriptor descriptor(); List<SecurityEvent> readEvents(); }`，adapter 输出统一 domain event。
- `SecurityEventCapabilityRegistry` 聚合 `ObjectProvider<SecuritySourceAdapter>`：
  - `modelSupportedTypes()` = `SecurityEventType` 全集（模型能力）。
  - `capabilities()` 每个类型返回 `SecurityEventCapability(eventType, modelSupported=true, sourceConnected, productionSource, state)`。
  - `state`：`AVAILABLE`（生产源接入）/ `ADAPTED`（仅有 Demo/非生产源）/ `NOT_READY`（无源）。**没有 adapter 声明的类型必须 `NOT_READY`。**
- `MockSecurityAdapter` 实现 `SecuritySourceAdapter`，`descriptor()` 声明 `ACCESS_CONTROL` + `{ACCESS_ANOMALY}` + `productionSource=false`，因此 `ACCESS_ANOMALY` 只到 `ADAPTED`。
- `SecurityEventReader` 保留 `listEvents()`，由 adapter 组合实现（Mock 场景仍由 Mock adapter 提供）。

- [ ] **Step 1: 写失败测试** — 断言：`FIRE_SMOKE` 等四类 `modelSupported=true` 但 `sourceConnected=false` 且 `state=NOT_READY`；`ACCESS_ANOMALY` 为 `ADAPTED` 且 `productionSource=false`；空 adapter 列表时全部 `NOT_READY`；registry 结果不可变、顺序稳定。
- [ ] **Step 2: 跑测试确认失败** — `./mvnw.cmd -q -Dtest=SecurityEventCapabilityRegistryTest,MockSecurityAdapterTest test`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 端口 + 枚举/record + registry；`MockSecurityAdapter` 适配为 adapter 并标注非生产源。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(security): add source adapters and capability separation"`。

---

### Task 4: 服务层集成归并与 disposition 状态机

**Files:**
- Modify: `src/main/java/com/example/smartpark/securityincident/SecurityIncident.java`
- Modify: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentStatus.java`（保持 `OPEN/REVIEWED/HANDOFF`，不新增平行状态）
- Modify: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentService.java`
- Modify: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentConfiguration.java`（注入 capability registry / adapters）
- Modify: `src/main/java/com/example/smartpark/securityincident/SecurityIncidentEvidence.java`（如需携带 source/severity 摘要）
- Modify: `src/main/java/com/example/smartpark/port/collaboration/SecurityIncidentHandoff.java`（透传 `disposition` 与 `rawEventType`）
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/SecurityIncidentHandoffStore.java`
- Modify: `src/test/java/com/example/smartpark/securityincident/SecurityIncidentServiceTest.java`
- Modify: `src/test/java/com/example/smartpark/securityincident/SecurityIncidentFixtures.java`

**Interfaces:**
- `SecurityIncident` 增加 `SecurityEventType eventType`、`SecurityDisposition disposition`、`SecurityDispositionRecord dispositionRecord`；`eventType` 在 DTO 输出 `.name()`。
- `SecurityIncidentService.review(String incidentId, SecurityDisposition disposition, String note)`：
  - 默认 `CONFIRMED_INCIDENT`；记录 `SecurityDispositionSource.HUMAN_REVIEW` 与 actor。
  - `FALSE_POSITIVE` 走同一人工路径（合法），并写入 `dispositionRecord`。
  - `review` 幂等：已 `REVIEWED` 的事件重复调用返回当前值（保持现有幂等语义），不允许在 `HANDOFF` 后改写。
- 归并键改用 `SecurityEventType`；`incidentId` 材料使用 `eventType.name()`。
- 事件级 disposition 聚合到事件实例：任一 `FALSE_POSITIVE` 不自动传播；事件实例 disposition 由最近一次人工/模型判定决定，未判定为 `UNREVIEWED`。
- 保留 `restoreStates`/`handoff` 的既有幂等与投影逻辑；新增 `dispositionRecord` 在 `withStoredState` 中透传，避免刷新丢失。
- `handoff` 生成的协同投影携带 `disposition` 与 `rawEventType`，供协同中心只读展示。

- [ ] **Step 1: 写失败测试** — 覆盖：多事件类型归并；`UNKNOWN` 类型归并为独立事件；disposition 默认 `UNREVIEWED`；`review(FALSE_POSITIVE)` 记录人工来源；重复 review 幂等；`HANDOFF` 后不改写 disposition；刷新/重算不丢失 disposition；与 collaboration handoff 关联；风险升级逻辑不变。
- [ ] **Step 2: 跑测试确认失败** — `./mvnw.cmd -q -Dtest=SecurityIncidentServiceTest,SecurityIncidentStoreTest test`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 修改模型/服务/配置，沿用确定性归并与幂等键；不触碰协同中心归并职责。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(security): integrate disposition into incident lifecycle"`。

---

### Task 5: REST / DTO 契约扩展与 capability 快照

**Files:**
- Modify: `src/main/java/com/example/smartpark/web/SecurityIncidentController.java`
- Modify: `src/main/java/com/example/smartpark/web/SecurityIncidentDtos.java`
- Create: `src/main/java/com/example/smartpark/web/SecurityCapabilityController.java`
- Modify: `src/main/java/com/example/smartpark/operations/OperationsCapabilitiesSnapshot.java`
- Modify: `src/main/java/com/example/smartpark/operations/OperationsCapabilitiesService.java`
- Modify: `src/test/java/com/example/smartpark/web/SecurityIncidentControllerTest.java`
- Create: `src/test/java/com/example/smartpark/web/SecurityCapabilityControllerTest.java`

**Interfaces:**
- `GET /api/security/incidents` / `{id}` 详情新增白名单字段：`eventType`、`rawEventType`、`sourceType`、`sourceId`、`severity`、`confidence`（可空）、`disposition`、`dispositionSource`、`dispositionDecidedAt`；不返回模型 Prompt、供应商响应、异常堆栈。
- `POST /api/security/incidents/{id}/review` 接受可选 JSON body `{ "disposition": "FALSE_POSITIVE", "note": "..." }`：
  - `disposition` 缺省为 `CONFIRMED_INCIDENT`；非法值 400；`FALSE_POSITIVE` 仍由服务端强制 `HUMAN_REVIEW` 来源，客户端不能声明 `REGISTERED_MODEL`。
  - 空 body 兼容旧调用。
- 新增 `GET /api/security/capabilities`（`APPROVER`/`ADMIN`）：返回每个 `SecurityEventType` 的 `modelSupported` / `sourceConnected` / `productionSource` / `state`，以及 `dispositionEnabled`。
- `OperationsCapabilitiesSnapshot` 增加 `securityEventCapabilities` 与 `securityDispositionEnabled`，供治理页与驾驶舱读取；保持向后兼容（新增字段，不改旧字段名）。
- 审计：disposition 变更写入 `AuditTrail` 的 `REVIEW_SECURITY_INCIDENT`，detail 含 disposition（不含 note 原文，避免注入敏感文本）。

- [ ] **Step 1: 写失败测试** — 角色 `APPROVER`/`ADMIN` 允许，`VIEWER`/`OPERATOR`/`CUSTOMER_AGENT` 拒绝；disposition 参数校验（未知值 400，`REGISTERED_MODEL` 被拒）；详情 DTO 白名单无敏感字段；capability 端点对四类新类型返回 `NOT_READY`；错误响应不含 adapter 异常/URL。
- [ ] **Step 2: 跑测试确认失败** — `./mvnw.cmd -q -Dtest=SecurityIncidentControllerTest,SecurityCapabilityControllerTest test`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 扩展 DTO/控制器，新增 capability 端点，接入 `OperationsCapabilitiesService`。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(web): expose security capabilities and disposition contract"`。

---

### Task 6: 前端 SecurityIncidentCenter 展示与研判操作

**Files:**
- Modify: `ui/src/types/securityIncident.ts`
- Modify: `ui/src/services/securityIncidentApi.ts`
- Modify: `ui/src/components/security/SecurityIncidentCenter.vue`
- Modify: `ui/src/components/security/SecurityIncidentCenter.spec.ts`
- Modify: `ui/src/utils/labels.ts`（事件类型 / disposition 中文标签）

**Interfaces:**
- `SecurityIncidentSummary` 新增 `eventType`（标准枚举）、`rawEventType?`、`sourceType?`、`sourceId?`、`severity?`、`confidence?`、`disposition`。
- `SecurityIncident` 新增 `dispositionRecord` 只读字段。
- `reviewSecurityIncident(role, incidentId, disposition?)` 发送 body。
- 交互：
  - 列表/详情显示标准事件类型中文标签与来源类型；`rawEventType` 仅作次要审计信息展示。
  - `confidence` 仅在后端返回非空时显示；不计算、不推断。
  - 研判操作提供 `确认事件`、`误报`、`无法判定`、`重复`；选择 `误报` 时明确标注「人工复核结论」。
  - 统计区 `误报数` 只在存在真实 disposition 数据时显示，否则显示「暂无复核结论」。
- 权限、空态、错误态、请求代次防旧响应覆盖逻辑保持不变。

- [ ] **Step 1: 写失败测试** — 事件类型与 disposition 标签渲染；`confidence` 缺失时不显示；无 disposition 数据时不显示误报统计；点击「误报」调用带 disposition 的 API；权限不足显示只读态。
- [ ] **Step 2: 跑测试确认失败** — `cd ui && npm test -- SecurityIncidentCenter`，预期 FAIL。
- [ ] **Step 3: 最小实现** — 更新类型/服务/组件/标签映射；不前端推导风险、归并或误报。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(ui): show security event types and disposition"`。

---

### Task 7: 驾驶舱 / 治理 capability chip 接入（fail-closed）

**Files:**
- Modify: `ui/src/components/operations/OperationsBoard.vue`
- Modify: `ui/src/components/operations/OperationsBoard.spec.ts`
- Modify: `ui/src/components/governance/GovernanceCenter.vue`
- Modify: `ui/src/components/governance/GovernanceCenter.spec.ts`
- Modify: `ui/src/services/workflowApi.ts`（`OperationsCapabilities` 类型新增字段）

**Interfaces:**
- `OperationsBoard` 能力条新增安防 chip：`fire-smoke`、`perimeter-intrusion`、`crowding`、`post-absence`、`access-anomaly`、`false-positive-filter`。
  - chip 状态直接来自后端 `securityEventCapabilities`：`AVAILABLE`/`ADAPTED`/`NOT_READY`。
  - 无数据源时固定 `NOT_READY` + 明确原因（如「当前没有 FIRE_SMOKE datasource」）。
  - `false-positive-filter` 仅在存在真实 disposition 数据与 `securityDispositionEnabled` 时解除 `NOT_READY`。
- `GovernanceCenter` 能力面板展示安全事件类型接入状态与 disposition 可用性；未接入保持不可用文案。

- [ ] **Step 1: 写失败测试** — 新 chip 默认 `NOT_READY`；后端返回 `ADAPTED`/`AVAILABLE` 时正确升级；capabilities 缺失/请求失败时回退 `NOT_READY`；误报过滤无数据源时不显示统计。
- [ ] **Step 2: 跑测试确认失败** — `cd ui && npm test -- OperationsBoard GovernanceCenter`，预期 FAIL。
- [ ] **Step 3: 最小实现** — chip 绑定后端状态，禁止前端硬编码 AVAILABLE。
- [ ] **Step 4: 跑测试确认通过** — 同上，预期 PASS。
- [ ] **Step 5: 提交** — `git commit -m "feat(ui): gate security capability chips on real sources"`。

---

### Task 8: 文档、能力映射与浏览器验证

**Files:**
- Create: `docs/issue-62-capability-map.md`
- Create: `docs/issue-62-browser-verification.md`
- Modify: `docs/agent-orchestration.md`（更新 #62 的 NOT_READY 说明，明确模型已支持但数据源未接入）
- Modify: `README.md`（安防能力表格补充事件类型/disposition 边界）

**内容要求：**
- capability map 逐类型列出：`modelSupported`、`sourceConnected`、`productionSource`、chip state、NOT_READY 原因。
- 明确写出：本轮无新增真实 source；`ACCESS_ANOMALY` 为 Demo 适配来源（`ADAPTED`，非生产）；其余四类 `NOT_READY`；`FALSE_POSITIVE` 只来自人工复核或已登记模型。
- 浏览器验证脚本覆盖：驾驶舱 chip 状态、安全事件中心事件类型展示、`误报` 研判动作、无数据时误报统计不出现。

- [ ] **Step 1: 写文档与验证清单**。
- [ ] **Step 2: 启动后端 + 前端，按脚本记录实际结果**（失败即回退修复，不粉饰）。
- [ ] **Step 3: 提交** — `git commit -m "docs: record issue 62 capability map and browser verification"`。

---

### Task 9: 全量回归与验收

- [ ] 后端：`./mvnw.cmd -q test` 全绿（含 `SecurityIncidentArchitectureTest`、`SecurityBoundaryTest`）。
- [ ] 前端：`cd ui && npm test && npm run build` 全绿。
- [ ] 对照 Issue #62 验收标准逐条勾选，确认 fail-closed 与真实源分离。
- [ ] 确认无 seed/mock 新事件类型、无硬编码数量、无 UI/AI 生成的 `FALSE_POSITIVE`。
- [ ] 独立 PR（不混入 #59 编排等无关改动），PR 描述引用本计划与 `docs/issue-62-*.md`。

---

## 验收标准映射

| Issue #62 验收标准 | 本计划对应 |
| --- | --- |
| Security event 类型可扩展 | Task 1（枚举 + raw 映射） |
| 至少一个新增类型有真实 source 端到端；无源则 fail-closed | Task 3/7 + Task 8 文档明确 `NOT_READY` |
| source capability 与 event type support 分离 | Task 3 `SecurityEventCapabilityRegistry` |
| 误报有明确 disposition 模型 | Task 2 + Task 4 |
| FALSE_POSITIVE 不由 UI/阈值凭空生成 | Task 2 校验 + Task 5 服务端强制 `HUMAN_REVIEW` |
| evidence/audit 可追溯 | Task 2 audit 字段 + Task 5 审计 |
| 与 SecurityIncidentCenter/Collaboration/Workflow 集成 | Task 4 + Task 6 |
| 驾驶舱只对真实已接入能力解除 NOT_READY | Task 7 |
| 现有测试全部通过 | Task 9 |
| 独立 PR | Task 9 |

## 风险与缓解

- **兼容性**：`SecurityEvent` 改字段会波及 `SecurityIncidentService` 与测试夹具。缓解：保留旧构造器，Task 5 集中替换 `eventType()` 用法。
- **能力误报**：adapter 自报 `productionSource` 可能被滥用。缓解：registry 默认 `NOT_READY`，且测试断言只有显式声明为生产源的 adapter 才能产生 `AVAILABLE`。
- **误报语义滥用**：前端提供「误报」按钮可能被当成 AI 判定。缓解：UI 明示「人工复核结论」；服务端强制 `HUMAN_REVIEW`；模型来源必须带 ID/版本/证据。
- **范围膨胀**：不做 CV 训练、不伪造视频流、不接完整 VMS、不自动执行高风险动作（Issue 非目标）。
