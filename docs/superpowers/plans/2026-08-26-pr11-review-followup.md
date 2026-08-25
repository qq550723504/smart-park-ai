# PR #11 Review Follow-up Implementation Plan

> **For agentic workers:** Execute this plan task-by-task with a fresh test cycle for each task.

**Goal:** Resolve the seven unresolved PR #11 review threads against the merged PR head without changing the current checkout.

**Architecture:** Keep the existing server-owned query plan and metric catalog boundaries. Extend the analysis graph's deterministic normalization at the point where model output becomes a validated plan, and extend expert routing at the domain-validation boundary. Fix the shared Vue trace lifecycle by making the collaboration component resubscribe when its view becomes active, while preserving mounted component state.

**Tech Stack:** Java 17, Spring Boot 4, JUnit 5, AssertJ, Testcontainers PostgreSQL, Vue 3, TypeScript, Vitest, Vue Test Utils.

**Spec:** PR #11 unresolved review threads `discussion_r3854715303`, `discussion_r3854715314`, `discussion_r3854715322`, `discussion_r3854715329`, `discussion_r3854715339`, `discussion_r3854715354`, and `discussion_r3854715363`.

## Global Constraints

- Work only in `codex/pr11-review-followup` at `C:\Users\Henry\code\springaialibaba\.worktrees\pr11-review-followup`.
- Preserve the current checkout and unrelated worktrees.
- Server-side time and filter interpretation is deterministic and fail-closed.
- SQL remains parameterized and must satisfy the existing AST and query-plan guards.
- Existing entity-filter case-sensitive behavior remains unchanged; only categorical values may normalize case-insensitively.
- Each behavior gets a failing regression test before production code changes.
- No GitHub mutation is required while implementing this local follow-up.

### Task 1: Metric time-column grouping and canonical metric de-duplication

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

- [ ] Add a failing integration test for `按日查看停车进场量`: the plan must infer only `stat_date`, execute a valid grouped query, and complete.
- [ ] Add a failing integration test for model terms `能耗` and `用电量`: the final plan must contain one `energy_kwh` metric and the valid SQL must execute once.
- [ ] Run only the two new tests and confirm each fails for the pre-fix root cause.
- [ ] Change metric resolution to retain the first resolved metric per canonical name while preserving model order.
- [ ] Replace the temporal aggregation branch that enumerates `occurred_at`, `snapshot_at`, and `stat_date` with metric-owned `timeColumn` inference when a daily/time grouping phrase is present; still validate that each inferred column is approved by every selected metric.
- [ ] Run the two tests again, then the complete `OperationsAnalysisGraphTest`.

### Task 2: Server-owned relative and explicit calendar time ranges

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

- [ ] Add a failing test proving `上周` resolves to the previous Monday 00:00 through the current Monday 00:00 in `Asia/Shanghai`, while rolling phrases remain rolling.
- [ ] Add a failing test proving `2026-08-01 到 2026-08-05` becomes 2026-08-01 local start through 2026-08-06 local start, including when the model returns a matching requested range.
- [ ] Run the tests and verify the failures are caused by unsupported/incorrect time parsing.
- [ ] Implement one deterministic parser for relative phrases and ISO calendar-date ranges; parse explicit ranges as local calendar dates and use an exclusive next-day boundary.
- [ ] Keep model-range validation against the parsed server expectation and use the parsed range when the model omits it.
- [ ] Run the focused tests and the complete analytics graph test class.

### Task 3: Preserve omitted categorical filters

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/main/java/com/example/smartpark/analytics/model/QueryPlan.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`
- Test: `src/test/java/com/example/smartpark/analytics/model/QueryPlanTest.java`

- [ ] Add a failing integration test for `OPEN 状态的告警数量` where the model returns no filter; the final plan must contain `status=OPEN` and bind `:filter_status`.
- [ ] Add a failing unit test proving canonical categorical values such as `OPEN` can match a lower-case question token without weakening identifier matching.
- [ ] Run both tests and confirm the model omission currently widens the plan.
- [ ] Add a small deterministic dimension-value vocabulary for the existing analytics schema (status, risk level, and alert category), merge inferred values with model filters without overwriting explicit model values, and only infer a dimension approved by every selected metric unless the metric's fixed condition already owns that predicate.
- [ ] Make the plan-boundary question-presence check case-insensitive only for categorical dimensions; retain exact entity identifier checks.
- [ ] Run focused tests, the graph class, and `QueryPlanTest`.

### Task 4: Entity-only expert routing

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorPlanValidator.java`
- Test: `src/test/java/com/example/smartpark/collaboration/supervisor/SupervisorPlanValidatorTest.java`

- [ ] Add failing tests for `电表 MTR-1-1 当前读数是多少` -> ENERGY and `DEV-POWER-001 当前状态` -> DEVICE.
- [ ] Run the focused validator test and verify both currently produce an empty expected-domain set.
- [ ] Add token/boundary-aware entity noun and identifier-prefix detection for meter, device, and security entities without changing the existing generic-alert rule.
- [ ] Run the focused validator test and the complete collaboration supervisor test set.

### Task 5: Restore collaboration trace on view activation

**Files:**
- Modify: `ui/src/App.vue`
- Modify: `ui/src/components/ExpertCollaborationPage.vue`
- Test: `ui/src/components/ExpertCollaborationPage.spec.ts`
- Test: `ui/src/App.spec.ts`

- [ ] Add a failing component test with a stable runId: after `active=false` then `active=true`, the trace must resubscribe to that same run.
- [ ] Run the focused UI test and confirm the unchanged runId currently prevents resubscription.
- [ ] Pass the current-view state from `App.vue` to the collaboration page and watch `active + runId`; subscribe when the view becomes active and a run exists.
- [ ] Preserve the existing default prop behavior for direct component callers and keep all views mounted under `v-show`.
- [ ] Run the focused component/App tests, UI typecheck, and the complete UI unit suite.

### Task 6: Cross-component verification and handoff

**Files:**
- No production files beyond Tasks 1-5.

- [x] Run the complete Maven test suite and record the result, including skipped tests.
- [x] Run UI typecheck, UI unit tests, and production build.
- [x] Review `git diff --check`, the changed-file list, and the unresolved PR comment mapping.
- [x] Do not claim review closure until all seven local regressions pass and the final diff contains no unrelated changes.
