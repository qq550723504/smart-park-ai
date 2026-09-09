# Issue #69 客户框架与园区总览能力映射

本映射以 `01-park-overview.png` 为视觉基准；设计图中的数值、日期、楼宇名称和按钮不作为 API 契约。

| 展示字段或动作 | 当前来源 | 展示约束 |
| --- | --- | --- |
| 最近 24 小时园区能耗、趋势、楼宇分布 | `GET /api/operations/energy-time-series` | 按现有 B1/B2/B3 园区目录查询完整园区，而不是使用异常接口的受影响楼宇清单；`PARTIAL` 明示部分观测；缺失时段保留断点，不补零。 |
| 受影响楼宇 | `GET /api/operations/anomaly-overview` 的 `affectedBuildingCount` | 任一数据域为 `PARTIAL` / `UNAVAILABLE` 时明示“部分数据域可用”。 |
| 待处理事件 | `anomaly-overview.breakdowns.statuses[OPEN]` | 不使用总告警数冒充待处理数。 |
| 人工服务请求 | `GET /api/operations/metrics` 的 `humanTicketCount` | 表示当前运行实例累计，不外推为生产规模；该数据域独立加载，不阻塞园区总览。 |
| AI 今日关注 | `anomaly-overview?status=OPEN` 的高风险告警、离线设备、能耗偏差 | 仅将 OPEN 告警计入可操作关注，同时保留设备和能耗事实；做确定性运营规则排序并标注“未调用模型”，不生成原因结论。 |
| 园区楼宇名称和坐标 | 现有演示迁移 `V3__add_operations_visualization_demo_data.sql` 的 B1/B2/B3 | 只用于统一演示空间语义；未知 ID 原样显示，空间画面标注为非实时示意。 |
| 我的待办 | `GET /api/collaboration/work-items`，只读 `CUSTOMER_AGENT` 演示视角 | 最多读取 50 条 SLA 排序结果，过滤完成、关闭、取消、拒绝和失败等终态后展示前 4 条；本项不审批、不更新工单；该数据域独立加载，失败和空态分别展示。 |
| 最新事件 | `GET /api/operations/anomaly-evidence/{buildingId}` | 合并后端返回的告警、设备和能耗安全摘要，按各自事实时间排序后展示前 5 条；与当前所选楼宇及同一查询窗口绑定。 |
| 楼宇卡片 / 地图标注选择 | 前端状态 + 同一 `buildingId` | 只更新总览上下文和事件证据，不跳转尚未实现的客户分析页。 |
| 运营分析、事件与工单、运营报告、AI 助手导航 | #70、#71、#72、#73 | 本项仅登记统一导航契约；渲染为不可交互的计划项，不创建空页、死按钮或技术台跳转。 |
| 内部工作台入口 | 现有 `App.vue` 的 `showcase` / `workbench` 长生命周期切换 | 继续通过现有 `enter-workbench` 事件进入；返回和再次进入不重复挂载活动工作台。 |

## 设计差异

- 原图“设备运行率”需要完整设备分母，现有 API 只提供离线数，无法严谨计算，因此替换为现有 `affectedBuildingCount`。
- 原图天气、通用搜索和实时日期不接入；当前范围没有对应可靠数据源。
- 原图中的“查看分析”“生成简报”等入口属于后续 Issue，本项不显示可点击的伪入口。
- 建筑横幅、园区鸟瞰和低碳插画为本次依据批准稿生成的独立视觉素材；不承载业务事实。
