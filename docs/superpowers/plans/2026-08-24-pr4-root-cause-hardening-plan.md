# PR #4 Root-Cause Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:test-driven-development and superpowers:verification-before-completion. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish single ownership for knowledge contracts, fail fast on inconsistent index state, bound embedding inputs, and keep customer workflow publication and intent handling scoped to the state and contract they protect so future RAG changes do not recreate review findings.

**Architecture:** `KnowledgeDocument` owns the invariant for identifiers and bounded knowledge metadata; adapters consume already-valid domain objects and only enforce storage-specific limits. RAG seed loading rejects duplicate IDs before indexing. Customer request coordination remains keyed by idempotency/session state rather than the workflow instance monitor; handoff ticket and session publication share a state write lock, and deterministic repair handoff bypasses retrieval failure, with tests defining the concurrency and fallback contracts.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI `VectorStore`, JUnit 5, AssertJ, Maven Wrapper.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-rag-boundary-and-model-contract-design.md`

## Global Constraints

- Preserve existing HTTP paths, JSON fields, Mock behavior, RAG configuration, and safe-handoff behavior.
- Do not add a persistent database, distributed lock, or external service in this hardening slice.
- Domain model validation is the source of truth; adapter and DTO validation may remain defense-in-depth but must not define conflicting rules.
- Every production-code change starts with a failing focused test.
- Keep each slice independently testable and stage only explicit paths.

### Task 1: Make knowledge document invariants canonical

**Files:**
- Modify: `src/main/java/com/example/smartpark/model/common/KnowledgeDocument.java`
- Delete: `src/main/java/com/example/smartpark/port/knowledge/KnowledgeMatch.java`
- Test: `src/test/java/com/example/smartpark/model/common/KnowledgeDocumentTest.java`

- [x] Add failing tests for control-character and overlong document IDs at the `KnowledgeDocument` constructor boundary.
- [x] Run `KnowledgeDocumentTest` and confirm failure comes from the missing identifier invariant.
- [x] Reuse `PublicMetadata.requireIdentifier` in `KnowledgeDocument`; remove the legacy domainless constructor and duplicate `KnowledgeMatch` type.
- [x] Run the focused model and citation tests and confirm they pass.

### Task 2: Reject inconsistent RAG seed state before indexing

**Files:**
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapter.java`
- Test: `src/test/java/com/example/smartpark/adapter/rag/RagKnowledgeAdminIndexTest.java`

- [x] Add a failing test showing duplicate seed IDs are rejected rather than silently replacing metadata after multiple vector writes.
- [x] Validate seed IDs as a complete set before calling `save` so construction is atomic from the adapter's perspective.
- [x] Run RAG admin/index tests, including move, activation, rollback, and duplicate-seed cases.

### Task 3: Add boundary guardrails

**Files:**
- Create: `src/test/java/com/example/smartpark/architecture/KnowledgePortBoundaryTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/mock/KnowledgeManagementTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapterTest.java`

- [x] Add a source-level guard that forbids the domainless search overload and duplicate ranked-match type.
- [x] Keep domain isolation, inactive-document exclusion, bounded ranked results, vector rollback, and embedding-input behavior covered by adapter tests.
- [x] Run Mock and RAG adapter tests with real adapters and local test doubles.

### Task 4: Revalidate workflow state coordination

**Files:**
- Modify: `src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java`
- Modify: `src/test/java/com/example/smartpark/workflow/CustomerServiceWorkflowConcurrencyTest.java`
- Modify: `src/test/java/com/example/smartpark/workflow/CustomerServiceWorkflowTest.java`

- [x] Add a regression test that same-session replies with different idempotency keys cannot lose a conversation update.
- [x] Add a regression test that identical concurrent idempotency requests invoke retrieval and answer only once.
- [x] Replace idempotency locking with completion reservations; keep session synchronization scoped to the session coordinator and ensure unrelated requests progress while a provider call is blocked.
- [x] Run the workflow concurrency, idempotency, answer-failure, and port tests.

### Task 5: Full verification and handoff

- [x] Run `git diff --check`.
- [x] Run `./mvnw test` and record 234 tests, 0 failures, and 1 skip.
- [x] Run `npm.cmd run build` under `ui`.
- [x] Review the complete diff for unrelated changes, commit each independently verified slice, and push the PR branch; remote CI/review state remains to be checked after this push.

### Task 6: Close the newly exposed publication and input-boundary gaps

**Files:**
- Modify: `src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java`
- Modify: `src/main/java/com/example/smartpark/model/common/KnowledgeDocument.java`
- Modify: `src/main/java/com/example/smartpark/model/common/PublicMetadata.java`
- Modify: `src/main/java/com/example/smartpark/adapter/rag/RagKnowledgeAdapter.java`
- Modify: `src/main/java/com/example/smartpark/web/KnowledgeAdminController.java`
- Test: `src/test/java/com/example/smartpark/workflow/CustomerServiceWorkflowConcurrencyTest.java`
- Test: `src/test/java/com/example/smartpark/workflow/CustomerServiceAnswerFailureTest.java`
- Test: `src/test/java/com/example/smartpark/model/common/KnowledgeDocumentTest.java`

- [x] Add a concurrency regression test proving ticket listing cannot delete a handoff between ticket creation and session publication.
- [x] Publish handoff ticket and session under one state write lock while leaving provider calls outside the lock.
- [x] Make tag count, per-tag length, total tag length, and assembled embedding text bounded before vector-store writes.
- [x] Add a regression test proving repair confirmation is preserved when knowledge retrieval fails; bypass retrieval for deterministic repair intent.
- [x] Run focused tests, full backend tests, frontend build, and `git diff --check`.

### Task 7: Keep the answer-port reason boundary closed

**Files:**
- Modify: `src/main/java/com/example/smartpark/workflow/CustomerServiceWorkflow.java`
- Modify: `docs/architecture.md`
- Test: `src/test/java/com/example/smartpark/workflow/CustomerServiceAnswerFailureTest.java`

- [x] Add a failing regression test for a custom answer port returning internal `RETRIEVAL_UNAVAILABLE` after successful retrieval.
- [x] Reject workflow-only reasons at the workflow port boundary and map the violation to the existing safe contract-failure handoff.
- [x] Document that retrieval-unavailable is workflow-owned and not answer-port selectable.
