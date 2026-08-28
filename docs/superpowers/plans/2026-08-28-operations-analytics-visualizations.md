# 运营分析全套展示与模拟数据 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在真实只读运营分析上增加 KPI、排行、堆叠柱状图、折线图、热力图、日历热力图、散点图、仪表盘、楼宇空间分布和表格兜底，并补齐可重复命中的模拟数据。

**Architecture:** 保留 `OperationsAnalysisGraph` 的查询和 SSE 生命周期。模型只提出结构化图表候选；后端依据已执行的 `QueryPlan`、指标目录和真实 `TabularResult` 生成/校验有限图表契约，前端 `AnalyticsChart` 继续复用 ECharts 渲染，结果表始终保留。新增数据库字段通过白名单视图暴露，原始表不授予分析只读角色。

**Tech Stack:** Java 17, Spring Boot 4, PostgreSQL 16, Flyway, JSqlParser, Vue 3, TypeScript, ECharts 6, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-28-operations-analytics-visualizations-design.md`

## Global Constraints

- 图表字段必须来自已执行结果列，不能由模型或前端创造数据。
- 图表单位必须来自指标目录；混合单位、空结果、缺失坐标和不匹配维度回退 `TABLE`。
- SQL 仍由已校验 `QueryPlan` 参数化渲染，并继续通过 AST、查询计划、只读角色和成本门禁。
- 新增模拟数据以当前日期为锚点，保证相对时间问题在不同运行日期都能命中。
- 不输出原始模型响应、数据库凭据或供应商错误正文。
- 使用现有 Apache ECharts，不重复实现图表引擎。

---

## Task 1: Add deterministic analytics demo data and catalog metrics

**Files:**
- Create: `src/main/resources/db/migration/V3__add_operations_visualization_demo_data.sql`
- Modify: `src/main/java/com/example/smartpark/analytics/catalog/MetricCatalog.java`
- Modify: `src/test/java/com/example/smartpark/analytics/AnalyticsSchemaMigrationTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/catalog/MetricCatalogTest.java`

**Interfaces:**
- Produces new allowlisted view columns: `building_name`, `stat_date`, `hour_of_day`, `day_of_week`, `area_sqm`, `map_x`, `map_y`, `occupancy_count`, and `target_kwh`.
- Produces catalog metrics `peak_kw`, `occupancy_avg`, and `energy_target_completion_pct`, plus canonical dimensions needed by heatmap, calendar, scatter and map queries.

- [ ] Write a migration test that applies V1–V3 to a fresh PostgreSQL database, checks the new view columns and verifies the three buildings have profile, occupancy and target data.
- [ ] Run `./mvnw.cmd -Dtest=AnalyticsSchemaMigrationTest test` and confirm the new column assertions fail before V3 exists.
- [ ] Add V3 with `building_profile_raw` and `building_occupancy_hourly_raw`, current-date-anchored rows, a recreated `v_energy_hourly` view exposing only derived fields, and explicit grants only on the existing allowlisted view.
- [ ] Add catalog definitions with source view, expression, unit, shared time column and approved dimensions; do not expose the new raw tables to `smartpark_analytics_ro`.
- [ ] Run the migration and catalog tests and confirm they pass.

## Task 2: Extend the backend chart contract and safe chart resolution

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/model/ChartSpec.java`
- Modify: `src/main/java/com/example/smartpark/execution/model/DisplayPayload.java`
- Modify: `src/main/java/com/example/smartpark/web/ExecutionDtos.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClient.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Test: `src/test/java/com/example/smartpark/analytics/model/ChartSpecTest.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClientTest.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

**Interfaces:**
- Extends `ChartSpec.ChartType` with `KPI`, `STACKED_BAR`, `HEATMAP`, `CALENDAR_HEATMAP`, `SCATTER`, `GAUGE` and `MAP` while retaining `LINE`, `BAR` and `TABLE`.
- Adds typed rendering options for horizontal orientation, stacking, target value and coordinate fields; the graph emits the same options through `DisplayPayload.ChartPayload` and its DTO.
- `ChartSpec.fromProposal(Proposal, TabularResult, Map<String,String>)` remains the single validation entry point.

