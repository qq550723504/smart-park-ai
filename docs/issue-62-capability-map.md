# Issue #62 安全事件能力映射

> 初始基线（Task 0）。实现完成后由 Task 8 更新为最终验证结果。

## 当前结论

本仓库当前只存在一个安防数据来源：`MockSecurityAdapter` -> `MockParkDataStore`，仅提供一条 `SEC-ACCESS-001`（原始类型 `UNAUTHORIZED_ACCESS_ATTEMPT`）。它是确定性 Demo 数据，**不是生产安防数据源**。

因此本轮 Issue 的交付是「模型 + 端口 + capability 分离 + disposition 模型」，而不是新增真实接入能力。

## 事件类型能力

| 标准类型 | 模型支持 | 当前部署已接入来源 | 生产来源 | 驾驶舱状态 | 不可用原因 |
| --- | --- | --- | --- | --- | --- |
| `FIRE_SMOKE` | 是 | 否 | 否 | `NOT_READY` | 当前没有 FIRE_SMOKE datasource |
| `PERIMETER_INTRUSION` | 是 | 否 | 否 | `NOT_READY` | 当前没有 PERIMETER_INTRUSION datasource |
| `CROWDING` | 是 | 否 | 否 | `NOT_READY` | 当前没有 CROWDING datasource |
| `POST_ABSENCE` | 是 | 否 | 否 | `NOT_READY` | 当前没有 POST_ABSENCE datasource |
| `ACCESS_ANOMALY` | 是 | 是（Demo 适配） | 否 | `ADAPTED` | 仅确定性 Demo 来源，非生产接入 |
| `UNKNOWN` | 是 | — | 否 | `NOT_READY` | 无类型化来源；用于未知原始类型兜底 |

## 误报（disposition）能力

| 能力 | 状态 | 说明 |
| --- | --- | --- |
| disposition 模型 | 计划支持 | `UNREVIEWED` / `CONFIRMED_INCIDENT` / `FALSE_POSITIVE` / `INCONCLUSIVE` / `DUPLICATE` |
| `FALSE_POSITIVE` 来源 | 计划仅人工复核 | 或已登记模型（含 `modelId` / `modelVersion` / `evidenceRef`） |
| 误报统计 | `NOT_READY` | 当前没有真实 disposition 数据，不显示误报数 |

## 真实性边界

- 不为了点亮 chip 新增 seed/mock 事件类型或硬编码事件数量。
- 没有真实 source/adapter 的类型一律保持 `NOT_READY`。
- `confidence` 仅在来源真实提供或存在明确模型时展示，不由前端推导。
- 本轮非目标：CV 模型训练、伪造摄像头流、完整 VMS、自动执行高风险现场动作。
