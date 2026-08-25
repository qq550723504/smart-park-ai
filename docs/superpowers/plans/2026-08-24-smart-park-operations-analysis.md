# 智慧园区自然语言运营分析实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 让运营人员用自然语言完成真实、可审计的只读 PostgreSQL 分析，并得到表格、图表与基于结果的结论。

**Architecture:** 复用 Spring AI Alibaba SQL Agent 的 `list_tables -> get_schema -> run_query` 工作流思想，但应用自有指标目录、AST 安全门和数据库只读角色构成硬边界。Graph 节点显式分离意图、计划、SQL、校验、成本、执行、图表与总结。

**Tech Stack:** Spring AI Alibaba Graph 2.0, ChatModel, PostgreSQL, Flyway, JSqlParser, JDBC, Testcontainers PostgreSQL, Apache ECharts, Vue 3, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Depends on:** 2.0 基线与统一执行事件计划完成。

**Global constraints:** 只执行真实 SQL；禁止 mock/fallback；仅允许 SELECT/只读 CTE；仅允许 `analytics` 白名单视图与函数；所有时间值绑定参数；最多 500 行、1 MiB、3 秒；最多一次模型修复；总结只能引用查询结果；数据库账号自身必须只读。

## 文件结构与职责

- `pom.xml`：PostgreSQL、Flyway、JSqlParser、Testcontainers 测试依赖。
- `src/main/resources/db/migration/*`：analytics schema、视图、demo 数据和只读角色授权。
- `src/main/java/com/example/smartpark/analytics/model/*`：Metric、QueryPlan、ValidatedSql、TabularResult、ChartSpec、AnalysisRun。
- `src/main/java/com/example/smartpark/analytics/catalog/*`：指标与允许 schema 的唯一事实源。
- `src/main/java/com/example/smartpark/analytics/sql/*`：SQL 生成、AST 校验、成本检查、只读执行。
- `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`：九节点 Graph。
- `src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java`：run 生命周期和事件发布。
- `src/main/java/com/example/smartpark/web/OperationsAnalysisController.java`：runs、clarifications、状态、SSE。
- `ui/src/components/analytics/*`、`ui/src/composables/useOperationsAnalysis.ts`：问题输入、计划/SQL、表格、ECharts、结论。

## Task 1：建立真实 analytics 数据边界

- [ ] 先增加 `AnalyticsSchemaMigrationTest.java`，用 Testcontainers PostgreSQL + Flyway 断言四个视图存在且应用账号不能 INSERT/UPDATE/DELETE。

- [ ] 在 `pom.xml` 加依赖并运行，预期因 migration 缺失失败：

```powershell
.\mvnw.cmd -B -Dtest=AnalyticsSchemaMigrationTest test
```

- [ ] 添加 migration：`analytics.v_energy_hourly`、`analytics.v_alert_fact`、`analytics.v_device_snapshot`、`analytics.v_parking_daily`。字段包括设计规格中的八项指标，并固定夜间为 22:00–06:00。

- [ ] 创建 `smartpark_analytics_ro` 角色：只允许 CONNECT、USAGE analytics、SELECT 四个视图；显式 revoke public schema 写权限。测试容器使用同一权限模型。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=AnalyticsSchemaMigrationTest test
git add -- pom.xml src/main/resources/db/migration src/test/java/com/example/smartpark/analytics/AnalyticsSchemaMigrationTest.java
git commit -m "feat: establish readonly analytics schema"
```

## Task 2：建立指标目录与查询计划契约

- [ ] 先写 `MetricCatalogTest.java` 与 `QueryPlanTest.java`，断言中文别名映射、单位、允许维度、默认时间范围、歧义返回澄清问题、未知指标拒绝。

- [ ] 实现不可变 `MetricDefinition` 和 `MetricCatalog`；目录必须覆盖 `energy_kwh`、`night_energy_kwh`、`energy_deviation_pct`、`alert_count`、`high_risk_alert_count`、`device_offline_count`、`parking_entries`、`parking_utilization_pct`。

- [ ] 定义 `QueryPlan(question, metrics, dimensions, filters, timeRange, limit)`，构造器强制 limit 1..500。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=MetricCatalogTest,QueryPlanTest test
git add -- src/main/java/com/example/smartpark/analytics/catalog src/main/java/com/example/smartpark/analytics/model src/test/java/com/example/smartpark/analytics/catalog src/test/java/com/example/smartpark/analytics/model
git commit -m "feat: define governed analytics metrics"
```

## Task 3：从根上实现 AST SQL 安全门

- [ ] 先写参数化 `SqlAstGuardTest.java`，允许 SELECT、聚合、JOIN 白名单视图、只读 CTE；拒绝 DML/DDL、`SELECT INTO`、多语句、注释拼接、非 analytics 对象、危险函数、无界 limit、字面量时间、递归 CTE。

- [ ] 实现 `SqlAstGuard`，必须使用 JSqlParser AST visitor；不得用正则表达式判定 SQL 类型或表名。输出 `ValidatedSql(sql, namedParameters, maxRows)`。

