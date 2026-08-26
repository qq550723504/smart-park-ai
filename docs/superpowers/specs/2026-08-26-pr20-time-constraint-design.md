# PR20 时间约束解析根因修复设计

## 1. 状态与范围

- 日期：2026-08-26
- 状态：待实现
- 目标：从根上消除自然语言时间表达式被截断、实体编号被误判为日期，以及解析失败回落默认 lookback 的问题。
- 范围：运营分析问题文本的时间词法识别、有限语法解析、查询计划时间来源和 SQL 计划门禁。
- 不包含：引入通用大模型时间解析替代服务端校验、重写指标目录、修改公开 REST/SSE 字段、持久化运行状态和 UI 重构。

当前 PR20 的 TimeRangeParser 已将日期计算从 Graph 中抽出，但仍有三个根因缺口：

1. 本周三、上月15日会先匹配基础周期，产生错误但合理的范围。
2. MTR-2026-08-01 中的日期片段可能被识别为用户时间。
3. 过去24小时、近12小时没有时间候选，因而回落到指标默认范围。

## 2. 设计不变量

时间约束必须遵循以下不变量：

    NONE            -> 允许使用指标默认 lookback
    PARSED          -> 必须使用服务端解析出的精确范围
    UNSUPPORTED     -> 在生成 SQL 前失败
    MULTIPLE        -> 在生成 SQL 前失败
    AMBIGUOUS       -> 在生成 SQL 前失败

任何用户文本中已识别的显式时间，不得因为解析不完整而进入默认 lookback。模型返回的 requestedTimeRange 只能作为理解结果，不能覆盖服务端对原始问题的时间判断。

## 3. 方案比较

### 方案 A：继续追加正则和 contains 判断

改动最小，但会继续产生前缀截断、重叠表达式和实体内部日期误判。它无法证明一个匹配是完整时间 token，不采用。

### 方案 B：边界感知的有限语法解析器（采用）

使用一个共享的文本 token 边界扫描器识别候选 span，再由有限语法解析器计算 QueryPlan.TimeRange。解析结果携带明确状态和原始 span。它不依赖模型作为安全边界，也不需要引入重量级 NLP 依赖，适合当前受控的园区分析语法。

### 方案 C：交给模型返回完整时间范围

可以覆盖更多口语表达，但模型漏解析、幻觉范围和实体误判仍然存在，不能作为 SQL 安全门禁。

## 4. 组件与职责

### 4.1 QuestionTokenScanner

新增共享的文本扫描边界，供 TimeRangeParser 和 QueryPlan 使用：

- 识别实体 identifier 的完整 span；
- 识别时间候选的完整 span；
- 日期样式若处在字母、数字、下划线或连字符组成的 identifier 内，不得作为时间候选；
- 不通过无界 find 把任意日期子串当作用户时间；
- 输出原始文本位置和 token 文本，便于错误信息和审计事件引用。

现有 QueryPlan.ENTITY_IDENTIFIER 与时间解析器中的重复边界逻辑合并到该共享组件，避免两个规则逐渐漂移。

### 4.2 TimeRangeParser

解析器只接收问题文本、服务端 Clock 当前时刻和园区时区，返回结构化结果：

    TimeParseResult {
        Status status;
        List<TimeMention> mentions;
        QueryPlan.TimeRange range;
        String reason;
    }

    enum Status {
        NONE, PARSED, UNSUPPORTED, MULTIPLE, AMBIGUOUS
    }

解析顺序必须优先完整表达式，再匹配基础表达式：

1. 日期区间；
2. 完整日期；
3. 年月、年份、月份；
4. 带限定的周期；
5. 相对时长；
6. 无限定基础周期。

这样本周三只能形成一个完整 mention，不会被本周抢先消费。

### 4.3 TimeConstraintResolver

将解析结果转换为查询计划输入：

- PARSED：写入明确的时间范围和 EXPLICIT_USER_RANGE 来源；
- NONE：在指标解析完成后使用默认 lookback，并标记 DEFAULT_METRIC_LOOKBACK；
- 其他状态：终止分析，不调用 SQL 生成模型。

