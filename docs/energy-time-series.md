# 能耗时序契约

`GET /api/operations/energy-time-series` 为驾驶舱和 Operations Analysis 提供同口径的只读能耗时序。

## 数据口径

- 来源系统：`OPERATIONS_ANALYTICS`。
- 底层事实：按楼宇、电表、小时记录的 `kwh`、`baseline_kwh` 和 `peak_kw`。
- `energy_kwh`：按楼宇和请求 bucket 对已存在的 `kwh` 求和，单位来自指标注册表（`kWh`）。
- `energy_baseline_kwh`：按相同楼宇和请求 bucket 对已存在的 `baseline_kwh` 求和，单位为 `kWh`；用于与实际用电做同源对比，不在客户端倒算。
- `energy_deviation_pct`：按同一 bucket 使用注册口径 `(SUM(kwh)-SUM(baseline_kwh))/SUM(baseline_kwh)`，单位 `%`。
- `HOUR` 直接使用小时事实；`DAY` 只对小时事实做聚合，不插值、不生成更细数据。
- 时区来自现有分析视图的登记口径，固定为 `Asia/Shanghai`；客户端不能覆盖或猜测。若未来迁移事实时区，必须同时迁移视图分桶口径与该契约，不能只改返回字符串。
- `asOf` 是本次返回事实中的最大观测时间，不是响应生成时间。

仓库迁移包含确定性 demo seed，analytics Compose 还会为本地演示刷新这些夹具。它们不是生产遥测。真实部署必须关闭 demo refresh 并向同一事实表接入真实采集数据。API 无论运行模式都只绘制数据库实际返回的点。

## 请求

```text
GET /api/operations/energy-time-series
  ?buildingIds=B1,B2
  &metric=energy_kwh
  &from=2026-09-01T00:00:00Z
  &to=2026-09-03T00:00:00Z
  &granularity=HOUR
```

`buildingIds` 必填且只接受受控楼宇标识。`from`/`to` 可省略；默认查询最近五天、截至当前已完成小时的窗口。显式边界必须与粒度对齐。查询结果最多 500 个“楼宇 × bucket”点，最长窗口 31 天；超出时在访问数据源前拒绝。

## 响应示例

```json
{
  "metric": "energy_kwh",
  "unit": "kWh",
  "timezone": "Asia/Shanghai",
  "window": {
    "from": "2026-09-01T00:00:00Z",
    "to": "2026-09-01T02:00:00Z",
    "granularity": "HOUR"
  },
  "status": "PARTIAL",
  "series": [
    {
      "buildingId": "B1",
      "points": [
        { "timestamp": "2026-09-01T00:00:00Z", "value": 123.4 }
      ],
      "missingTimestamps": ["2026-09-01T01:00:00Z"]
    }
  ],
  "asOf": "2026-09-01T00:00:00Z",
  "source": {
    "system": "OPERATIONS_ANALYTICS",
    "metricDefinition": "energy_kwh",
    "status": "PARTIAL"
  },
  "evidence": [
    {
      "buildingId": "B1",
      "expectedPointCount": 2,
      "actualPointCount": 1,
      "firstObservedAt": "2026-09-01T00:00:00Z",
      "lastObservedAt": "2026-09-01T00:00:00Z"
    }
  ]
}
```

## 可用性

- `AVAILABLE`：每个请求楼宇的所有 bucket 都有真实聚合值。
- `PARTIAL`：至少有一个真实点，但存在缺失 bucket 或受控执行器报告截断；返回真实点和明确缺口。
- `UNAVAILABLE`：数据源不可用，或所有请求楼宇在窗口内都没有数据；`series` 为空，前端不绘图。

当前完整性只能判断楼宇 bucket 是否存在。数据模型没有登记“每栋楼应有多少电表”的历史清单，因此不能声称识别单个 bucket 内的电表级缺失；这是后续接入真实采集治理时需要补齐的元数据边界。

## 安全

客户端不能提交 SQL。服务端仅从指标注册表和闭集粒度生成固定查询，楼宇与窗口全部参数化；查询依次通过 AST 白名单、EXPLAIN 成本门禁和只读执行器。公共响应和错误不包含 relation、SQL、JDBC URL、host、credential 或 stack trace。
