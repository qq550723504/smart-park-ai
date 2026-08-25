# PR #11 P1 Root Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the six current P1 review findings by replacing racy lifecycle transitions, ineffective cancellation, text-based SQL plan checks, implicit time windows, and regex-only synthesis validation with explicit contracts.

**Architecture:** Keep the existing single-process REST/SSE architecture and public DTOs. Tighten internal boundaries with atomic run transitions, cancellable `FutureTask` handles, structured requested time ranges, a JSqlParser-backed `SqlPlanGuard`, and server-derived synthesis conclusions assembled from validated findings.

**Tech Stack:** Java 17, Spring Boot 4, Spring AI Alibaba 2.0.0-M1.1, JSqlParser, JUnit 5, AssertJ, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-pr11-p1-root-fixes-design.md`

## Global Constraints

- Preserve current REST/SSE response shapes and run IDs.
- Keep the documented single-instance in-memory boundary; do not add durable workflow infrastructure.
- Reuse JSqlParser and JDK concurrency primitives; add no replacement SQL parser, state-machine framework, or NLP dependency.
- Write and observe each regression test failing before production changes.
- Do not address unresolved P2 comments in this implementation.

---

### Task 1: Atomic analysis clarification lifecycle

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsProperties.java`
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java`
- Modify: `src/test/java/com/example/smartpark/analytics/AnalyticsPropertiesTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java`

**Interfaces:**
- Consumes: existing `AnalysisRunStore.RunRecord`, `GraphRunner`, `Clock`, metric candidates returned by `AnalysisRunResult`.
- Produces: constructor parameter `Duration clarificationTimeout`; internal `PendingClarification(questions, candidates, expiresAt)`; atomic `NEEDS_CLARIFICATION -> RUNNING` transition.

- [ ] **Step 1: Write failing lifecycle tests**

Add tests that coordinate two threads on the same paused run and assert exactly one `submitClarification` succeeds and `GraphRunner` resumes once. Add a fixed/mutable clock test where a paused run exceeds `clarificationTimeout`, then a new `start` succeeds while the old run becomes `FAILED` with `CLARIFICATION_TIMEOUT`. Add property binding coverage for a positive default/configured clarification timeout and rejection of zero/negative values.

- [ ] **Step 2: Run the tests and verify RED**

Run:

```powershell
./mvnw.cmd -B '-Dtest=OperationsAnalysisServiceTest,AnalyticsPropertiesTest' test
```

Expected: concurrent resume launches more than once or both calls succeed; abandoned clarification still rejects a new run; the new property API does not compile.

- [ ] **Step 3: Implement one atomic lifecycle boundary**

Add `AnalyticsProperties.clarificationTimeout` defaulting to five minutes and validate it as positive. Pass it through `AnalyticsConfiguration`; retain existing service constructors as compatibility facades that delegate to the new timeout-aware constructor. Replace the candidate-only map with:

```java
private record PendingClarification(
        List<String> questions,
        List<Set<String>> candidates,
        Instant expiresAt) {}
```

Under `lifecycleLock`, validate current status and pending candidates, consume pending state, write `RUNNING`, and only then launch once. Before rejecting `start` or accepting clarification, expire an overdue paused run to `FAILED/CLARIFICATION_TIMEOUT`, release `activeRunId`, and publish one terminal failure event.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 1 command and confirm zero failures.

- [ ] **Step 5: Commit the slice**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/AnalyticsProperties.java src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java src/test/java/com/example/smartpark/analytics/AnalyticsPropertiesTest.java src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java
git commit -m "fix: make analysis clarification transitions atomic"
```

