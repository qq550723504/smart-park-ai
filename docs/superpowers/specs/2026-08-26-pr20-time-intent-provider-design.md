# PR20 时间意图提供器设计

## 1. 背景与目标

当前 `TimeRangeParser` 通过有限正则直接扫描自然语言。每增加一种中文表达，就需要继续追加规则；更危险的是，基础时间词可能先被匹配，而后面的限定词被静默忽略，例如 `今天上午` 只匹配 `今天`。

本设计将“识别自然语言时间”和“计算、校验查询时间范围”分离。查询图谱只接受经过校验的结构化 `TimeIntent`，未知、歧义或存在未消费限定词的表达式必须在生成 SQL 前失败，不得回落默认 lookback。

## 2. 非目标

- 不承诺覆盖所有中文自然语言表达。
- 不让大模型直接提供最终 `Instant` 并绕过服务端校验。
- 不在本切片中引入一个未经运行验证的外部解析服务。
- 不修改公开 REST/SSE 响应结构。

## 3. 核心不变量

```text
NONE        -> 允许指标默认 lookback
PARSED      -> 只能使用服务端根据 TimeIntent 计算的精确范围
UNSUPPORTED -> 在生成 SQL 前失败
MULTIPLE    -> 在生成 SQL 前失败
AMBIGUOUS   -> 在生成 SQL 前失败
```

一个时间意图必须完整消费它覆盖的文本 span。若 `今天` 被识别但 `上午` 没有被同一意图消费，结果不能是 `PARSED(今天)`，只能是 `UNSUPPORTED` 或 `AMBIGUOUS`。

## 4. 组件边界

### 4.1 `TimeIntentProvider`

新增可替换接口，输入原始问题、服务端当前时间和园区时区，输出 `TimeIntentResult`：

- `status`：`NONE`、`PARSED`、`UNSUPPORTED`、`MULTIPLE`、`AMBIGUOUS`；
- `mentions`：原始时间 span、起止位置和安全错误原因；
- `intent`：结构化时间语义，不保存模型生成的最终时间戳；绝对日期区间必须保存 `fromDate` 和 `toDate` 两个端点；
- `timeRange`：由服务端根据 `intent` 和 `Clock` 计算的范围。

图谱只依赖该接口和结果状态，不再直接调用正则、`contains` 或模型时间戳。

### 4.2 当前实现

先将现有规则实现收敛为 `FiniteGrammarTimeIntentProvider`，其职责是：

1. 先匹配最长、完整的组合表达式；
2. 记录所有候选 span；
3. 检查时间限定词是否全部被某个候选消费；
4. 排除实体 identifier 中的日期片段；
5. 只用 `java.time` 根据结构化意图计算范围。

未来如果引入 Duckling 或其他开源解析器，只增加新的 provider adapter，不改变图谱和 `QueryPlan` 契约。Duckling 的中文能力仍属于实验性质，因此必须先通过项目语料回归集，不能直接作为 SQL 安全边界。

## 5. 第一阶段语法

第一阶段只保证以下表达式有确定行为：

- `今天上午`、`今天下午`：按园区本地日分段计算；
- `本月15日`、`本月15号`、`上月15日`、`上月15号`；
- `过去24小时`、`过去24个小时`、`近12小时`；
- `今年上半年`、`今年下半年`、`2026年上半年`、`2026年下半年`；
- 已有绝对日期、月份、年份、周/月/季度和年级别滚动范围。

未列入语法的时间表达式不能被基础词前缀吞掉；必须返回 `UNSUPPORTED`。

## 6. 图谱数据流

```text
question
  -> TimeIntentProvider.resolve
  -> TimeIntentResult
  -> TimeConstraintResolver
  -> QueryPlan(timeRange, timeRangeSource)
  -> SQL generation
```

`QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE` 只能来自 `PARSED`；`DEFAULT_METRIC_LOOKBACK` 只能来自 `NONE`。模型的时间字段只能作为理解信息，不能覆盖服务端解析结果。

## 7. 测试策略

- 表驱动测试覆盖所有第一阶段表达式和服务端时区边界；
- 测试 `今天上午` 不得退化为整天，`本月15号` 不得退化为整月；
- 测试 `过去24个小时` 和年/半年组合；
- 测试任意已识别基础词后残留时间限定词时返回 `UNSUPPORTED`；
- 测试实体 `MTR-2026-08-01` 不产生时间 mention；
- 图谱测试确认 `UNSUPPORTED`、`MULTIPLE` 不调用 SQL 生成模型；
- 图谱测试确认 `NONE` 才能使用默认 lookback；
- 保留 `QueryPlan`、SQL 参数和执行器的同一时间范围来源断言。

## 8. 交付边界

本切片只重构时间意图边界和 PR20 评论覆盖，不合并 PR、不部署生产。完成后重新检查所有当前 review threads，只有最新代码和测试确实覆盖评论场景时才标记解决。
