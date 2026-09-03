# 运营异常雷达与告警证据链设计

## 1. 目标

在现有运营看板、运营分析指标和统一执行轨迹之上，增加一个可演示、可下钻的“异常雷达”切片：先用真实的只读聚合数据突出当前运营异常，再把选中的楼宇或异常项展开为告警、设备、能耗和执行轨迹证据链。

首版优先服务于方案展示和运营人员研判，不引入模型预测、设备控制或新的审批状态机。已有告警分析入口继续保留；异常雷达只是一个跨域只读投影，不能替代各领域事实模型。

## 2. 范围与非目标

### 2.1 本次范围

- 在 `/api/operations` 下新增异常总览和楼宇证据查询能力。
- 聚合告警数量、高风险告警、风险/类别/状态分布、离线设备、设备类型分布、异常楼宇和能耗偏差。
- 在 `OperationsBoard` 首屏增加异常雷达卡片、筛选入口和异常楼宇排行。
- 点击异常项打开“异常证据链”抽屉，展示安全 DTO 中允许的告警、设备、能耗和执行轨迹摘要。
- 支持从雷达筛选跳转到现有 `OperationsAnalysisPage`，并复用 `ExecutionTraceRail` 查看处置轨迹。
- 覆盖权限、时间范围、空数据、部分域失败、响应脱敏和前端交互测试。

### 2.2 非目标

- 不在前端写死或推算“实时”业务数字，不生成未经定义的综合健康分或风险分。
- 不把告警、设备、能耗直接拼成未经验证的跨域 SQL Join；各域先独立读取，再在应用层按 `buildingId` 合并。
- 不复制安全事件中心的事件模型、归并规则或审批动作；安全事件只提供授权后的跳转/摘要。
- 不新增关闭告警、派单、通知、设备控制、预测性维护或模型推理能力。
- 不返回原始告警证据、个人信息、视频/音频内容或未脱敏自由文本。

## 3. 架构

### 3.1 分层与依赖

```text
AlertAnalyticsReader + DeviceAnalyticsReader + EnergyAnalyticsReader
                         + WorkflowExecutionStore/WorkflowEventPublisher
                         ↓
              OperationsAnomalyService
        ├─ 各域独立查询与口径校验
        ├─ 应用层按 buildingId 合并
        └─ 组装安全、稳定排序的只读 DTO
                         ↓
               OperationsController
                         ↓
                 OperationsBoard
              ├─ 异常雷达卡片
              ├─ 筛选/分析跳转
              └─ 证据链抽屉 + 执行轨迹
```

优先扩展现有 `OperationsController`，避免为同一 `/api/operations` 资源再增加 Controller。聚合逻辑放在 operations 应用服务中，不放在 Controller、Vue 组件或具体 Mock 适配器中。现有 `OperationsAnalysisService` 仍负责自然语言分析，本功能只消费其可复用的指标口径和执行事件元数据。

现有 `AlertPort`、`DevicePort`、`EnergyPort` 分别面向告警工作流、设备单体查询和最新能耗读取，方法粒度不足以支撑本功能的时间窗/楼宇聚合。因此本次不扩展这三个业务端口，而是新增窄接口 `AlertAnalyticsReader`、`DeviceAnalyticsReader`、`EnergyAnalyticsReader`，由只读分析适配器实现并读取现有 `analytics.v_alert_fact`、`analytics.v_device_snapshot`、`analytics.v_energy_hourly` 视图。这样既保留领域操作边界，也避免在 Controller 或前端拼接事实数据。执行轨迹只通过现有 `WorkflowExecutionStore`/`WorkflowEventPublisher` 读取已存在的运行元数据；没有关联运行时只显示“暂无执行轨迹”。

### 3.2 数据口径

- 默认时间窗为过去 7 天；请求明确传入 `from`/`to` 时使用请求范围，并在响应中原样返回规范化后的时间窗。
- 告警数量按 `AlertAnalyticsReader` 的 `occurredAt` 过滤；高风险按既有风险枚举过滤。
- 离线设备沿用设备快照的“当前/最近 1 天”口径，响应必须带 `asOf`，不得伪装为 7 天历史趋势。
- 能耗异常使用已有 `energy_deviation_pct` 定义；无法取得目标或偏差时返回“不可用”，不补零。
- 异常楼宇为告警、高风险告警、离线设备或能耗偏差任一信号命中的楼宇并集；楼宇排名按明确的主排序指标及 `buildingId` 稳定排序。
- 不将多个指标压缩成一个未经产品定义的“异常分数”。

