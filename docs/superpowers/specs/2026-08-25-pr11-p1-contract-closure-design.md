# PR #11 P1 Contract Closure Design

## 1. Status and scope

- Date: 2026-08-25
- Approved in chat: yes
- Target: all thirteen P1 review threads current on 2026-08-25: the seven query,
  summary, synthesis, privilege, and identifier contract findings; the later
  demo-snapshot integrity finding; the table-valued `FROM` bypass; and the
  clarification-resume metric-loss finding; plus the second-page findings for
  expert assignment scope, resolvable demo questions, and lineage row changes
- Baseline: commit `5281da6`, after the independent P2 round-4 fixes

This slice closes four trust boundaries that currently validate only subsets of
their intended contracts:

1. generated SQL versus the approved `QueryPlan`;
2. generated summaries versus verified rows or expert findings;
3. the analytics database login versus the dedicated-database privilege model.
4. demo-fixture maintenance versus real analytics source data.
5. clarification selections versus the canonical metrics already resolved
   from the original question.
6. supervisor-generated assignments and UI demo questions versus the exact
   entity identifiers the expert tools can query.

It does not implement the remaining P2 comments, add a general-purpose query
compiler, introduce arbitrary joins, or turn the demo runtime into a
multi-instance service.

## 2. Confirmed root causes

### 2.1 SQL validation proves inclusion, not equivalence

`SqlPlanGuard` currently proves that selected expressions are allowed and that
required time/catalog predicates appear somewhere in a consumed source branch.
It does not prove that the complete result-producing query shape equals the
plan. Consequently:

- a requested dimension can be omitted;
- an unplanned narrowing predicate can be added;
- repeated occurrences of the same fact view disappear into a `Set`;
- schema and table boundaries disappear when all identifier quotes are removed.

These are one design defect: query evidence is collected as lossy sets and then
checked using subset relations.

### 2.2 Summary grounding guesses entity syntax

`AnalysisSummaryValidator` recognizes entities only with
`DIGIT_IDENTIFIER`. That happens to cover `B1` and `MTR-2`, but not valid
dimension values such as `HIGH`, `LOW`, `ONLINE`, or Chinese labels. Numeric
existence is therefore checked independently of its actual result row.

`SupervisorSynthesizer` has the same completeness problem in structured form:
the model-selected domain set only has to be a subset of the supervisor plan.
A successfully verified finding can be silently omitted.

### 2.3 The migration contradicts the approved deployment boundary

The approved P1 specification requires a dedicated PostgreSQL analytics
database. V1 instead avoids hardening `PUBLIC` because it assumes the database
may be shared. Every PostgreSQL login implicitly receives privileges granted to
`PUBLIC`; revoking privileges only from `smartpark_analytics_ro` cannot remove
those inherited privileges.

V1 may already have been applied, so modifying it is not a valid upgrade path.
The correction must be a new versioned migration.

### 2.4 Demo maintenance has no provenance boundary

`DemoSnapshotRefresher` is registered whenever analytics is enabled and updates
every snapshot older than two hours. It cannot distinguish the seven V1 demo
fixtures from real device snapshots, so a presentation aid mutates production
facts and makes stale devices appear current.

## 3. Chosen architecture

### 3.1 Lossless relation identity

Add one package-private relation identity abstraction used by both SQL guards.
It is constructed from JSqlParser `Table` schema and name components, not from
`TablesNamesFinder` strings.

- Unquoted PostgreSQL identifiers are case-folded to lower case.
- Double-quoted components preserve case and are unescaped component by
  component.
- A physical analytics view must have exactly two components: schema and view.
- The single quoted identifier `"analytics.v_energy_hourly"` has no schema
  component and cannot equal `analytics.v_energy_hourly`.
- CTE aliases remain logical relations and are resolved separately from
  physical view occurrences.

This continues to use the existing JSqlParser dependency. No SQL parser is
implemented locally.

### 3.2 Exact query-shape contract

For the currently supported single-fact-source `QueryPlan`, derive a lossless
contract and compare the result query against it:

