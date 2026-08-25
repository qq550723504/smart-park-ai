# PR #11 P1 Contract Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close all eighteen current P1 findings by making SQL, result-summary,
expert-synthesis, database privilege, demo-fixture, and clarification-resume
contracts complete and lossless, and by making expert entity scope and shipped
demo questions server-verifiable.

**Architecture:** Reuse JSqlParser to extract structural relation and query-shape evidence, then compare the full supported shape to `QueryPlan`. Ground summaries in actual dimension columns and rows, derive synthesis completeness from every validated supported finding, and add a forward-only Flyway V2 migration that enforces the already-approved dedicated analytics database boundary.

**Tech Stack:** Java 17, Spring Boot 4, JSqlParser, PostgreSQL 16, Flyway, JUnit 5, AssertJ, Testcontainers, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-25-pr11-p1-contract-closure-design.md`

## Global Constraints

- Preserve public REST, SSE, `QueryPlan`, `TabularResult`, and `Synthesis` DTO shapes.
- Reuse the existing JSqlParser dependency; do not write a replacement SQL parser.
- The current `QueryPlan` supports exactly one physical fact-view occurrence and no joins.
- Ordinary runtime filters remain unsupported until a typed question-to-filter contract exists; unplanned predicates fail closed.
- Do not edit the already-applied `V1__analytics_readonly_schema.sql`; add V2.
- Analytics uses a dedicated PostgreSQL database. Shared-database compatibility is not a supported security boundary.
- Write each regression test first and observe the expected failure before production changes.
- Stage explicit paths only; do not include unrelated work or remaining P2 findings.

---

### Task 1: Lossless relation identity in both SQL guards

**Files:**
- Create: `src/main/java/com/example/smartpark/analytics/sql/SqlRelationName.java`
- Modify: `src/main/java/com/example/smartpark/analytics/sql/SqlAstGuard.java`
- Modify: `src/main/java/com/example/smartpark/analytics/sql/SqlPlanGuard.java`
- Modify: `src/test/java/com/example/smartpark/analytics/sql/SqlAstGuardTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/sql/SqlPlanGuardTest.java`

**Interfaces:**
- Consumes: JSqlParser `Table.getSchemaName()` and `Table.getName()` components.
- Produces: package-private `record SqlRelationName(String schema, String relation)` with `from(Table)` and `parseCatalogName(String)` factories; structural equality shared by both guards.

- [ ] **Step 1: Write failing identifier and occurrence tests**

Add direct behavior tests:

```java
@Test
void rejectsSingleQuotedIdentifierThatOnlyLooksQualified() {
    assertThatThrownBy(() -> SqlAstGuard.validate(
            "SELECT SUM(kwh) FROM \"analytics.v_energy_hourly\" "
                    + "WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100"))
            .isInstanceOf(UnsafeSqlException.class)
            .hasMessageContaining("白名单");
}

@Test
void acceptsSeparatelyQuotedSchemaAndViewComponents() {
    assertThatCode(() -> SqlAstGuard.validate(
            "SELECT SUM(kwh) FROM \"analytics\".\"v_energy_hourly\" "
                    + "WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100"))
            .doesNotThrowAnyException();
}