### Task 2: Cancel the underlying collaboration run

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaboration/ExpertCollaborationService.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/ExpertCollaborationServiceTest.java`

**Interfaces:**
- Consumes: existing `ExecutorService runExecutor`, `Duration runTimeout`, graph/planner/synthesizer boundaries.
- Produces: directly submitted `FutureTask<Void>` and synchronized `savePlanIfRunning`, `saveFindingsIfRunning`, `completeIfRunning` transitions.

- [ ] **Step 1: Write failing cancellation tests**

Use a real single-thread executor and latches. Assert that the run thread observes interruption after the total deadline. Add a dependency that deliberately returns after interruption and assert the stored run remains `FAILED`, never returning to `RUNNING` or `COMPLETED`.

- [ ] **Step 2: Run the tests and verify RED**

```powershell
./mvnw.cmd -B '-Dtest=ExpertCollaborationServiceTest' test
```

Expected: current `orTimeout` path does not interrupt the underlying run, or a late stage overwrites the failed record.

- [ ] **Step 3: Implement cancellable ownership and terminal guards**

Replace `CompletableFuture.runAsync(...).orTimeout(...)` with a `FutureTask<Void>` submitted directly to `runExecutor`. A synchronized deadline transition first verifies the task is not done, stores `FAILED`, and then calls `cancel(true)` on that exact task; this orders terminal state before interruption. Gate plan, findings, and completion writes through synchronized “if RUNNING” methods; abort later stages when a gate returns false.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 2 command and confirm the interrupted flag and terminal state assertions pass.

- [ ] **Step 5: Commit the slice**

```powershell
git add -- src/main/java/com/example/smartpark/collaboration/ExpertCollaborationService.java src/test/java/com/example/smartpark/collaboration/ExpertCollaborationServiceTest.java
git commit -m "fix: cancel collaboration runs at the owning task"
```

### Task 3: Carry explicit user time ranges into query plans

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/AnalyticsModelClient.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClient.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java`
- Create: `src/test/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClientTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

**Interfaces:**
- Produces: `RequestedTimeRange(Instant fromInclusive, Instant toExclusive)` and four-argument `QuestionUnderstanding`; retain the existing three-argument constructor as a compatibility facade that supplies no requested range.
- Consumes: injected `Clock` and park zone `Asia/Shanghai`; `QueryPlan.TimeRange` remains the downstream source of truth.

- [ ] **Step 1: Write failing structured-time tests**

Create a `TestChatModel` response containing ISO `fromInclusive` and `toExclusive` values and assert `LlmAnalyticsModelClient.understandQuestion` parses them. In the graph test, supply a 30-day `RequestedTimeRange` and capture `SqlGenerationRequest.plan()` plus execution parameters; assert both use the exact requested instants. Keep a separate no-range test asserting catalog default lookback.

- [ ] **Step 2: Run the tests and verify RED**

```powershell
./mvnw.cmd -B '-Dtest=LlmAnalyticsModelClientTest,OperationsAnalysisGraphTest' test
```

Expected: requested-time types/constructor do not exist and the graph still binds the metric default range.

- [ ] **Step 3: Implement the structured model boundary**

Add the optional range record with ordered, non-null endpoint validation. Update the understanding prompt to include `now` and `Asia/Shanghai` and request either both ISO endpoints or `null`. Parse both endpoints with `Instant.parse`. In `buildQueryPlan`, select the requested range when present and derive the parameter map from `ctx.plan.timeRange()` instead of recalculating it.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 3 command and confirm exact instant assertions pass.

- [ ] **Step 5: Commit the slice**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/agent/AnalyticsModelClient.java src/main/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClient.java src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java src/test/java/com/example/smartpark/analytics/agent/LlmAnalyticsModelClientTest.java src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java
git commit -m "fix: carry requested time ranges into analysis plans"
```

### Task 4: Enforce query plans through the SQL AST

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/sql/SqlPlanGuard.java`
- Modify: `src/main/java/com/example/smartpark/analytics/catalog/MetricDefinition.java`
- Modify: `src/main/java/com/example/smartpark/analytics/catalog/MetricCatalog.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Create: `src/test/java/com/example/smartpark/analytics/sql/SqlPlanGuardTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/catalog/MetricCatalogTest.java`

**Interfaces:**
- Consumes: `ValidatedSql`, `QueryPlan`, JSqlParser AST classes already on the classpath.
- Produces: `SqlPlanGuard.validate(ValidatedSql sql, QueryPlan plan)`; `MetricDefinition.timeColumn()`.

- [ ] **Step 1: Write failing semantic-gate tests**

Build a high-risk alert plan and assert rejection for SQL where `risk_level = 'HIGH'` appears only inside an unrelated string literal and `:fromTs <= :toTs` is a tautology. Assert acceptance for SQL with top-level conjuncts `occurred_at >= :fromTs`, `occurred_at < :toTs`, and `risk_level = 'HIGH'`. Add catalog assertions for all four temporal columns.

- [ ] **Step 2: Run the tests and verify RED**

```powershell
./mvnw.cmd -B '-Dtest=SqlPlanGuardTest,MetricCatalogTest,OperationsAnalysisGraphTest' test
```

Expected: `SqlPlanGuard` and `timeColumn` do not exist; the current text gate accepts the bypass SQL.

- [ ] **Step 3: Implement the JSqlParser semantic gate**