- physical source occurrence multiset: exactly one occurrence of the planned
  fact view;
- result dimensions: projected dimension set equals `plan.dimensions()`;
- grouping dimensions: `GROUP BY` set equals `plan.dimensions()`;
- metric projections: every catalog expression appears exactly as already
  required;
- source predicates: every top-level conjunct is consumed exactly once by an
  approved category.

Approved predicate categories are:

1. the required inclusive `:fromTs` bound;
2. the required exclusive `:toTs` bound;
3. the metric catalog's fixed condition, when present;
4. an explicitly represented plan filter.

The current runtime constructs plans with no ordinary filters. Therefore any
ordinary dimension predicate is rejected fail-closed. This slice does not infer
filters from question text: adding typed filter extraction is a separate
product capability and must not be invented inside a security validator.

The current plan cannot describe or prove arbitrary relational transformations.
The guard therefore accepts only a single direct `SELECT` over one whitelisted
fact view and rejects CTEs, subqueries, joins, `HAVING`, `DISTINCT`, ordering,
offset/fetch, and a `LIMIT` different from the plan. It also rejects duplicate
required predicates, unconsumed predicates, table-valued `FROM` items, and
repeated physical source occurrences. A future richer plan must carry explicit
source-grain, cardinality, ordering, and pagination contracts before those
forms can be accepted.

### 3.3 Row-aware analysis summary grounding

Use `plan.dimensions()` to locate dimension columns in `TabularResult` by exact,
case-insensitive column name. Build row facts from the actual result schema:

```text
dimension tuple -> numeric values in the same row
```

For each conclusion fact, in textual order:

- recognize actual dimension values using escaped token-aware matching;
- find rows compatible with all mentioned dimension values;
- require every numeric figure in that clause to belong to a compatible row;
- classify numeric dimension tokens before later numeric figures;
- reject ambiguous multi-entity clauses and unsupported pairings;
- recognize scientific notation, unit suffixes, and Unicode minus signs;
- exempt row/column counts only in independent metadata clauses without an
  active result dimension.

This removes `DIGIT_IDENTIFIER` as the source of entity truth. Existing global
checks for unsupported numbers, row counts, and unverifiable trend language
remain as defense in depth.

### 3.4 Complete expert synthesis

The set of supported domains is derived from validated findings, not chosen by
the model. A `SUPPORTED` synthesis must cover exactly every `SUPPORTED`
finding. Its conclusion remains the stable-domain-order concatenation of those
verified conclusions.

The model response must declare the same domain set; a subset or superset is
rejected. Evidence references must cover the union of evidence references from
all supported findings. Failed or insufficient findings still require explicit
uncertainty disclosure and do not contribute factual conclusions.

This preserves the public `Synthesis` shape while making finding completeness
server-authoritative.

### 3.5 Dedicated-database privilege boundary

Add `V2__harden_analytics_readonly_role.sql`; do not edit V1.

V2 establishes the approved dedicated-database assumptions:

- revoke database privileges from `PUBLIC`, then grant `CONNECT` only where
  required for the analytics read-only login;
- revoke all privileges on `public` and `analytics` schemas and their objects
  from `PUBLIC`;
- reset the analytics login to non-superuser, non-createdb, non-createrole,
  non-replication and non-bypass-RLS attributes;
- grant the login only `USAGE` on `analytics` and `SELECT` on the four approved
  views;
- retain explicit revocation on raw analytics tables.

The README and configuration comments state that the analytics URL must point
to a dedicated database. Shared-database compatibility is deliberately
removed: PostgreSQL has no per-role `DENY` that can override `PUBLIC` grants.

### 3.6 Explicit demo-fixture refresh boundary

The refresher is absent by default and requires an explicit demo-only
configuration flag. Even when enabled, its update is restricted to the seven
stable device identifiers seeded by V1. This creates two independent gates:
real analytics environments do not register the mutating job, and a mistaken
opt-in cannot rewrite unrelated device rows.

