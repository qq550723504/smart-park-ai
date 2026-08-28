# Operations Analysis Runtime Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make valid natural-language analytics questions produce governed results and charts reliably when the live model returns advisory fields that do not exactly match the internal query contract.

**Architecture:** Keep the live model as an understanding and SQL proposal provider, but introduce a deterministic normalization boundary before `QueryPlan` construction and a deterministic SQL renderer for plans that do not require model-specific SQL freedom. Preserve the read-only AST/cost/execution gates. Add safe internal diagnostics while keeping vendor responses, credentials, SQL drafts, and stack traces out of public payloads.

**Tech Stack:** Java 17, Spring Boot, Spring AI Alibaba, PostgreSQL, JSqlParser, JUnit 5, AssertJ, Vue 3, TypeScript.

**Spec:** Approved in-chat design from 2026-08-28: canonicalize model understanding, render SQL from validated plans, and retain safe failure causes.

## Global Constraints

- Analytics queries remain read-only and restricted to the four whitelisted analytics views.
- Model output remains untrusted; no model-provided dimension, filter, SQL, chart field, or number bypasses deterministic validation.
- No credentials, vendor response bodies, raw SQL drafts, or stack traces may reach public API/SSE payloads or logs.
- Preserve existing REST/SSE contracts unless a narrowly scoped safe failure field is required.
- Do not change non-analytics business chains or expand the metric catalog in this slice.
- Use the existing JSqlParser AST guard, plan guard, cost guard, and result grounding validator.

---

### Task 1: Add regression tests for the live failure boundaries

**Files:**
- Modify: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClientTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java`

**Steps:**

- [ ] Add a graph regression test proving `过去5天各楼宇能耗` reaches SQL generation when the understanding response contains an advisory non-canonical dimension or inferred time grouping.
- [ ] Add a SQL regression test proving a plan for parking entries is rendered without an unauthorized `GROUP BY stat_date` when the user asked for a total.
- [ ] Add a service/graph test proving an unexpected internal failure retains a safe failure stage/reason without exposing the exception text or vendor body.
- [ ] Run only the new tests and confirm they fail for the intended missing behavior.

### Task 2: Normalize structured model understanding at the analytics boundary

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClient.java`
- Modify or create: `src/main/java/com/example/smartpark/analytics/agent/AnalyticsQuestionNormalizer.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/AnalyticsQuestionNormalizerTest.java`

**Interfaces:**
- Consume the original question, `MetricCatalog`, parsed server-owned time intent, and model advisory fields.
- Produce canonical metric names, only explicitly requested canonical dimensions, and only question-grounded typed filters.

**Steps:**

- [ ] Define canonicalization rules for common aliases such as `building`/`楼宇` to `building_id`, without treating implicit time span as a grouping dimension.
- [ ] Reject or clarify unsupported model fields rather than silently widening the query.
- [ ] Preserve the original-question and catalog checks at `QueryPlan` as defense in depth.
- [ ] Run normalizer tests and the graph regression test; confirm the tests pass.

### Task 3: Render governed SQL from the validated query plan

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/sql/QueryPlanSqlRenderer.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/AnalyticsModelClient.java` only if the provider seam needs narrowing
- Test: `src/test/java/com/example/smartpark/analytics/sql/QueryPlanSqlRendererTest.java`

**Interfaces:**
- Consume a validated `QueryPlan` and render one parameterized PostgreSQL `SELECT` with the plan's metric expressions, approved dimensions, approved filters, time column, and exact limit.
- Return the existing SQL draft/validated-SQL flow so AST, plan, cost, and read-only gates remain authoritative.

**Steps:**

- [ ] Add renderer tests for total energy, per-building energy, total parking entries, and categorical alert filters.
- [ ] Verify renderer output uses only bound `fromTs`/`toTs` and filter parameters, never literals from the question.
- [ ] Route governed plans through the renderer and keep the model-generated path only where the existing contract requires it, with deterministic post-validation.
- [ ] Run renderer, SQL guard, and graph tests; confirm the parking regression passes.

### Task 4: Preserve safe, actionable failure diagnostics

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java`
- Modify: `src/main/java/com/example/smartpark/web/OperationsAnalysisDtos.java` only if a safe public field is needed
- Test: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

**Steps:**

- [ ] Map known validation failures to their actual safe stage (`buildQueryPlan`, `validateSqlAst`, etc.).
- [ ] Log only a stable error code/stage and sanitized message at the backend boundary; never log credentials, model response, or SQL draft.
- [ ] Keep the public failure response bounded to the existing `failureStage` contract and retry guidance.
- [ ] Run failure-path tests and inspect event payloads for secret/stack/vendor-body absence.

### Task 5: Full verification and runtime acceptance

**Files:**
- No additional source changes unless verification exposes a regression.

**Steps:**

- [ ] Run focused analytics unit tests and record exact counts.
- [ ] Run the full Maven test suite, distinguishing any pre-existing clock/fixture failure from this slice.
- [ ] Build and restart the isolated Compose analytics stack using `.env` without printing values.
- [ ] Verify capabilities, database fixture counts, `过去5天各楼宇能耗` result rows, and a BAR/LINE chart event.
- [ ] Inspect the diff, ensure only scoped files changed, and report local tests, runtime checks, and any remaining limitations separately.