Parse the validated SELECT with JSqlParser. Read actual tables from `TablesNamesFinder`. Flatten only top-level `AND` terms from the WHERE expression. Match time bounds only when a planned `timeColumn` is compared directly with the correct named parameter and direction. Parse each non-null metric condition with `CCJSqlParserUtil.parseCondExpression` and require an equivalent top-level AST term. Delete the graph’s `normalize/contains` gate and invoke `SqlPlanGuard` after `SqlAstGuard`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 4 command and confirm bypass rejection plus valid-query acceptance.

- [ ] **Step 5: Commit the slice**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/sql/SqlPlanGuard.java src/main/java/com/example/smartpark/analytics/catalog/MetricDefinition.java src/main/java/com/example/smartpark/analytics/catalog/MetricCatalog.java src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java src/test/java/com/example/smartpark/analytics/sql/SqlPlanGuardTest.java src/test/java/com/example/smartpark/analytics/catalog/MetricCatalogTest.java src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java
git commit -m "fix: enforce analysis plans through the SQL AST"
```

### Task 5: Derive synthesis text from validated findings

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesizer.java`
- Modify: `src/main/java/com/example/smartpark/collaboration/supervisor/SynthesisValidator.java`
- Modify: `src/main/java/com/example/smartpark/collaboration/CollaborationRuntimeConfiguration.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesisTest.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/supervisor/SynthesisValidatorTest.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/CollaborationRuntimeConfigurationTest.java`

**Interfaces:**
- Model JSON consumes: `status`, `selectedDomains`, `evidenceRefs`, `confidence`, `uncertainties`.
- Produces: existing public `Synthesis`; `conclusion` is server-built by joining exact conclusions from selected `SUPPORTED` findings in enum order.
- Validator signature: `validate(Synthesis synthesis, List<ExpertFinding> findings, Set<ExpertDomain> selectedDomains)`.

- [ ] **Step 1: Write failing synthesis tests**

Provide a supported ENERGY finding whose conclusion names B1. Feed model JSON that selects ENERGY but also includes a contradictory free-text B2 conclusion; assert the returned synthesis conclusion remains the exact B1 finding text. Assert selecting an unsupported/failed domain or citing evidence outside selected findings is rejected.

- [ ] **Step 2: Run the tests and verify RED**

```powershell
./mvnw.cmd -B '-Dtest=SupervisorSynthesisTest,SynthesisValidatorTest,CollaborationRuntimeConfigurationTest' test
```

Expected: current parser requires and trusts model `conclusion`, so the contradictory value survives or the new JSON contract is rejected.

- [ ] **Step 3: Implement deterministic conclusion construction**

Parse and validate `selectedDomains`; resolve them to `SUPPORTED` findings; construct the conclusion from those exact finding conclusions. Build `Synthesis` with model status/confidence/uncertainties and the server-derived text. Call `SynthesisValidator.validate(synthesis, allFindings, selectedDomains)`. Replace regex fact extraction with checks that every selected domain has a supported finding, cited evidence belongs to selected findings, at least one supported finding backs `SUPPORTED`, and partial failures in the complete finding list are disclosed. Update the model prompt to omit free-text conclusion.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 5 command and confirm contradictory free text cannot affect the result.

- [ ] **Step 5: Commit the slice**

```powershell
git add -- src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesizer.java src/main/java/com/example/smartpark/collaboration/supervisor/SynthesisValidator.java src/main/java/com/example/smartpark/collaboration/CollaborationRuntimeConfiguration.java src/test/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesisTest.java src/test/java/com/example/smartpark/collaboration/supervisor/SynthesisValidatorTest.java src/test/java/com/example/smartpark/collaboration/CollaborationRuntimeConfigurationTest.java
git commit -m "fix: derive synthesis from validated findings"
```

### Task 6: Verify and close the PR review loop

**Files:**
- Modify: `docs/superpowers/plans/2026-08-25-pr11-p1-root-fixes.md` only to check completed boxes if desired.

**Interfaces:**
- Consumes: all five independently committed slices.
- Produces: clean local worktree, pushed PR head, inline replies in the six original review threads, and zero unresolved P1 threads from this review round.

- [ ] **Step 1: Run full verification**

```powershell
./mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
git status --short --branch
```

- [ ] **Step 2: Push the branch without force**

```powershell
git push origin codex/smart-park-p1
```

- [ ] **Step 3: Reply and resolve original threads**

Re-fetch all review threads. For each of the six root comment IDs, post a concise inline reply naming the implementing commit and focused regression test, then resolve that thread. Do not resolve a thread whose test or implementation is missing.

- [ ] **Step 4: Re-fetch remote evidence**

Confirm the PR head OID, unresolved thread count, and each remote CI check separately. Leave the PR open; do not merge or deploy.
