# PR20 Time Intent Provider Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the graph's partial time-expression matching with a complete, replaceable time-intent boundary that never ignores temporal qualifiers or silently falls back to the metric default.

**Architecture:** `OperationsAnalysisGraph` will consume a `TimeIntentProvider` result rather than calling parser details directly. The first provider, `FiniteGrammarTimeIntentProvider`, will expose a bounded grammar, preserve source spans, reject unconsumed temporal qualifiers, and calculate ranges with the server `Clock` and park timezone. A future Duckling or other parser adapter can implement the same provider without changing the graph or `QueryPlan`.

**Tech Stack:** Java 17, `java.time`, JUnit 5, AssertJ, Spring AI Alibaba graph, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-08-26-pr20-time-intent-provider-design.md`

## Global Constraints

- `NONE` permits metric default lookback; `PARSED` uses only the server-calculated exact range; `UNSUPPORTED`, `MULTIPLE`, and `AMBIGUOUS` fail before SQL generation.
- Do not allow model-provided timestamps to override the server time-intent result.
- Do not modify public REST/SSE response structures or add an unverified external parser service.
- Preserve entity-token boundaries so dates inside identifiers such as `MTR-2026-08-01` are not time mentions.
- Use the park timezone `Asia/Shanghai` for calendar boundaries and the injected `Clock` for all current-time calculations.
- Each implementation slice must have a failing test before production code and an independent commit.

---

### Task 1: Introduce the replaceable time-intent contract

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/agent/TimeIntent.java`
- Create: `src/main/java/com/example/smartpark/analytics/agent/TimeIntentProvider.java`
- Create: `src/main/java/com/example/smartpark/analytics/agent/TimeIntentResult.java`
- Test: `src/test/java/com/example/smartpark/analytics/agent/TimeIntentResultTest.java`

**Interfaces:**
- `TimeIntentProvider.resolve(String question, Instant now)` returns `TimeIntentResult`.
- `TimeIntentResult` contains `Status`, `List<TimeMention>`, nullable `TimeIntent`, nullable `QueryPlan.TimeRange`, and a safe `reason`.
- `TimeIntent` contains `sourceText`, `Kind`, `amount`, `Unit`, nullable `LocalDate fromDate`, nullable `LocalDate toDate`, and nullable `DayPart`; its constructor rejects invalid field combinations. `DATE_RANGE` uses both date endpoints so an absolute interval is not lossy.

- [ ] **Step 1: Write the failing contract tests**

Add tests for:

```java
assertThat(new TimeIntentResult(TimeIntentResult.Status.NONE, List.of(), null, null, "")
        .status()).isEqualTo(TimeIntentResult.Status.NONE);

assertThatThrownBy(() -> new TimeIntent(
        "过去24小时", TimeIntent.Kind.ROLLING, 0, TimeIntent.Unit.HOUR, null, null))
        .isInstanceOf(IllegalArgumentException.class);
```

Also test that `TimeMention` preserves `text`, `start`, and `end`, and that a parsed result requires a non-null intent and range.

- [ ] **Step 2: Run the contract test to verify RED**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=TimeIntentResultTest' test
```

Expected: test compilation fails because the new contract types do not exist.

- [ ] **Step 3: Implement the minimal contract**

Use package-visible records in the analytics agent package. Define these enums:

```java
enum Kind { ROLLING, SINGLE_DATE, DATE_RANGE, CALENDAR_PERIOD, QUALIFIED_DAY, DAY_PART }
enum Unit { HOUR, DAY, WEEK, MONTH, QUARTER, YEAR, HALF_YEAR }
enum DayPart { MORNING, AFTERNOON }
```

Validate positive amounts for `ROLLING`, require `fromDate` for `SINGLE_DATE` and both `fromDate`/`toDate` for `DATE_RANGE`, require `dayPart` for `DAY_PART`, and require both intent and range only for `PARSED` results.

- [ ] **Step 4: Run the contract test to verify GREEN**

Run the same Maven command. Expected: all contract assertions pass.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/agent/TimeIntent.java src/main/java/com/example/smartpark/analytics/agent/TimeIntentProvider.java src/main/java/com/example/smartpark/analytics/agent/TimeIntentResult.java src/test/java/com/example/smartpark/analytics/agent/TimeIntentResultTest.java
git commit -m "refactor: define time intent provider contract"
```

### Task 2: Make the finite provider consume complete temporal spans

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProvider.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/TimeRangeParser.java`
- Modify: `src/test/java/com/example/smartpark/analytics/agent/TimeRangeParserTest.java`
- Create: `src/test/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProviderTest.java`

**Interfaces:**
- `FiniteGrammarTimeIntentProvider implements TimeIntentProvider`.
- `TimeRangeParser` becomes a compatibility adapter whose `parse` method delegates to the provider and maps `TimeIntentResult` to the existing `ParseResult` until all callers migrate.
- The provider scans complete spans before calculating any range; it never accepts a base match when temporal text remains inside the same phrase.

- [ ] **Step 1: Write failing tests for the three current review cases**

Add provider tests asserting:

```java
assertThat(provider.resolve("今天上午能耗", NOW).status())
        .isEqualTo(TimeIntentResult.Status.PARSED);
assertThat(provider.resolve("本月15号能耗", NOW).timeRange())
        .isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-14T16:00:00Z"),
                Instant.parse("2026-08-15T16:00:00Z")));
