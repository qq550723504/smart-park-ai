# PR #11 P1 根因修复设计

## 1. 状态与范围

- 日期：2026-08-25
- 目标：修复 PR #11 当前 6 条未解决 P1 评论所暴露的设计缺陷。
- 范围：运营分析运行生命周期、专家协作总超时、自然语言时间范围、SQL 计划语义门禁、Supervisor 综合结论。
- 不包含：当前 P2 评论、持久化运行存储、多实例协调、生产级 HA、与本批 P1 无关的 UI 或目录重构。

现有设计文档明确把应用限定为单实例内存运行时。本次继续遵守该边界，但单进程内的状态转换、取消和安全门禁必须确定、原子且可测试。

## 2. 已确认的根因

1. `OperationsAnalysisService` 把 run 状态、活动槽位和待澄清候选拆在多个可变位置，澄清恢复没有原子 compare-and-transition，且待澄清运行没有截止时间。
2. `ExpertCollaborationService` 使用 `CompletableFuture.orTimeout` 完成同一个 future 后再调用 `cancel(true)`，无法可靠取消底层 `runAsync` 任务；执行阶段还能在 timeout 后覆盖终态。
3. `OperationsAnalysisGraph.enforcePlan` 用规范化字符串包含关系验证 SQL 是否执行了计划，不能证明条件位于约束行集的 AST 谓词位置。
4. `QuestionUnderstanding` 没有时间范围字段，导致 `QueryPlan` 总是使用指标默认 lookback，即使问题明确指定“昨天”或“过去 30 天”。
5. `SynthesisValidator` 用正则抽取数字和部分标识符，无法验证自由文本中的定性关系或反义结论。

## 3. 方案比较

### 方案 A：继续逐点补丁

在现有方法上增加 `synchronized`、更多字符串规则和额外关键词校验。改动小，但状态仍分散，SQL 和结论校验继续依赖不可证明的文本启发式，后续 review 会重复发现同类绕过。

### 方案 B：收紧现有边界的显式契约（采用）

在不替换框架的前提下，引入显式状态转换、底层可取消任务句柄、结构化时间范围、JSqlParser AST 计划门禁，以及由已验证 finding 确定性生成的综合结论。公开 REST/SSE 契约保持兼容，新增行为由聚焦测试锁定。

### 方案 C：引入持久化工作流或状态机平台

使用 Temporal、Spring Statemachine 或数据库锁统一生命周期。这能覆盖多实例和进程恢复，但超出当前单实例演示边界，也会把本 PR 扩展为运行平台迁移。

## 4. 运行状态机

运营分析允许的状态转换固定为：

```text
NEW -> RUNNING
RUNNING -> NEEDS_CLARIFICATION | COMPLETED | FAILED
NEEDS_CLARIFICATION -> RUNNING | FAILED(CLARIFICATION_TIMEOUT)
RUNNING -> FAILED(ANALYSIS_TIMEOUT | ANALYSIS_ABORTED | ANALYSIS_INTERRUPTED)
```

实现约束：

- `activeRunId`、当前 `RunRecord` 和 `PendingClarification` 的检查与转换在同一个生命周期锁下提交。
- `PendingClarification` 保存问题、每题候选集合和 `expiresAt`，不再仅保存候选二维列表。
- `submitClarification` 必须在锁内完成状态检查、截止时间检查、候选校验、pending 消费和 `RUNNING` 写入；只有成功提交一次的请求可以启动 graph。
- `start` 和 `submitClarification` 在检查活动槽位前惰性过期已超时的澄清运行。过期运行写入 `FAILED/CLARIFICATION_TIMEOUT`、关闭轨迹并释放槽位，使下一次启动不依赖应用重启。
- graph 的 `contexts` 仍按 `runId` 保存，但原子恢复保证同一 run 不会出现两个并发 context。
- 不改变现有 run ID、状态查询和 SSE URL。

澄清截止时间使用新的正值配置 `smartpark.analytics.clarification-timeout`，默认 5 分钟。它与单次 graph 的 `analysis-timeout` 分离。

## 5. 可取消协作任务

`ExpertCollaborationService` 直接把 `FutureTask<Void>` 提交给 `collaborationRunExecutor`，超时回调持有并取消这个底层句柄：

```text
submit FutureTask -> deadline cancel(true) -> failIfRunning
                                   |
                                   +-> interrupted graph/branch tasks
```

每个异步阶段通过原子 `saveIfRunning` 更新存储：计划、部分 findings、综合完成都不能把 `FAILED` 改回 `RUNNING` 或 `COMPLETED`。分支级 `ExpertCollaborationGraph` 继续持有并取消各自的 `FutureTask`；服务级取消负责完整 run，两层所有权不混用。