- [ ] Add failing tests for every new type, valid field combinations, unit provenance, required numeric/discrete dimensions, gauge target derivation and safe table fallback.
- [ ] Run the focused Java tests and confirm the new enum/options assertions fail against the old three-type contract.
- [ ] Implement typed options and validation: reject unknown result columns, empty numeric series, duplicate heatmap coordinates, missing map coordinates, mixed units and invalid target ranges; return a table fallback.
- [ ] Update the model chart prompt to list the finite chart types and options, while preserving the server-side validation boundary.
- [ ] Emit validated chart options in the `CHART_SPECIFIED` event and retain the existing safe event payload policy.
- [ ] Run the chart, model-client and graph tests and confirm all pass.

## Task 3: Implement ECharts renderers for all chart types

**Files:**
- Modify: `ui/src/types/execution.ts`
- Modify: `ui/src/components/analytics/AnalyticsChart.vue`
- Modify: `ui/src/components/analytics/analytics.css`
- Test: `ui/src/components/analytics/AnalyticsChart.spec.ts`
- Test: `ui/src/components/analytics/OperationsAnalysisPage.spec.ts`

**Interfaces:**
- `DisplayPayload` uses the backend chart enum and typed option fields.
- `AnalyticsChart` consumes only `chart`, `columns` and `rows`, returning an empty render for `TABLE` or invalid specs and never changing `rows`.

- [ ] Add component tests for KPI text, horizontal BAR, STACKED_BAR, HEATMAP, CALENDAR_HEATMAP, SCATTER, GAUGE, MAP and invalid/table fallback; spy on ECharts option construction without asserting implementation-only details.
- [ ] Run the chart component tests and confirm the new type cases fail before renderer branches exist.
- [ ] Implement one option builder per chart family: category charts, matrix charts, calendar, numeric scatter, gauge and coordinate scatter; keep null cells as gaps and preserve all result rows in the table.
- [ ] Add accessible labels and compact legends/tooltips, with a text fallback for KPI and gauge values.
- [ ] Run the full Vue unit suite and production typecheck/build.

## Task 4: Add query presets and deterministic demo scenarios

**Files:**
- Modify: `ui/src/components/analytics/OperationsAnalysisPage.vue`
- Modify: `ui/src/components/analytics/OperationsAnalysisPage.spec.ts`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/AnalyticsQuestionNormalizer.java`

**Interfaces:**
- The preset list remains a local array of question strings and click behavior continues to fill the input without bypassing the normal submit flow.
- Normalization preserves only dimensions and filters stated by the original question, including the new date/hour/coordinate terms.

- [ ] Add failing UI assertions for presets covering past-five-day energy, hourly trend, ranking, heatmap, target completion and occupancy relationship.
- [ ] Run the page test and verify it fails because the new presets are absent.
- [ ] Add the preset questions and conservative aliases/grouping rules; do not add a separate query execution path.
- [ ] Run the page tests and confirm each preset fills the analysis input correctly.

## Task 5: Verify real end-to-end results and update documentation

**Files:**
- Modify: `docs/architecture.md`
- Modify: `docs/customer-capabilities.md`
- Modify: `src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java`
- Modify: `src/test/java/com/example/smartpark/web/ExecutionEventControllerTest.java`

**Interfaces:**
- Real Compose analysis runtime uses the existing explicit analytics overlay and returns a result table plus a validated `CHART_SPECIFIED` event for each supported scenario.

- [ ] Add service/event assertions for chart options surviving the graph-to-SSE-to-DTO boundary and for table fallback on invalid proposals.
- [ ] Run focused backend and frontend tests, then the full `./mvnw.cmd test` and `npm.cmd run test:unit -- --run` commands.
- [ ] Build the frontend and backend images using the explicit analytics Compose configuration; run `docker compose config --quiet` first.
- [ ] Execute at least six real demo questions covering BAR, LINE, STACKED_BAR, HEATMAP/CALENDAR_HEATMAP, SCATTER, GAUGE/KPI and MAP; verify rows, chart type, fields, units and no failure stage.
- [ ] Verify all Compose services are healthy and update architecture/customer docs with the actual supported display types and fallback behavior.
- [ ] Run `git diff --check` and inspect `git status --short`; report tests, runtime health and any remaining non-blocking warnings separately.
