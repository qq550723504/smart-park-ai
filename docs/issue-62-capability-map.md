# Issue #62 安全事件能力映射

> 最终验证结果（Task 8）。实现见分支 `codex/issue-62-security-event-model`。

## 当前结论

本仓库当前只有一个安防数据来源：`MockSecurityAdapter` -> `MockParkDataStore`，仅提供一条 `SEC-ACCESS-001`（原始类型 `UNAUTHORIZED_ACCESS_ATTEMPT`）。它是确定性 Demo 数据，**不是生产安防数据源**。

因此本轮 Issue 的交付是「模型 + 端口 + capability 分离 + disposition 模型 + 可审计研判」，而不是新增真实接入能力。模型支持（`modelSupported`）与当前部署已接入来源（`sourceConnected` / `productionSource`）由 `SecurityEventCapabilityRegistry` 分开表达，并通过 `GET /api/security/capabilities` 与运营能力快照暴露。

## 事件类型能力

| 标准类型 | 模型支持 | 当前部署已接入来源 | 生产来源 | 驾驶舱状态 | 不可用原因 |
| --- | --- | --- | --- | --- | --- |
| `FIRE_SMOKE` | 是 | 否 | 否 | `NOT_READY` | 当前没有 FIRE_SMOKE datasource |
| `PERIMETER_INTRUSION` | 是 | 否 | 否 | `NOT_READY` | 当前没有 PERIMETER_INTRUSION datasource |
| `CROWDING` | 是 | 否 | 否 | `NOT_READY` | 当前没有 CROWDING datasource |
| `POST_ABSENCE` | 是 | 否 | 否 | `NOT_READY` | 当前没有 POST_ABSENCE datasource |
| `ACCESS_ANOMALY` | 是 | 是（Demo 适配） | 否 | `ADAPTED` | 仅确定性 Demo 来源，非生产接入 |
| `UNKNOWN` | 是 | — | 否 | `NOT_READY` | 无类型化来源；用于未知原始类型兜底 |

原始类型别名（例如 `UNAUTHORIZED_ACCESS_ATTEMPT`）由 `SecurityEventType.fromRaw()` 归一到标准类型并保留在证据 `rawEventType` 中，未知原始值归为 `UNKNOWN`。

## 误报（disposition）能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| disposition 模型 | 已实现 | `UNREVIEWED` / `CONFIRMED_INCIDENT` / `FALSE_POSITIVE` / `INCONCLUSIVE` / `DUPLICATE` |
| `FALSE_POSITIVE` 来源 | 已实现，仅人工复核 | `SecurityDispositionRecord` 强制 `HUMAN_REVIEW`；或已登记自动判定模型（含 `modelId` / `modelVersion` / `evidenceRef` / `decidedAt`） |
| 研判入口 | 已实现 | `POST /api/security/incidents/{incidentId}/review`，可选 body `{ "disposition": "..." }`；缺省 `CONFIRMED_INCIDENT`，`UNREVIEWED` 与未知值返回 400；幂等并写审计 |
| 误报统计 | `NOT_READY` | 当前没有生产数据源，`securityDispositionEnabled=false`；前端在无真实 disposition 时显示「暂无复核结论」，不显示误报数 |

`SecurityDispositionRecord.unreviewed()` 是唯一未复核工厂；已判定记录必须带 `decidedAt`，`FALSE_POSITIVE` 必须带合规来源，避免 UI 标签或阈值凭空生成误报。

## 与既有状态机的集成

- `SecurityIncident` 扩展 `disposition` 与 `dispositionRecord` 两个组件（保留 15 参兼容构造器），仍复用原有 `OPEN` / `REVIEWED` / `HANDOFF` 状态机，没有平行安全事件系统。
- `SecurityIncident.standardEventType()` 提供类型化访问；Web 层 summary 输出标准类型名，原始值保留在证据 `rawEventType`。
- `SecurityIncidentService` 只依赖端口（`SecurityEventReader`、`AlertPort`、`SecurityIncidentHandoffPort`、`Clock`），`SecurityIncidentArchitectureTest` 继续通过。

## 与计划的两处偏离

1. **review 请求体**：计划写的是 `{ disposition, note }`；实现只接受 `{ disposition }`，不接受未校验的自由文本，避免把任意文本持久化进审计。空 body 等价 `CONFIRMED_INCIDENT`。
2. **运行时装配修复**：`SecurityIncidentConfiguration` / `SecurityIncidentWebConfiguration` 原先的 `beanNameFor` 无法解析 `@Bean` 方法定义（`getResolvableType()` 为 `NONE` 且 `getBeanClassName()` 为 null），导致默认运行上下文里 `SecurityIncidentService` / `SecurityIncidentController` 从不注册、`securityIncidentEnabled=false`。本 Issue 增加基于 `ConfigurableListableBeanFactory.getType(name, false)` 的回退，并在 `SmartParkApplicationTest` 加回归断言。该问题在基线提交 `902286d` 已可复现，属既有缺陷。

## 真实性边界

- 不为了点亮 chip 新增 seed/mock 事件类型或硬编码事件数量。
- 没有真实 source/adapter 的类型一律保持 `NOT_READY`。
- `confidence` 仅在来源真实提供或存在明确模型时展示，不由前端推导；当前 Demo 来源未提供 `confidence`，前端不展示。
- 证据摘要必须 `REDACTED:` 前缀、≤512 字符，且不含原始媒体、人脸特征、身份证原文；错误信息不暴露摄像头凭证、内部 URL 或 token。
- 本轮非目标：CV 模型训练、伪造摄像头流、完整 VMS、自动执行高风险现场动作。