## 6. 结构化时间范围

`AnalyticsModelClient.QuestionUnderstanding` 增加可选 `RequestedTimeRange(fromInclusive, toExclusive)`：

- `LlmAnalyticsModelClient` 在理解 prompt 中提供由注入 `Clock` 得到的当前时间和园区时区 `Asia/Shanghai`。
- 模型必须把明确时间表达解析成 ISO-8601 instant；没有时间表达时返回空范围。
- record 构造器拒绝只提供一个边界、乱序边界或空字符串。
- `buildQueryPlan` 优先使用请求范围；仅在范围缺失时使用指标目录的默认 lookback。
- `QueryPlan` 和 SQL 参数共享同一个 `TimeRange` 来源，避免计划展示与真实绑定分叉。

公开问题文本和指标澄清协议保持不变；结构化时间范围只存在于服务端模型边界和查询计划中。

## 7. AST 计划语义门禁

新增 `SqlPlanGuard`，继续复用项目已有的 JSqlParser，不增加 SQL 解析依赖。`SqlAstGuard` 负责通用只读安全，`SqlPlanGuard` 负责证明 SQL 实现当前 `QueryPlan`：

- `MetricDefinition` 明确声明 `timeColumn`，例如 `hour_ts`、`occurred_at`、`snapshot_at`、`stat_date`。
- 从 SELECT AST 获取真实引用视图；字符串常量、别名文本和 SELECT 展示字段不能满足视图要求。
- 只把 WHERE 的顶层合取项视为约束条件；位于字符串、SELECT、HAVING、无关子表达式、`OR ... TRUE` 或 `NOT` 下的内容不能满足计划。
- 每个计划时间列必须分别受到 `:fromTs` 下界和 `:toTs` 上界约束；参数之间的恒真比较不能通过。
- 指标固定条件通过 `CCJSqlParserUtil.parseCondExpression` 解析成 AST，并与 WHERE 顶层合取子树比较，不再搜索 SQL 文本。
- 通用 AST、安全函数白名单、EXPLAIN 成本和只读数据库权限继续作为独立纵深门禁。

`OperationsAnalysisGraph.enforcePlan` 的字符串 `normalize/contains` 实现删除，由 `SqlPlanGuard.validate(validatedSql, plan)` 替代。

## 8. 受 finding 约束的综合结论

Supervisor 模型不再返回可自由改写事实的 `conclusion`。它返回：

```text
status
selectedDomains[]
evidenceRefs[]
confidence
uncertainties[]
```

服务端只允许选择状态为 `SUPPORTED` 的 finding，并按稳定领域顺序把这些 finding 的已验证 `conclusion` 原样组合成最终 `Synthesis.conclusion`。因此 Supervisor 可以选择和披露证据，但不能把 B1 改成 B2、把“正常”改成“异常”，也不能新增定性关系。

`SynthesisValidator` 改为验证结构化选择、证据集合、状态和不确定性，不再使用数字/标识符正则作为事实证明。现有 `Synthesis` 对 Controller/UI 的公开形状保持不变。

## 9. 测试策略

每个生产改动先增加能在当前 HEAD 上失败的回归测试：

1. 两个并发澄清请求只有一个成功启动；过期澄清释放活动槽位。
2. 协作总超时中断底层 run；即使依赖忽略中断后返回，也不能覆盖 `FAILED`。
3. 固定条件藏在字符串、时间参数互相比对的 SQL 被 `SqlPlanGuard` 拒绝；真实列范围和固定条件通过。
4. “昨天”和“过去 30 天”的结构化范围成为实际 `QueryPlan` 与绑定参数；无范围时才使用默认 lookback。
5. Supervisor 提交 B2/反义自由文本不再影响输出；最终结论只由所选已验证 findings 构成。

完成时运行：

```powershell
./mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
```

## 10. 验收条件

- 当前 6 条 P1 的反例均有先红后绿的自动化测试。
- 同一分析 run 不可能并发恢复两次，废弃澄清不会永久阻塞新运行。
- 协作 timeout 取消底层任务，任何晚到结果都不能覆盖终态。
- SQL 只有在真实 AST 谓词约束计划视图、时间列和固定条件时才能执行。
- 用户明确时间范围进入真实 SQL 参数；默认 lookback 只用于未指定时间的请求。
- 综合结论不包含任何未出现在已验证 supported findings 中的断言。
- PR 的公开 API 保持兼容；P2 评论留给独立切片。
