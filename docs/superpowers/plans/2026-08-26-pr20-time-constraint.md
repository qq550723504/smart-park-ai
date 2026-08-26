# PR20 Time Constraint Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Make user-specified time constraints deterministic, boundary-aware, and fail-closed so qualified periods, hour windows, and date-shaped entity identifiers cannot produce incorrect SQL ranges.

**Architecture:** Keep server-side time parsing as the authority. Split text span scanning from finite time grammar evaluation, carry an explicit TimeRangeSource in QueryPlan, and allow the metric default lookback only when the parser returns NONE. The model remains advisory and cannot introduce or widen a server time range.

**Tech Stack:** Java 17, java.time, JUnit 5, AssertJ, Maven Wrapper, existing Spring AI Alibaba StateGraph and JSqlParser SQL plan guard.

**Spec:** docs/superpowers/specs/2026-08-26-pr20-time-constraint-design.md

## Global Constraints

- Preserve public REST/SSE shapes and existing six-argument QueryPlan construction compatibility.
- Do not add a general-purpose NLP dependency; use the existing Java time APIs and a bounded grammar.
- NONE is the only parser status that permits metric default lookback.
- UNSUPPORTED, MULTIPLE, and AMBIGUOUS must stop before SQL generation.
- Keep changes isolated to the PR20 worktree and stage explicit paths only.
- Do not merge, deploy, or modify production configuration.

---

### Task 1: Add shared question token boundaries

**Files:**
- Create: src/main/java/com/example/smartpark/analytics/model/QuestionTokenScanner.java
- Test: src/test/java/com/example/smartpark/analytics/model/QuestionTokenScannerTest.java
- Modify: src/main/java/com/example/smartpark/analytics/model/QueryPlan.java

**Interfaces:**
- Produces QuestionTokenScanner.entityIdentifiers(String) returning ordered immutable token spans.
- Produces QuestionTokenScanner.isStandaloneSpan(String, int, int) for date candidate boundary checks.
- QueryPlan uses the shared entity scanner instead of its private ENTITY_IDENTIFIER pattern.

- [ ] Step 1: Write the failing tests

Add tests for:

~~~java
assertThat(QuestionTokenScanner.entityIdentifiers(
        "MTR-2026-08-01表计的能耗").stream().map(QuestionTokenScanner.Token::text))
        .containsExactly("MTR-2026-08-01");
assertThat(QuestionTokenScanner.isStandaloneSpan(
        "MTR-2026-08-01表计", 4, 14)).isFalse();
assertThat(QuestionTokenScanner.isStandaloneSpan(
        "2026-08-01能耗", 0, 10)).isTrue();
~~~

- [ ] Step 2: Run the focused test and verify RED

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=QuestionTokenScannerTest' test
~~~

Expected: test compilation fails because QuestionTokenScanner does not exist.

- [ ] Step 3: Write the minimal scanner

Define:

~~~java
public record Token(String text, int start, int end) {}

static List<Token> entityIdentifiers(String question)
static boolean isStandaloneSpan(String question, int start, int end)
~~~

Use the existing identifier grammar from QueryPlan:

~~~text
(?i)(?<![A-Za-z0-9_-])(?:[A-Za-z]\d+|[A-Za-z][A-Za-z0-9]*(?:-[A-Za-z0-9]+)+)(?![A-Za-z0-9_-])
~~~

Keep spans half-open and return immutable ordered results. Replace the private QueryPlan matcher with this scanner without changing plan validation messages.

- [ ] Step 4: Run the focused test and verify GREEN

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=QuestionTokenScannerTest' test
~~~

Expected: all scanner tests pass.

- [ ] Step 5: Commit the isolated boundary change

~~~powershell
git add -- src/main/java/com/example/smartpark/analytics/model/QuestionTokenScanner.java src/test/java/com/example/smartpark/analytics/model/QuestionTokenScannerTest.java src/main/java/com/example/smartpark/analytics/model/QueryPlan.java
git commit -m "refactor: share analytics question token boundaries"
~~~

### Task 2: Extend the finite time grammar

**Files:**
- Modify: src/main/java/com/example/smartpark/analytics/agent/TimeRangeParser.java
- Test: src/test/java/com/example/smartpark/analytics/agent/TimeRangeParserTest.java

**Interfaces:**
- Preserve TimeRangeParser.parse(String, Instant).
- Extend TimeRangeParser.Status with AMBIGUOUS only if a candidate has multiple valid interpretations.
- Return the original expression and a safe reason for unsupported or ambiguous spans.

- [ ] Step 1: Write the failing parser tests

Add tests with NOW = Instant.parse("2026-08-24T00:00:00Z") for:

~~~java
assertThat(parser.parse("本周三能耗", NOW).timeRange()).isEqualTo(
        new QueryPlan.TimeRange(
                Instant.parse("2026-08-18T16:00:00Z"),
                Instant.parse("2026-08-19T16:00:00Z")));
assertThat(parser.parse("上月15日能耗", NOW).timeRange()).isEqualTo(
        new QueryPlan.TimeRange(
                Instant.parse("2026-07-14T16:00:00Z"),
                Instant.parse("2026-07-15T16:00:00Z")));
assertThat(parser.parse("过去24小时能耗", NOW).timeRange()).isEqualTo(
        new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T00:00:00Z"),
                NOW));
assertThat(parser.parse("近12小时告警", NOW).timeRange()).isEqualTo(
        new QueryPlan.TimeRange(
                Instant.parse("2026-08-23T12:00:00Z"),
                NOW));
assertThat(parser.parse("MTR-2026-08-01表计的能耗", NOW).status())
        .isEqualTo(TimeRangeParser.Status.NONE);
~~~

