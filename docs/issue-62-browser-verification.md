# Issue #62 浏览器与能力端点验证

验证日期：2026-09-14
分支：`codex/issue-62-security-event-model`

## 环境

- 后端：`http://localhost:18080`，`SPRING_AI_DASHSCOPE_ENABLED=false`，使用仓库内 Mock 适配器与确定性 Demo 数据。
- 前端：Vite dev server `http://localhost:5173`，`VITE_API_PROXY_TARGET=http://localhost:18080`。
- 浏览器：本机 Chrome（headless + CDP），脚本通过 `[data-enter-workbench]` 进入内部工作台，再按 `[data-workbench-view]` 切换页面。
- 演示角色：工作台默认 `ADMIN`（`OperationsWorkbench.vue` 中 `role = ref('ADMIN')`），因此可以读取安全事件研判与运营能力。

## 验证前提（本轮修复的阻塞）

在基线提交 `902286d` 上，默认运行上下文里 `SecurityIncidentService` / `SecurityIncidentController` 从不注册，`GET /api/operations/capabilities` 返回 `securityIncidentEnabled=false`，安全事件中心在导航中不可达。定位为 `SecurityIncidentConfiguration` / `SecurityIncidentWebConfiguration` 的 `beanNameFor` 无法解析 `@Bean` 方法定义（`getResolvableType() == ResolvableType.NONE`，`getBeanClassName() == null`）。本 Issue 增加 `ConfigurableListableBeanFactory.getType(name, false)` 回退后，默认运行恢复正常，`securityIncidentEnabled=true`，并新增 `SmartParkApplicationTest` 回归断言。

## 能力端点

`GET /api/security/capabilities`（`X-Demo-Role: ADMIN`）：

- 按枚举声明序返回 6 个类型；
- `ACCESS_ANOMALY` = `ADAPTED`（`sourceConnected=true`、`productionSource=false`）；
- `FIRE_SMOKE` / `PERIMETER_INTRUSION` / `CROWDING` / `POST_ABSENCE` / `UNKNOWN` = `NOT_READY`；
- `dispositionEnabled=false`。

`GET /api/operations/capabilities`：`securityIncidentEnabled=true`、`securityEventCapabilities` 6 条、`securityDispositionEnabled=false`。

无角色或 `CUSTOMER_AGENT` 访问 `/api/security/capabilities` 返回 403，读/研判权限仍只对 `APPROVER` / `ADMIN` 开放。

## 安全事件中心

进入「安全事件研判」后（页面无预置数据，唯一事件来自 Mock 来源）：

- 事件类型展示为标准类型标签「门禁异常」（`data-security-event-type` = `ACCESS_ANOMALY`）。
- 证据来源展示原始类型与来源类型：「`UNAUTHORIZED_ACCESS_ATTEMPT` · 未知来源」（`data-security-evidence-source`），说明原始类型被保留而非被 UI 改写。
- 未研判时 `data-security-disposition` = 「未复核」，`data-security-false-positive` = 「暂无复核结论」；不会出现误报数。
- 研判动作齐全：确认事件、误报、无法判定、重复事件；「转为协同工作项」在未研判时为「请先记录研判」。
- 页面文本不包含 `base64`、`data:image`、`face embedding` 等原始媒体痕迹。

点击「误报（人工复核结论）」后：

- `data-security-disposition` = 「误报（人工复核结论） · HUMAN_REVIEW」；
- `data-security-false-positive` = 「1 误报」（仅因为存在真实人工 disposition）；
- 「转为协同工作项」可用，复用既有 handoff 流程；
- 后续重复研判幂等，保留第一次的 `FALSE_POSITIVE` 与 `dispositionDecidedAt`。

## 能力条（运营看板与治理中心）

运营看板 `data-security-capabilities` 与治理中心 `data-governance-security-capabilities` 均逐类型展示：

| `data-security-capability` | `data-feature-state` | 文案 |
| --- | --- | --- |
| `FIRE_SMOKE` | `NOT_READY` | 模型已定义该类型，但当前部署未接入数据源 |
| `PERIMETER_INTRUSION` | `NOT_READY` | 同上 |
| `CROWDING` | `NOT_READY` | 同上 |
| `POST_ABSENCE` | `NOT_READY` | 同上 |
| `ACCESS_ANOMALY` | `ADAPTED` | 仅有非生产适配数据，不参与统计 |
| `UNKNOWN` | `NOT_READY` | 同上 |

两处 `data-security-disposition` / `data-governance-security-disposition` 均为 `NOT_READY`，文案「缺少生产数据源，误报数不可统计」，页面不出现 `误报: <数字>` 形式的统计。安全能力条独立于 `analyticsAvailable` 展示，避免在分析未启用时隐藏安防状态。

## 研判接口边界

- `POST /api/security/incidents/{id}/review`，body `{"disposition":"UNREVIEWED"}` → 400；
- body `{"disposition":"AI_GUESS"}` → 400；
- `CUSTOMER_AGENT` → 403；
- 空 body 或无 body → 默认 `CONFIRMED_INCIDENT`；
- 已研判事件再次研判 → 幂等，返回既有 disposition。

## 未验证 / 边界

- 本轮没有真实生产安防数据源，因此没有任何类型进入 `AVAILABLE`，`securityDispositionEnabled` 始终为 `false`；这是预期的 fail-closed 结果，不是缺陷。
- 没有 CV 推理解读、视频流或完整 VMS；浏览器验证只覆盖模型、端口、capability 状态、研判动作与脱敏展示。
- 截图在本地会话中用于人工比对，未提交到仓库（避免二进制产物进入版本库）。