Graph 不再自行判断“是否显式时间”，也不再直接在问题文本上做 contains 判断。

## 5. 支持语法与语义

第一阶段固定支持以下表达式，并为每类增加表驱动测试：

### 绝对日期

- 2026-08-01 到 2026-08-05
- 2026年8月1日到2026年8月5日
- 2026-08-01
- 2026年8月1日
- 2026年8月
- 2026年
- 8月

### 相对时长

- 过去24小时、近12小时
- 过去两天、过去两周
- 最近一个月
- 近两个季度

小时使用基于当前 Instant 的精确时长；日、周使用固定时长；月、季度使用园区时区的日历减法。

### 限定周期

- 本周三、上周三、上周末
- 本月15日、上月15日
- 本周、上周、本月、上月
- 本季度、上季度
- 今年、去年、上半年、下半年

日期不合法、限定日超出月份范围或表达式顺序无效时返回 UNSUPPORTED，不得自动修正或回落默认范围。

## 6. 查询计划来源约束

QueryPlan 增加 `TimeRangeSource timeRangeSource` 记录组件，并保留现有六参数构造器作为兼容构造器；兼容构造器默认标记 `DEFAULT_METRIC_LOOKBACK`，生产 Graph 必须显式传入真实来源：

    enum TimeRangeSource {
        EXPLICIT_USER_RANGE,
        DEFAULT_METRIC_LOOKBACK
    }

计划构建必须保证：

- 显式时间来源只能来自 TimeRangeParser.PARSED；
- 默认来源只能来自 TimeRangeParser.NONE；
- SQL 参数、计划展示和执行器使用同一个 TimeRange 对象；
- SqlPlanGuard 校验 SQL 的时间列上下界与计划范围来源一致；
- 模型返回时间范围但服务端原文没有时间表达时，继续 fail-closed。

## 7. 失败处理与可观测性

失败结果保留：

- 状态：UNSUPPORTED、MULTIPLE 或 AMBIGUOUS；
- 原始 mention；
- 失败原因；
- 原文 span。

执行事件和 API 错误中只展示安全的表达式和原因，不输出模型原始 prompt 或敏感实体值。查询计划可展示：

    timeRangeSource=EXPLICIT_USER_RANGE
    timeExpression=过去24小时

## 8. 测试策略

按 TDD 逐项增加测试，并先确认当前实现失败：

1. 本周三不得变成当前周至今；
2. 上月15日计算为上月指定自然日；
3. 过去24小时、近12小时使用准确小时范围；
4. MTR-2026-08-01表计的能耗不产生时间范围；
5. 同时出现两个时间范围时返回 MULTIPLE；
6. 未支持的显式时间返回 UNSUPPORTED，且 SQL 生成调用次数为 0；
7. 无时间表达式时才使用默认 lookback；
8. 日期边界、闰年、园区时区和非法日期均有测试；
9. 计划、SQL 绑定参数和执行器使用同一时间范围来源。

完成时运行：

    ./mvnw.cmd -B test
    Push-Location ui
    npm.cmd run test:unit
    npm.cmd run build
    Pop-Location
    git diff --check

## 9. 交付与 PR 评论

- 先提交解析器根因修复和聚焦回归测试；
- 远端 CI 通过后，逐条在原 review thread 回复实际修复范围；
- 只有当前代码和测试已经覆盖评论场景，且评论不再适用于最新提交时，才标记 thread resolved；
- 不把 outdated 当作“已修复”的证据；
- 不合并 PR，不部署生产。

## 10. 验收条件

- 显式时间表达式不会被截断或回落默认 lookback；
- 日期形实体 identifier 不会污染时间约束；
- 时间解析失败在 SQL 生成前终止；
- 计划、SQL 参数和执行时间范围只有一个来源；
- PR20 当前三个未解决评论均有对应回归测试和明确处理结果；
- 公开 API 兼容，工作树无无关变更。
