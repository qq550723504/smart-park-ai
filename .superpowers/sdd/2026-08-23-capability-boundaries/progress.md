# SDD ledger — plan: docs/superpowers/plans/2026-08-23-capability-boundaries.md

## Setup

The bundled `sdd-workspace` script initially could not run under PowerShell, so an equivalent scratch directory was created manually. Git Bash is now available and the canonical workspace is `.superpowers/sdd/2026-08-23-capability-boundaries/`.

## Preflight scan

| Tasks | Shared file or interface | Finding | Ruling |
|---|---|---|---|
| 1 -> 2 | Moved model, port packages | Tools consume the new capability packages. | Task 2 starts only after Task 1 commit. |
| 1 -> 3 | Common models and ports | The data store needs moved model and port imports. | Task 3 consumes Task 1 package names. |
| 1 -> 4 | Capability ports and models | Adapters implement the ports produced by Task 1. | Task 4 consumes Task 1 package names. |
| 1 -> 5 | Capability port interfaces | Runtime configuration must inject the moved port interfaces. | Task 5 consumes Task 1 and Task 4 outputs. |
| 2 -> 5 | Agent and tool packages | Spring Agent constructor imports must follow Task 2. | Task 5 does not alter callback semantics. |
| 3 -> 4 | MockParkDataStore | Every adapter must share one store instance. | Keep store and adapter constructors public enough for Spring configuration and the test fixture. |
| 4 -> 5 | Mock adapters and port beans | Configuration registers one adapter per port. | No aggregate `MockParkSystem` bean remains after Task 5. |
| 5 -> 6 | Runtime wiring and future security boundary | Security types are compile-time only and must not be wired. | Task 6 adds no security Spring bean or endpoint. |
| 6 -> 7 | README and all migrated files | Final verification covers the complete refactor. | Use the post-design commit range for the final audit. |

| Task | Internal consistency check | Finding | Ruling |
|---|---|---|---|
| 1 | Tests assert package names after moves; listed interfaces keep signatures. | Consistent. | Proceed. |
| 2 | Tool package tests and callback assertions match the existing Agent contract. | Consistent. | Proceed. |
| 3 | Store tests require reset and idempotency methods implemented by extracted store. | Consistent. | `buildWorkOrder` remains package-private and tests stay in `adapter.mock`. |
| 4 | Fixture and adapter tests consume the store and one-port adapter contracts. | Consistent after renaming the migrated test to `MockParkFixtureTest`. | Proceed. |
| 5 | Context test expects exactly one bean for each existing port and no aggregate bean. | Consistent with the proposed configuration. | Proceed. |
| 6 | Security boundary test does not require an adapter or workflow branch. | Consistent. | Proceed. |
| 7 | Commands verify tests, package, whitespace, status, and post-design diff. | Consistent. | Proceed. |

## Rulings

- Ruling: commit the already verified energy scenario and DashScope URL configuration before dispatching Task 1 — this creates a clean refactor baseline and prevents subagents from accidentally staging unrelated pre-existing work; cost if wrong: an extra baseline commit that can be reverted independently.

Task 1: complete (commits db586d9..db586d9, review clean)
Task 2: complete (commits 1bf5ee2..1bf5ee2, review clean)
Task 3: minor (deferred): strict TDD chronology is recorded in the report but not independently reproduced in the diff package.
Task 3: complete (commits e41b342..e41b342, review clean)
Task 4: complete (commits 509d73b..509d73b, review clean)
Task 5: complete (commits c7c3ac5..3fb165f, review clean; follow-up fixes de17b8a and 3fb165f)
Task 6: complete (commits 89f15e0..832747a, review clean; unrelated f579d8d isolated by b201de2; boundary tightening in 2f21ada)

Task 7: final verification complete at HEAD `ea67566`:
- `.\mvnw.cmd clean test`: PASS, 117 tests run, 0 failures, 0 errors, 1 skipped (the existing `DashScopeSmokeTest`).
- `.\mvnw.cmd package -DskipTests`: PASS, produced `target/smart-park-alert-workflow-0.0.1-SNAPSHOT.jar`.
- `git diff --check`: PASS.