- [ ] Step 2: Run the parser tests and verify RED

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=TimeRangeParserTest' test
~~~

Expected: the new cases fail because the current parser truncates 本周三 and 上月15日, lacks 小时, and scans the date substring inside the meter identifier.

- [ ] Step 3: Implement boundary-aware complete-span matching

Update candidate patterns and matching order:

1. Match complete date ranges and complete dates.
2. Match qualified periods before base periods:
   - 本周[一二三四五六日天末]
   - 上周[一二三四五六日天末]
   - 本月\d{1,2}日
   - 上月\d{1,2}日
3. Add 小时 to relative duration units.
4. Reject date candidates whose span is inside an entity token using QuestionTokenScanner.isStandaloneSpan.
5. Preserve existing supported expressions and return UNSUPPORTED rather than falling through when an explicit candidate is incomplete.

Use the park timezone for calendar boundaries and the current Instant for hour durations. Validate day-of-month with LocalDate.of; invalid values return UNSUPPORTED.

- [ ] Step 4: Run parser and graph tests

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=TimeRangeParserTest,OperationsAnalysisGraphTest' test
~~~

Expected: all parser and analysis graph tests pass, including existing 上周三 and 上周末 cases.

- [ ] Step 5: Commit the grammar slice

~~~powershell
git add -- src/main/java/com/example/smartpark/analytics/agent/TimeRangeParser.java src/test/java/com/example/smartpark/analytics/agent/TimeRangeParserTest.java
git commit -m "fix: parse qualified and hourly time ranges"
~~~

### Task 3: Carry time-range source through QueryPlan

**Files:**
- Modify: src/main/java/com/example/smartpark/analytics/model/QueryPlan.java
- Modify: src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java
- Test: src/test/java/com/example/smartpark/analytics/model/QueryPlanTest.java
- Test: src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java

**Interfaces:**
- Add QueryPlan.TimeRangeSource with EXPLICIT_USER_RANGE and DEFAULT_METRIC_LOOKBACK.
- Add timeRangeSource() as a record component.
- Retain the existing six-argument constructor and make it delegate to DEFAULT_METRIC_LOOKBACK.
- Update Graph query-plan construction to pass the actual source from the parser result.

- [ ] Step 1: Write failing source-invariant tests

Add assertions that:

~~~java
assertThat(explicitPlan.timeRangeSource())
        .isEqualTo(QueryPlan.TimeRangeSource.EXPLICIT_USER_RANGE);
assertThat(defaultPlan.timeRangeSource())
        .isEqualTo(QueryPlan.TimeRangeSource.DEFAULT_METRIC_LOOKBACK);
~~~

Add a Graph regression assertion that 过去24小时能耗 produces an explicit source and never invokes default lookback logic.

- [ ] Step 2: Run focused tests and verify RED

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=QueryPlanTest,OperationsAnalysisGraphTest' test
~~~

Expected: compilation or assertion failures because timeRangeSource() does not yet exist.

- [ ] Step 3: Implement source propagation

Add the enum and record component while retaining the compatibility constructor. In OperationsAnalysisGraph, map parser status as follows:

~~~java
TimeRangeSource source = parsedTime.status() == TimeRangeParser.Status.PARSED
        ? TimeRangeSource.EXPLICIT_USER_RANGE
        : TimeRangeSource.DEFAULT_METRIC_LOOKBACK;
~~~

Only construct the default source for Status.NONE; unsupported, multiple, and ambiguous statuses must return before buildQueryPlan.

- [ ] Step 4: Run focused tests and full backend tests

Run:

~~~powershell
./mvnw.cmd -q '-Dtest=QueryPlanTest,OperationsAnalysisGraphTest,TimeRangeParserTest' test
./mvnw.cmd -B test
~~~

Expected: all tests pass with no failures; the known external DashScope smoke test may remain skipped.

- [ ] Step 5: Commit the source-contract slice

~~~powershell
git add -- src/main/java/com/example/smartpark/analytics/model/QueryPlan.java src/main/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraph.java src/test/java/com/example/smartpark/analytics/model/QueryPlanTest.java src/test/java/com/example/smartpark/analytics/agent/OperationsAnalysisGraphTest.java
git commit -m "feat: record query plan time range source"
~~~

### Task 4: Verify SQL-plan and PR comment coverage

**Files:**
- Modify only if required by failing tests: src/main/java/com/example/smartpark/analytics/sql/SqlPlanGuard.java
- Test only if required by failing tests: src/test/java/com/example/smartpark/analytics/sql/SqlPlanGuardTest.java
- No new production changes to review-thread state are allowed in this task.

**Interfaces:**
- Confirm SqlPlanGuard continues to validate exact QueryPlan.timeRange values and remains independent of parser implementation.
- Confirm the explicit source is visible in the plan used by SQL generation and execution.

- [ ] Step 1: Run the full verification set

~~~powershell
./mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
git status --short
~~~

- [ ] Step 2: Inspect the three latest review threads

Use the current PR head and complete GraphQL thread data. For each thread verify:

~~~text
qualified periods -> covered by parser tests and source propagation
date-shaped identifiers -> covered by scanner and parser tests
hourly ranges -> covered by parser and Graph integration tests
~~~

- [ ] Step 3: Reply in the original threads

Reply with exact test and commit evidence using the original inline comment endpoint. Do not post top-level comments. Resolve a thread only after its current head is covered and CI is green.

- [ ] Step 4: Push and verify remote CI

~~~powershell
git push origin codex/fix-analytics-time-range
gh pr checks 20
~~~

Do not claim completion until both Backend tests and Frontend build are successful on the new head.

- [ ] Step 5: Verify final clean state

Review replies and GitHub resolution are remote actions and do not create a local commit. The final local check must show a clean worktree.