### 3.3 API 契约

`GET /api/operations/anomaly-overview?from=&to=&buildingId=&riskLevel=&category=&status=&deviceType=`

响应至少包含：

```json
{
  "window": { "from": "...", "to": "...", "timezone": "Asia/Shanghai" },
  "asOf": "...",
  "summary": {
    "alertCount": 0,
    "highRiskAlertCount": 0,
    "offlineDeviceCount": 0,
    "affectedBuildingCount": 0
  },
  "breakdowns": {
    "riskLevels": [],
    "categories": [],
    "statuses": [],
    "deviceTypes": []
  },
  "buildings": [],
  "domainStatus": { "alerts": "OK", "devices": "OK", "energy": "OK" }
}
```

`GET /api/operations/anomaly-evidence/{buildingId}?from=&to=`

响应包含楼宇标识、各域摘要、最近若干条安全引用、能耗偏差摘要以及可跳转的 `executionRunId`/`eventId`。引用只返回稳定 ID、时间、类型、风险和脱敏摘要；不存在的域返回 `UNAVAILABLE`，不影响其他域展示。

参数校验失败返回 `400`；未授权角色返回 `403`；聚合超时或适配器失败返回 `200` 加 `domainStatus` 和局部空数组，只有整体无法构造响应时才返回 `503`。响应不暴露适配器异常堆栈。

## 4. 前端组件与交互

- `OperationsBoard` 增加 `anomalyOverview` 加载状态、时间窗和筛选状态；保留现有 14 个受控分析问题作为下钻入口。
- 首屏用四个事实卡片展示总量，用分布条/排行展示构成；卡片标题必须带口径（例如“近 7 天告警”“最近 1 天离线设备”）。
- 点击风险、类别、状态或楼宇排行时：优先更新雷达筛选；点击“查看分析”则把已规范化筛选转换为现有受控问题并进入 `OperationsAnalysisPage`。
- 点击楼宇打开证据链抽屉；抽屉显示加载、部分失败、无数据和权限态，并提供“打开执行轨迹”和“进入分析”操作。
- 安全事件仅展示允许的摘要和跳转，不在抽屉中复制安全事件研判操作。
- 首屏不使用地图坐标；当前告警/设备视图没有稳定坐标，后续若补齐坐标再单独增加楼宇地图。

## 5. 错误处理与安全

- 所有输入时间统一解析并限制最大查询窗口，防止任意大范围扫描。
- 按角色复用运营分析的读取权限；证据详情遵循现有安全事件可见性规则。
- DTO 使用白名单字段和长度限制；自由文本统一脱敏，禁止把原始 payload 透传到浏览器。
- 各域查询独立计时和记录结构化失败原因；前端以“数据暂不可用”展示，不把失败显示为零。
- 聚合结果只读、无副作用，可安全重复请求；不写入新的业务状态。

## 6. 测试策略

### 后端

- 各域独立聚合、时间边界、风险/类别/状态筛选和稳定排序测试。
- 楼宇并集去重、能耗不可用、离线设备口径和 `domainStatus` 部分失败测试。
- DTO 脱敏、字段白名单、角色权限、参数校验和整体 `503` 测试。
- Controller 契约测试覆盖成功、空数据、部分失败、未授权和非法时间范围。

### 前端

- 雷达卡片、分布、排行和口径文案渲染测试。
- 筛选联动、分析跳转参数、证据抽屉打开/关闭和执行轨迹跳转测试。
- 加载、空态、部分失败、权限态和重试测试。
- 继续运行现有 `npm run test:unit`、`npm run typecheck`、`npm run build`。

## 7. 分阶段交付

1. 先完成只读 DTO、聚合服务、Controller 和契约测试。
2. 接入 `OperationsBoard` 异常雷达与筛选联动。
3. 增加证据链抽屉和执行轨迹跳转。
4. 补充 README、架构文档和演示验收记录。

后续可在数据历史和模型评估充分后另立设计，增加预测性维护或异常趋势预测；不纳入本次切片。
