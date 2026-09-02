# PR #41 Review Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ensure the collaboration center returns the correct 50-item queue for the selected sort mode and keeps the SLA overview visible during background refresh.

**Architecture:** Add an explicit sort mode to `WorkItemQuery`, apply the selected comparator in `CollaborationCenterService` before the limit, and expose it through the collaboration API. The Vue component sends the sort mode whenever filters or sorting change, while the overview is gated only by initial loading or failure.

**Tech Stack:** Java 17, Spring MVC, JUnit 5/AssertJ/Mockito, Vue 3, TypeScript, Vitest, Vue Test Utils.

**Spec:** PR #41 inline review comments `discussion_r3910723384` and `discussion_r3910723390`.

## Global Constraints

- Keep the collaboration endpoint read-only and preserve its existing source/status filters and 1–50 limit.
- Apply sorting before limiting the returned queue.
- Preserve existing rows during background refresh and continue to show an error only when the refresh fails.
- Add regression coverage before production changes and run the relevant Java and UI verification commands.

### Task 1: Server-side selected queue ordering

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/WorkItemQuery.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java`
- Modify: `src/main/java/com/example/smartpark/web/CollaborationCenterController.java`
- Modify: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java`
- Modify: `src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java`
- Modify: `ui/src/types/collaborationCenter.ts`
- Modify: `ui/src/services/workflowApi.ts`
- Modify: `ui/src/components/collaboration/CollaborationCenter.vue`
- Modify: `ui/src/components/collaboration/CollaborationCenter.spec.ts`

**Interfaces:**
- `WorkItemQuery` gains a `SortMode` value with `SLA` and `UPDATED_AT`; defaults use `SLA`.
- `GET /api/collaboration/work-items` accepts `sort=sla|updatedAt` and maps it into `WorkItemQuery`.
- `listCollaborationWorkItems` accepts the selected sort mode and serializes it as the `sort` query parameter.

- [x] **Step 1: Write the failing test** asserting that an overdue older item is returned ahead of a newer on-track item when the requested limit is 1 and sort mode is SLA.
- [x] **Step 2: Run the focused Java test** with `./mvnw.cmd -q -Dtest=CollaborationCenterServiceTest test` and confirm it fails because the query has no sort mode/server-side SLA comparator.
- [x] **Step 3: Implement the minimal query, controller, service, and UI API changes** so the selected comparator runs before `.limit(...)` and sort changes re-fetch the queue.
- [x] **Step 4: Add and run focused controller/UI assertions** for the `sort` parameter and selected sort request behavior.
- [x] **Step 5: Run the focused Java and UI tests** and confirm they pass.

### Task 2: Preserve SLA overview during background refresh

**Files:**
- Modify: `ui/src/components/collaboration/CollaborationCenter.vue`
- Modify: `ui/src/components/collaboration/CollaborationCenter.spec.ts`

**Interfaces:**
- The overview renders whenever the role can read and the last request has not failed; `loading` suppresses it only while there are no cached items.

- [x] **Step 1: Write the failing Vitest regression** that starts a background refresh with cached rows and asserts the existing SLA overview remains visible while the request is pending.
- [x] **Step 2: Run `npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`** and confirm the new test fails because the overview is currently hidden whenever `loading` is true.
- [x] **Step 3: Change the overview condition** to distinguish initial loading from refresh without changing filter-loading behavior.
- [x] **Step 4: Run the focused UI test** and confirm it passes.

### Task 3: Full verification

**Files:**
- Verify only; no additional files expected.

- [x] **Step 1: Run `npm run typecheck` from `ui` and confirm exit code 0.**
- [x] **Step 2: Run `npm run test:unit` from `ui` and confirm all tests pass.**
- [x] **Step 3: Run `npm run build` from `ui` and confirm exit code 0.**
- [x] **Step 4: Run `./mvnw.cmd -q test` from the repository root and confirm all tests pass.**
- [x] **Step 5: Inspect `git diff` and `git status --short` to ensure only the intended PR #41 fixes and plan are present.**