- [ ] 对未知/无法解析 AST 一律 fail closed；错误只返回安全错误码与可修复原因。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=SqlAstGuardTest test
git add -- src/main/java/com/example/smartpark/analytics/sql/SqlAstGuard.java src/main/java/com/example/smartpark/analytics/model/ValidatedSql.java src/test/java/com/example/smartpark/analytics/sql/SqlAstGuardTest.java
git commit -m "feat: enforce analytics sql ast policy"
```

## Task 4：实现成本检查与受限执行

- [ ] 先写 `ReadOnlyQueryExecutorTest.java`，用只读容器角色覆盖：绑定参数、3 秒 statement timeout、500 行截断、1 MiB 拒绝、数据库写操作失败、EXPLAIN 成本超限不执行。

- [ ] 实现 `QueryCostGuard` 执行 `EXPLAIN (FORMAT JSON)` 并检查配置阈值；实现 `ReadOnlyQueryExecutor` 在事务和连接两层设置 read-only，使用 NamedParameterJdbcTemplate，只接受 `ValidatedSql`。

- [ ] 结果只保存列名、类型、安全值、行数、截断标志和执行耗时；不得保存连接信息。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ReadOnlyQueryExecutorTest,QueryCostGuardTest test
git add -- src/main/java/com/example/smartpark/analytics/sql src/main/java/com/example/smartpark/analytics/model/TabularResult.java src/test/java/com/example/smartpark/analytics/sql
git commit -m "feat: execute bounded readonly analytics queries"
```

## Task 5：实现结构化模型边界与九节点 Graph

- [ ] 写 `OperationsAnalysisGraphTest.java`，用 `TestChatModel` 的固定结构化响应覆盖节点顺序：`understandQuestion -> resolveMetricAndDimensions -> recallAllowedSchema -> buildQueryPlan -> generateSql -> validateSqlAst -> explainAndCheckCost -> executeReadOnlyQuery -> buildChartSpec -> summarizeFromResult`。

- [ ] 定义模型输出 records 并用 JSON schema/严格解析，不从自由文本提取 SQL。第一次 SQL 校验或成本失败时只允许携带安全原因重试一次；第二次失败结束 run。

- [ ] `ChartSpec(type,title,xField,yFields,seriesField,unit)` 只允许 `line|bar|area|table`，字段必须存在于 `TabularResult`。

- [ ] 总结 prompt 仅注入问题、QueryPlan、TabularResult、ChartSpec；测试注入与结果矛盾的模型文本并拒绝不被数据支持的数字。

- [ ] 每个节点发布统一真实事件，SQL 事件展示 AST 校验后的 SQL 和参数名，不展示值中的敏感明细。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=OperationsAnalysisGraphTest,ChartSpecTest,AnalysisSummaryValidatorTest test
git add -- src/main/java/com/example/smartpark/analytics/agent src/main/java/com/example/smartpark/analytics/model src/test/java/com/example/smartpark/analytics/agent
git commit -m "feat: orchestrate natural language operations analysis"
```

## Task 6：run 生命周期、澄清与 API

- [ ] 写 `OperationsAnalysisServiceTest.java`，覆盖运行、歧义暂停、澄清后恢复、终态幂等、超时、最多一次 SQL 修复、并发 run 隔离。

- [ ] 写 `OperationsAnalysisControllerTest.java` 覆盖：

  - `POST /api/operations-analysis/runs`；
  - `POST /api/operations-analysis/runs/{runId}/clarifications`；
  - `GET /api/operations-analysis/runs/{runId}`；
  - `GET /api/executions/{runId}/events` 复用统一 SSE；
  - 非法输入 400、未知 run 404、不可恢复安全拒绝 422。

- [ ] 实现内存 run store 与服务，所有状态变更和事件发布在同一临界区；失败必须终止事件流。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=OperationsAnalysisServiceTest,OperationsAnalysisControllerTest test
git add -- src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java src/main/java/com/example/smartpark/analytics/AnalysisRunStore.java src/main/java/com/example/smartpark/web/OperationsAnalysisController.java src/main/java/com/example/smartpark/web/OperationsAnalysisDtos.java src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java src/test/java/com/example/smartpark/web/OperationsAnalysisControllerTest.java
git commit -m "feat: expose operations analysis runs"
```

## Task 7：分析 UI 与真实结果可视化

- [ ] 先写 `useOperationsAnalysis.spec.ts`、`OperationsAnalysisPage.spec.ts`，覆盖提交、澄清、SSE、SQL 展示、空结果、错误、表格、line/bar/table 图表。

- [ ] 增加 ECharts；创建页面组件。图表仅由后端 `ChartSpec + TabularResult` 生成；页面显示“真实只读查询”、耗时、行数、截断状态。

- [ ] 在 `App.vue` 导航加入“运营分析”，把 runId 交给共享轨迹栏。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd install echarts
npm.cmd run test:unit -- OperationsAnalysis
npm.cmd run build
Pop-Location
git add -- ui/package.json ui/package-lock.json ui/src/types/analytics.ts ui/src/services/analyticsApi.ts ui/src/composables/useOperationsAnalysis.ts ui/src/components/analytics ui/src/App.vue ui/src/styles.css
git commit -m "feat: add natural language operations analysis ui"
```

## Task 8：配置与完整回归

- [ ] 在 `application.yml` 增加 datasource、Flyway、`smartpark.analytics.statement-timeout=3s`、`max-rows=500`、`max-bytes=1048576`、cost 阈值；所有凭据仅引用环境变量。

- [ ] 添加配置绑定测试及启动失败测试：缺失真实数据库配置时 analytics 能力必须 fail fast，不能切换到内存数据。

- [ ] 执行：

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
Pop-Location
```

- [ ] 提交：

```powershell
git add -- src/main/resources/application.yml src/main/java/com/example/smartpark/analytics/AnalyticsProperties.java src/test/java/com/example/smartpark/analytics/AnalyticsPropertiesTest.java
git commit -m "config: require real analytics database"
```

## 完成闸门

- 每次成功分析均有真实 SQL、真实结果、ChartSpec、结果约束总结和完整轨迹。
- AST、EXPLAIN、应用执行器、数据库权限四层均能独立阻止写操作。
- 歧义问题显式澄清，SQL 最多修复一次；无静默替代结果。
- 目标正常链路 10–20 秒；超时明确失败。
