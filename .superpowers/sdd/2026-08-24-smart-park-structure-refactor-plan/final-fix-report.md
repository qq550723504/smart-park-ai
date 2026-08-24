# Smart Park Structure Refactor — Final Fix Report

Date: 2026-08-24

Worktree: `C:\Users\Henry\code\springaialibaba\.worktrees\smart-park-structure-refactor`

Branch: `codex/smart-park-structure-refactor`

## Scope and constraints

- Addressed all four final-review findings in one coherent fix slice.
- Preserved existing HTTP paths, public JSON fields, deterministic Mock fixtures, and customer ticket lifecycle behavior.
- Did not add PostgreSQL, Security, RAG, external integrations, new scenes, or sensitive-data fields.
- Kept customer ports free of Spring, adapter, and database dependencies.
- Did not modify the implementation plan, design spec, or SDD progress ledger.
- Did not touch or merge the main branch.

## Root causes and fixes

### 1. Repair follow-ups returned HTTP 409 instead of transferring to a human

Root cause: `CustomerServiceWorkflow.reply` used only `documents.isEmpty()` to decide human transfer. The active repair knowledge fixture made repair retrieval non-empty, so the workflow attempted to auto-answer `REPAIR`; `answer` has no automatic repair branch and threw `IllegalStateException("Unsupported answered intent: REPAIR")`.

Fix: reply handling now treats `REPAIR` as an unconditional human-transfer intent, matching initial requests and the design requirement. A repair follow-up creates a `WAITING_AGENT` ticket even when repair knowledge exists.

Regression coverage:

- Workflow: `repairFollowUpCreatesHumanTicketEvenWhenRepairKnowledgeExists`.
- HTTP: `repairFollowUpCreatesWaitingAgentTicket` verifies status 200, `intent=REPAIR`, `needsHuman=true`, and `ticket.status=WAITING_AGENT`.

### 2. Idempotency keys were not scoped and historical results were mutable

Root causes:

- `IdempotencyRecord` stored only question/result/time, so the same key and question could be replayed across `handle` and `reply`, or across two reply target sessions.
- `updateIdempotencyResults` bulk-rewrote every historical request result for a session after ticket changes.
- `reply` rejected human-owned sessions before checking whether the call was an exact retry, so a retry of the ticket-creating reply failed after handoff.

Fixes:

- Added immutable `IdempotencyScope` with `IdempotencyOperation.HANDLE` or `REPLY`; reply scope requires its target session ID.
- Reuse validates question first (preserving the existing conflict response for question mismatch), then operation/target scope; mismatched scope fails instead of returning another operation/session's result.
- Removed bulk idempotency-result rewriting. Stored request results remain immutable request-time snapshots.
- Exact reply retries are resolved before the human-handoff refusal. Current ticket state remains available through the current session and ticket query paths.

Regression coverage:

- Cross-operation reuse is rejected.
- Cross-session reply reuse is rejected.
- Handle retry remains `WAITING_AGENT` after the current ticket advances to `ASSIGNED`.
- Reply retry remains `WAITING_AGENT` after the current ticket advances to `ASSIGNED`.
- Existing retry-after-later-reply coverage remains enabled.

### 3. Ticket storage was not canonical and eviction leaked orphan tickets

Root causes:

- `CustomerServiceWorkflow.tickets()` read session snapshots instead of `CustomerTicketPort.list()`.
- Session TTL/capacity eviction removed sessions and idempotency records but did not notify ticket storage.
- `InMemoryCustomerSessionStore.update` recreated a missing/evicted session, allowing stale writes.

Fixes:

- `CustomerTicketPort.list()` is now the source for the API-visible ticket queue; session data is used only to reconstruct the unchanged response shape.
- `CustomerSessionStore.evict(now)` reports expired/capacity-evicted session IDs, including evictions discovered during other store operations.
- `CustomerTicketPort.deleteBySessionId` provides explicit coordinated retention cleanup. Every workflow entry point retires pending session evictions, and ticket queries defensively remove any unreachable ticket.
- Session updates reject unknown/evicted session IDs instead of recreating them.
- Ticket updates reconcile eviction before transition and verify the target session before mutating ticket state, so updating an already-evicted ticket fails without changing retained sessions or tickets.

Regression coverage:

- Port-usage test proves `workflow.tickets()` delegates to `CustomerTicketPort.list()`.
- TTL expiry removes the ticket from the ticket port before a transition attempt.
- Capacity-one workflow keeps only the retained ticket in both API-visible and port lists.
- Updating the evicted ticket fails while the retained ticket/session remain unchanged.
- Store-level test prevents an evicted session from being recreated by `update`.

### 4. Endpoint documentation was incomplete

Fixes:

- README no longer claims there are four HTTP endpoints.
- README now documents `POST /api/knowledge` and `GET /api/feedback`.
- Architecture endpoint table now includes customer reply, customer conversation, and audit endpoints.
- Removed the blank line that split the Markdown endpoint table.
- Synchronized customer idempotency, canonical ticket ownership, and coordinated retention descriptions.

## TDD evidence

All production behavior changes were preceded by regression tests that failed for the expected existing behavior:

- Repair follow-up RED: workflow threw `Unsupported answered intent: REPAIR`; HTTP returned 409 instead of 200.
- Idempotency RED: cross-operation/cross-session calls returned the wrong prior result; ticket updates rewrote the retry result; reply retry failed with the human-agent refusal.
- Ticket ownership/retention RED: `workflow.tickets()` did not call the ticket port, expired/capacity-evicted tickets remained in the adapter, and store update recreated an evicted session.
- A mutation check temporarily removed the idempotency scope comparison; the corrected cross-session test failed, then passed again after restoring the check.

Focused GREEN runs:

- Repair workflow + HTTP: 17 tests, 0 failures/errors.
- Idempotency workflow/store/port/HTTP: 27 tests, 0 failures/errors.
- Canonical ticket, eviction, adapter, boundary, and HTTP set: 33 tests, 0 failures/errors.

## Final verification

- Baseline before edits: `.\mvnw.cmd -B test` — 162 tests, 0 failures, 0 errors, 1 skipped.
- Final backend: `.\mvnw.cmd -B test` — 170 tests, 0 failures, 0 errors, 1 skipped; build success.
- Final frontend: `npm.cmd run build` in `ui` — TypeScript/Vue compilation and Vite build succeeded (1,619 modules transformed).
- `git diff --check` passed.
- Stale documentation scan found no old four-endpoint statement, old Web-package fault injector reference, or stale `ParkContext` package reference.
- Endpoint documentation checks found all required customer reply/conversation/audit, knowledge POST, and feedback GET entries, with no blank line splitting the table.
- Adapter-to-Web scan returned no matches.
- Customer-port Spring/adapter/SQL dependency scan returned no matches.

## Remaining concerns

- The optional DashScope smoke test remains skipped when no real API credential is configured; this is existing intended behavior and the final-review fixes are fully covered offline.
- The frontend build retains its existing Vite warning that the main JavaScript chunk exceeds 500 kB after minification. The build succeeds, and bundle splitting is outside this refactor's scope.