### 3.7 Server-owned expert scope and resolvable demo inputs

The model may select required expert domains and explain the selection, but it
does not own entity scope. The server assigns every selected expert the exact
normalized user question; model-generated assignment text is discarded, and
the validator enforces this invariant. Thus an assignment cannot replace `D1`
with `D2` or omit another concrete identifier.

The shipped default and presets use identifiers that exist in the same mock
tool fixtures (`DEV-ENERGY-001`, `DEV-POWER-001`, `DEV-HVAC-001`, and
`SEC-ACCESS-001`). The UI also states the exact-ID input requirement. General
entity discovery remains a separate capability; the demo no longer advertises
questions its current tools cannot resolve.

## 4. Data flow

```text
Question -> QueryPlan
             |
             v
       expected query shape
             |
model SQL -> JSqlParser AST -> lossless actual shape -> exact comparison
                                                    |
                                                    v
                                              read-only query

TabularResult + QueryPlan dimensions -> row facts -> summary validation

Validated ExpertFindings -> complete supported set -> deterministic synthesis
```

## 5. Failure behavior

- Every contract mismatch raises the existing safe, repairable
  `SQL_POLICY_REJECTED` or `IllegalArgumentException` boundary error.
- SQL still receives at most one model repair attempt.
- Summary rejection preserves the validated SQL and result table without a
  conclusion.
- Synthesis rejection fails the collaboration rather than publishing a partial
  successful answer.
- V2 migration failure prevents analytics startup; it never silently falls back
  to a broader database role.

## 6. Tests

Each production change starts with a focused test that fails on baseline
`5281da6` for the intended reason.

1. `SqlPlanGuardTest`
   - rejects a plan dimension omitted from projection/grouping;
   - rejects an unplanned ordinary predicate;
   - rejects a self-join of the planned fact view.
2. `SqlAstGuardTest`
   - rejects `"analytics.v_energy_hourly"` while retaining valid qualified,
     component-quoted view references.
3. `AnalysisSummaryValidatorTest`
   - rejects swapped figures for nonnumeric dimension values `HIGH` and `LOW`.
4. `SupervisorSynthesisTest`
   - rejects omission of one of two supported findings and verifies complete
     deterministic evidence/conclusion output.
5. `AnalyticsSchemaMigrationTest`
   - exercises a V1-to-V2 upgrade;
   - proves a table granted to `PUBLIC` is not readable by the analytics login;
   - proves the login still cannot create objects and can read only approved
     views.
6. `AnalyticsPropertiesTest` and `AnalyticsSchemaMigrationTest`
   - prove the refresher is absent by default and requires explicit opt-in;
   - prove an aged non-fixture snapshot remains unchanged after a demo refresh.
7. `OperationsAnalysisServiceTest`
   - proves clarification resumes retain canonical metrics already resolved
     from the original question and add only selected unresolved metrics.
8. SQL and summary adversarial regression tests
   - reject table-valued relations, scalar subqueries, CTE/subquery cardinality
     changes, result ordering/offsets, non-exact limits, alias/projection
     ambiguity, metadata collisions, and complete numeric spellings.
9. `SupervisorPlannerTest`, `SupervisorPlanValidatorTest`, and
   `ExpertCollaborationPage.spec.ts`
   - prove model assignment text cannot replace original entities;
   - prove every shipped demo question contains valid seeded tool identifiers.

Final verification:

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
```

## 7. Acceptance criteria

- All thirteen GitHub P1 counterexamples are red on their preceding implementation and
  green after the fix.
- SQL execution is impossible unless the full supported query shape matches the
  plan; no lossy identifier or occurrence normalization remains.
- Nonnumeric dimension values are grounded to figures from the same real row.
- Every supported expert finding appears in the final synthesis.
- An upgraded dedicated analytics database removes inherited `PUBLIC`
  privileges from the runtime login without rewriting V1.
- Public REST/SSE DTOs remain compatible.
- The thirteen GitHub threads receive technical replies and are resolved
  only after local and remote verification.