@Test
void rejectsRepeatedOccurrenceOfThePlannedFactView() throws Exception {
    QueryPlan plan = plan("energy_kwh");
    ValidatedSql sql = SqlAstGuard.validate("""
            SELECT e.building_id, SUM(e.kwh)
            FROM analytics.v_energy_hourly e
            JOIN analytics.v_energy_hourly duplicate
              ON duplicate.building_id = e.building_id
            WHERE e.hour_ts >= :fromTs AND e.hour_ts < :toTs
            GROUP BY e.building_id LIMIT 100""");
    assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
            .isInstanceOf(UnsafeSqlException.class)
            .hasMessageContaining("occurrence");
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B '-Dtest=SqlAstGuardTest,SqlPlanGuardTest' test
```

Expected: the dotted quoted identifier is accepted and the self-join is not rejected for source multiplicity.

- [ ] **Step 3: Implement structural relation identity**

`SqlRelationName.from(Table)` must normalize each component independently:

```java
record SqlRelationName(String schema, String relation) {
    static SqlRelationName from(Table table) {
        return new SqlRelationName(component(table.getSchemaName()), component(table.getName()));
    }

    boolean isQualified() {
        return !schema.isEmpty() && !relation.isEmpty();
    }
}
```

Unquoted components fold to lower case. Double-quoted components remove only their own outer quotes, unescape doubled quotes, and preserve case. An absent schema stays absent. Reject backtick identifiers for PostgreSQL rather than normalizing them.

Replace `TablesNamesFinder`/quote-deleting whitelist comparison in `SqlAstGuard` with a walk over each `PlainSelect` FROM/JOIN `Table`. Resolve exact CTE aliases first; validate every remaining physical relation against the structural whitelist.

Change `SqlPlanGuard.Branch` physical sources from `Set<String>` to `List<SqlRelationName>` so occurrence counts are preserved across consumed branches.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused command from Step 2 and `git diff --check`, then stage the five explicit paths and commit:

```powershell
git commit -m "fix: preserve SQL relation identity"
```

---

### Task 2: Exact QueryPlan query-shape comparison

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/sql/SqlPlanGuard.java`
- Modify: `src/test/java/com/example/smartpark/analytics/sql/SqlPlanGuardTest.java`

**Interfaces:**
- Consumes: lossless `Branch` relation occurrences from Task 1 and existing JSqlParser expression nodes.
- Produces: exact result dimension/grouping equality and an exhaustively consumed source-predicate contract.

- [ ] **Step 1: Write failing plan-equivalence tests**

Add three tests whose expected values are literal and independent of implementation helpers:

```java
@Test
void rejectsMissingRequestedDimension() throws Exception {
    QueryPlan plan = plan("energy_kwh");
    ValidatedSql sql = SqlAstGuard.validate("""
            SELECT SUM(kwh) FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs LIMIT 100""");
    assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
            .isInstanceOf(UnsafeSqlException.class)
            .hasMessageContaining("building_id");
}

@Test
void rejectsPredicateAbsentFromThePlan() throws Exception {
    QueryPlan plan = totalPlan("energy_kwh");
    ValidatedSql sql = SqlAstGuard.validate("""
            SELECT SUM(kwh) FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs
              AND building_id = 'B1' LIMIT 100""");
    assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
            .isInstanceOf(UnsafeSqlException.class)
            .hasMessageContaining("计划之外");
}

@Test
void rejectsExtraPredicateOnAProjectedDimension() throws Exception {
    QueryPlan plan = plan("energy_kwh");
    ValidatedSql sql = SqlAstGuard.validate("""
            SELECT building_id, SUM(kwh) FROM analytics.v_energy_hourly
            WHERE hour_ts >= :fromTs AND hour_ts < :toTs
              AND building_id = 'B1'
            GROUP BY building_id LIMIT 100""");
    assertThatThrownBy(() -> SqlPlanGuard.validate(sql, plan))
            .isInstanceOf(UnsafeSqlException.class)
            .hasMessageContaining("计划之外");
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B '-Dtest=SqlPlanGuardTest' test
```

Expected: all three counterexamples are accepted on baseline or fail for a downstream reason rather than plan equivalence.

- [ ] **Step 3: Implement exact dimension and predicate contracts**

Build `projectedDimensions` and `groupedDimensions` from structural `Column` nodes and require both sets to equal normalized `plan.dimensions()`. Reject duplicates so sets cannot hide repeated output/grouping items.

For the single source branch, copy top-level conjuncts into a mutable evidence list. Consume exactly one lower time bound, one upper time bound, and each distinct fixed metric condition. After consumption, reject if any term remains:

```java
if (!remainingTerms.isEmpty()) {
    throw reject("查询包含计划之外的结果谓词 " + remainingTerms);
}
```

Reject joins and any physical source occurrence count other than one before predicate checking. Keep the existing fail-closed rejection for multi-view plans and different metric predicate scopes.

- [ ] **Step 4: Verify GREEN and commit**

Run `SqlPlanGuardTest`, then all analytics SQL tests:

```powershell
.\mvnw.cmd -B '-Dtest=com.example.smartpark.analytics.sql.*Test' test
git diff --check
git commit -m "fix: enforce complete analytics query plans"
```

---

### Task 3: Ground summaries and synthesis in complete verified facts

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/agent/AnalysisSummaryValidator.java`
- Modify: `src/test/java/com/example/smartpark/analytics/agent/AnalysisSummaryValidatorTest.java`
- Modify: `src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesizer.java`
- Modify: `src/main/java/com/example/smartpark/collaboration/supervisor/SynthesisValidator.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/supervisor/SupervisorSynthesisTest.java`
- Modify: `src/test/java/com/example/smartpark/collaboration/supervisor/SynthesisValidatorTest.java`

**Interfaces:**
- Consumes: `QueryPlan.dimensions()`, `TabularResult.columnNames()/rows()`, and validated `ExpertFinding` values.
- Produces: row-aware dimension/figure validation and exact supported-finding synthesis coverage.

- [ ] **Step 1: Write failing row-binding and synthesis-completeness tests**

Add a summary test with literal rows `HIGH=10` and `LOW=20`; assert `HIGH 20, LOW 10` is rejected with a relationship error while `HIGH 10, LOW 20` passes.

Add a supervisor test with supported ENERGY and DEVICE findings where model JSON selects only ENERGY; assert rejection mentions all supported findings. Add a positive test selecting both and asserting the exact stable conclusion plus the exact union of evidence references.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B '-Dtest=AnalysisSummaryValidatorTest,SupervisorSynthesisTest,SynthesisValidatorTest' test
```

Expected: the swapped nonnumeric dimension values pass and the partial supported-domain synthesis passes.

- [ ] **Step 3: Implement row-aware dimension facts**

Locate each plan dimension in result columns case-insensitively and reject a missing dimension column. For each row, retain normalized dimension values and normalized numeric values. Match only actual dimension values in conclusion clauses using `Pattern.quote` with Unicode letter/number boundaries. If a clause contains recognized dimension values and figures, require one compatible real row to contain all of them.

Delete `DIGIT_IDENTIFIER` and the digit-dependent `figuresByEntity` path. Retain the existing unsupported-number and unverifiable-claim checks.

- [ ] **Step 4: Implement complete supported-finding synthesis**

Derive `expectedSupportedDomains` from findings and require model-selected domains to equal it for `SUPPORTED` status. Require synthesis evidence references to equal the union of references from all supported findings. Keep deterministic conclusion generation in stable `ExpertDomain` order and uncertainty requirements for failed/insufficient findings.

- [ ] **Step 5: Verify GREEN and commit**

Run the focused tests, all analytics-agent/collaboration-supervisor tests, and `git diff --check`; stage only the six paths and commit:

```powershell
git commit -m "fix: require complete grounded summaries"
```

---

### Task 4: Forward-only dedicated-database privilege hardening

**Files:**
- Create: `src/main/resources/db/migration/V2__harden_analytics_readonly_role.sql`
- Modify: `src/test/java/com/example/smartpark/analytics/AnalyticsSchemaMigrationTest.java`
- Modify: `README.md`

**Interfaces:**
- Consumes: V1-created `smartpark_analytics_ro`, raw tables, and four whitelisted views.
- Produces: a V1-to-V2 upgrade that removes inherited `PUBLIC` business-object access and documents the dedicated database requirement.

- [ ] **Step 1: Write a failing real PostgreSQL upgrade test**

Start a method-local PostgreSQL Testcontainer so this upgrade scenario is
independent of the class-level database already migrated by other tests. Use
Flyway `target("1")` to apply V1 only, create
`public.public_leak(secret text)`, insert a literal secret, and
`GRANT SELECT ON public.public_leak TO PUBLIC`. Verify the read-only login can
read it before V2 so the test proves the vulnerability. Migrate to latest,
reconnect, and assert:

- the login cannot read `public.public_leak`;
- the login cannot create in `public` or `analytics`;
- the login can still select every approved analytics view;
- the login cannot select raw analytics tables.

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\mvnw.cmd -B '-Dtest=AnalyticsSchemaMigrationTest' test
```

Expected: latest remains V1 and the `PUBLIC`-granted table is readable.

- [ ] **Step 3: Implement V2**

Create a versioned migration that executes, in dependency-safe order:

```sql
REVOKE ALL ON DATABASE current_database() FROM PUBLIC;
REVOKE ALL ON SCHEMA public, analytics FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public, analytics FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public, analytics FROM PUBLIC;
REVOKE ALL ON ALL FUNCTIONS IN SCHEMA public, analytics FROM PUBLIC;
```

Because PostgreSQL does not accept `current_database()` directly in `REVOKE ON DATABASE`, issue that statement through a `DO` block with `format('%I', current_database())`. Harden role attributes with `ALTER ROLE`, re-grant `CONNECT`, `USAGE` on `analytics`, and `SELECT` on the four views. Revoke direct access to raw tables again.

Update README analytics setup text to require a dedicated database and state that V2 intentionally revokes `PUBLIC` database/schema/object privileges.

- [ ] **Step 4: Verify GREEN and commit**

Run `AnalyticsSchemaMigrationTest`, then all analytics tests and `git diff --check`; stage the three paths and commit:

```powershell
git commit -m "fix: harden analytics database privileges"
```

---

### Task 5: Isolate demo snapshot maintenance from real data

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/AnalyticsConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/analytics/DemoSnapshotRefresher.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/java/com/example/smartpark/analytics/AnalyticsPropertiesTest.java`
- Modify: `src/test/java/com/example/smartpark/analytics/AnalyticsSchemaMigrationTest.java`
- Modify: `README.md`

- [ ] **Step 1: Prove the current job mutates a real aged row and is registered by default**
- [ ] **Step 2: Require explicit demo opt-in and restrict updates to the seven V1 fixture IDs**
- [ ] **Step 3: Verify focused configuration and PostgreSQL tests, then commit**

---

### Task 6: Close runtime identity, disclosure, admission, and entity-scope boundaries

**Files:**
- Modify analytics configuration, understanding/plan/SQL validation, and summary validation.
- Modify collaboration executor admission and expert evidence projection.
- Add focused regression tests for all five new P1 counterexamples.

- [ ] **Step 1: Require the migrated `smartpark_analytics_ro` runtime identity**
- [ ] **Step 2: Recognize unsupported figures adjacent to Chinese prose**
- [ ] **Step 3: Use a bounded run executor and admit before registering a run**
- [ ] **Step 4: Project knowledge evidence to public metadata only**
- [ ] **Step 5: Preserve entity filters from the original question through bound SQL**
- [ ] **Step 6: Verify focused tests and adversarial counterexamples**

---

### Task 7: Full verification and GitHub review closure

**Files:**
- Verify all files changed by Tasks 1-5.
- No unrelated production edits.

**Interfaces:**
- Consumes: all independently verified contract fixes.
- Produces: pushed PR head, technical replies in all eighteen P1 threads, resolved threads, and separately reported CI state.

- [ ] **Step 1: Run full local verification**

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
git status --short --branch
```

Record exact backend test totals, frontend test totals, build warnings, skipped online tests, and commit IDs.

- [ ] **Step 2: Re-fetch all PR review threads**

Paginate GitHub GraphQL `reviewThreads(first:100, after:...)` to exhaustion and
confirm the eighteen targeted thread IDs are current. Re-evaluate any newly
added P1 before pushing; do not resolve outdated or unrelated comments by
assumption.

- [ ] **Step 3: Push the explicit branch tip**

Push local HEAD to `origin/codex/smart-park-p1` without force:

```powershell
git push origin HEAD:codex/smart-park-p1
```

- [ ] **Step 4: Reply and resolve the eighteen P1 threads**

Reply inside each original review thread with the root contract changed and the focused test that proves it. Resolve only after the push succeeds and GitHub shows the target commit.

- [ ] **Step 5: Verify remote state**

Check PR head SHA, all CI checks, and the complete unresolved-thread list. Report local verification, CI, review resolution, merge state, and deployment state separately. Do not call the PR complete while checks are pending.