assertThat(provider.resolve("过去24个小时能耗", NOW).timeRange())
        .isEqualTo(new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T16:00:00Z"), NOW));
assertThat(provider.resolve("今年上半年能耗", NOW).status())
        .isEqualTo(TimeIntentResult.Status.PARSED);
```

Add fail-closed assertions for an unmatched temporal qualifier and multiple ranges. Use `MTR-2026-08-01表计的能耗` as the entity-boundary regression.

- [ ] **Step 2: Run provider tests to verify RED**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=FiniteGrammarTimeIntentProviderTest,TimeRangeParserTest' test
```

Expected: the new tests fail because `个小时`, `15号`, day-part composition, and year/half-year composition are not complete provider expressions.

- [ ] **Step 3: Implement complete-span scanning and composition**

Implement the provider in this order:

1. Scan entity identifiers through `QuestionTokenScanner` and exclude date-shaped spans inside them.
2. Match longest expressions first: date ranges, year/half-year, day-part, qualified month-day, qualified week, rolling duration, then base periods.
3. Accept `日|号` and `个?小时` in the corresponding grammar.
4. Track every temporal cue span. If a cue such as `上午`, `15号`, or `上半年` is left outside the selected expression, return `UNSUPPORTED` instead of returning the base period.
5. Return `MULTIPLE` when two complete non-overlapping ranges remain.
6. Build `TimeIntent` and calculate `QueryPlan.TimeRange` with `Asia/Shanghai` and the supplied `Instant`.

Keep all date validation fail-closed: invalid dates, invalid day-of-month, and reversed ranges return `UNSUPPORTED`.

- [ ] **Step 4: Run provider and parser tests to verify GREEN**

Run the focused command again. Expected: all provider, parser, entity-boundary, and existing time tests pass.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProvider.java src/main/java/com/example/smartpark/analytics/agent/TimeRangeParser.java src/test/java/com/example/smartpark/analytics/agent/FiniteGrammarTimeIntentProviderTest.java src/test/java/com/example/smartpark/analytics/agent/TimeRangeParserTest.java
git commit -m "fix: reject incomplete temporal expressions"
```

### Task 3: Route the graph through the provider result

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/agent/TimeConstraintResolver.java`
- Modify: `src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java`
- Modify: `src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java`

**Interfaces:**
- `TimeConstraintResolver.resolve(TimeIntentResult result, Instant now, int lookbackDays)` returns a resolved range and `QueryPlan.TimeRangeSource` or throws for non-parsed/non-none statuses.
- `OperationsAnalysisGraph` receives one `TimeIntentProvider` in its constructor; the existing constructor delegates to `FiniteGrammarTimeIntentProvider`.

- [ ] **Step 1: Write failing graph tests**

Add real PostgreSQL graph tests asserting:

- `今天上午能耗`, `本月15号能耗`, and `过去24个小时能耗` complete with exact ranges and `EXPLICIT_USER_RANGE`.
- `今年上半年能耗` completes as one range rather than failing as `MULTIPLE`.
- An intentionally unsupported residual expression fails with zero SQL-generation invocations.
- A question with no temporal cue retains `DEFAULT_METRIC_LOOKBACK`.

- [ ] **Step 2: Run graph tests to verify RED**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=OperationsAnalysisGraphTest' test
```

Expected: new cases fail because the graph still calls `TimeRangeParser` directly and does not use the provider/resolver contract.

- [ ] **Step 3: Implement resolver and graph wiring**

Move status handling into `TimeConstraintResolver`. In `understandQuestion`, resolve once from the original question and store the result in `RunContext`. In `buildQueryPlan`, use only the resolver's range/source pair. Preserve clarification snapshots and the existing six-argument `QueryPlan` compatibility constructor.

- [ ] **Step 4: Run graph and model tests to verify GREEN**

Run:

```powershell
.\mvnw.cmd -q '-Dtest=OperationsAnalysisGraphTest,LlmAnalyticsModelClientTest,QueryPlanTest' test
```

Expected: all graph, model-contract, and query-plan tests pass; unsupported expressions do not call SQL generation.

- [ ] **Step 5: Commit**

```powershell
git add -- src/main/java/com/example/smartpark/analytics/agent/TimeConstraintResolver.java src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java
git commit -m "refactor: route analytics time constraints through provider"
```

### Task 4: Full verification and PR20 review follow-through

**Files:**
- Modify only if verification exposes a regression in the files from Tasks 1-3.

- [ ] **Step 1: Run complete local verification**

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
git status --short
```

Expected: backend tests pass with no failures, frontend tests/build pass, and only intended commits/files are present.

- [ ] **Step 2: Push the isolated branch and wait for current CI**

```powershell
git push origin codex/fix-analytics-time-range
gh run list --branch codex/fix-analytics-time-range --limit 3
$runId = gh run list --branch codex/fix-analytics-time-range --limit 1 --json databaseId --jq '.[0].databaseId'
gh run watch $runId --exit-status
```

Use the latest run head SHA, not an earlier successful run, as evidence.

- [ ] **Step 3: Re-read all current PR20 threads**

Confirm the three latest unresolved threads are covered by tests and the current head:

- residual qualifier: `今天上午`, `本月15号`;
- `个小时` duration;
- year-qualified half-year composition.

- [ ] **Step 4: Reply in each original thread and resolve only after CI is green**

Reply with the exact commit and test evidence in the original review threads. Resolve only the threads covered by the final head; leave any newly created or unrelated thread open.

- [ ] **Step 5: Final status check**

```powershell
gh pr view 20 --json state,headRefOid,mergeable,mergeStateStatus,statusCheckRollup
git status --short
```

Report code tests, remote CI, review resolution, merge, and deployment as separate facts. Do not merge or deploy.
