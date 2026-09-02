# PR#43 Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolve all five inline review comments on PR#43 while preserving the existing analysis, customer-service, and report contracts.

**Architecture:** Keep lifecycle ownership in the existing services. Terminal analysis records release the shared active-run guard before invoking awaiter callbacks; the customer trace derives retrieval-node status from the workflow result reason; the report composable keeps its last accepted snapshot until a replacement is accepted; the report view owns the accepted run ID and renders server-owned time-resolution metadata.

**Tech Stack:** Java 17, Spring Boot, JUnit 5/AssertJ/Mockito, Vue 3, TypeScript, Vitest, Vue Test Utils.

**Spec:** PR#43 inline review comments from GitHub review `5087762165`.

> **Status:** All five fixes are present in `origin/main` via PR#43 commit `bff4908` and have been verified on the reconciliation branch.

## Global Constraints

- Do not expose exception text, prompts, SQL, or raw provider content in execution summaries.
- A `NEEDS_CLARIFICATION` analysis remains active until its existing abort/resume lifecycle handles it.
- Report values and resolved time windows come from backend DTOs; the UI must not invent metrics or ranges.
- Preserve existing public method signatures and compatibility constructors unless a test requires a contract change.

---

### Task 1: Release analysis runs before invoking terminal awaiters

**Files:**
- Modify: `src/main/java/com/example/smartpark/analytics/OperationsAnalysisService.java:180-205,535-639`
- Test: `src/test/java/com/example/smartpark/analytics/OperationsAnalysisServiceTest.java`

**Interfaces:**
- Consumes: `OperationsAnalysisService.startAndAwait(String)` and `OperationsAnalysisService.start(String)`.
- Produces: terminal waiter callbacks observe that the previous run is no longer active; clarification waiters still complete while the run remains active.

- [x] **Step 1: Write the failing test**

Add a deterministic asynchronous test that blocks the first graph execution, registers a `startAndAwait` callback, releases the graph, and asserts the callback can start a second run.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `.\mvnw.cmd -Dtest=OperationsAnalysisServiceTest#terminalAwaiterCanStartNextRunAfterPreviousRunReleasesActiveSlot test`

Expected: FAIL because the callback runs while `activeRunId` still points to the first completed run.

- [x] **Step 3: Implement the minimal lifecycle ordering fix**

For terminal outcomes, failures, and aborts, persist the record, release the active run, publish the terminal trace, then complete the waiter. Keep the clarification branch completing its waiter without releasing the active run.

- [x] **Step 4: Run the focused test and verify it passes**

Run: `.\mvnw.cmd -Dtest=OperationsAnalysisServiceTest#terminalAwaiterCanStartNextRunAfterPreviousRunReleasesActiveSlot test`

Expected: PASS.

### Task 2: Mark customer retrieval outages as failed trace nodes

**Files:**
- Modify: `src/main/java/com/example/smartpark/customer/CustomerServiceExecutionService.java:47-58`
- Test: `src/test/java/com/example/smartpark/customer/CustomerServiceExecutionServiceTest.java`

**Interfaces:**
- Consumes: `CustomerServiceResult.reason()`.
- Produces: a safe retrieval-node event with `FAILED` status and a generic outage summary for `RETRIEVAL_UNAVAILABLE`; normal retrieval remains `SUCCEEDED`.

- [x] **Step 1: Write the failing test**

Add a test using a knowledge port that throws and assert the retrieval node event is `NODE_COMPLETED`, has `ExecutionStatus.FAILED`, and contains no exception text.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `.\mvnw.cmd -Dtest=CustomerServiceExecutionServiceTest#marksRetrievalOutageAsFailedWithoutLeakingProviderDetails test`

Expected: FAIL because the current wrapper always emits `SUCCEEDED` and “知识检索完成”.

- [x] **Step 3: Implement the minimal reason-based projection**

Branch only on `CustomerAnswer.Reason.RETRIEVAL_UNAVAILABLE`; emit `FAILED` and a fixed safe handoff summary for that reason, retaining the existing hit-count summary otherwise.

- [x] **Step 4: Run the focused test and verify it passes**

Run: `.\mvnw.cmd -Dtest=CustomerServiceExecutionServiceTest#marksRetrievalOutageAsFailedWithoutLeakingProviderDetails test`

Expected: PASS.

### Task 3: Preserve the previous report snapshot until replacement acceptance

**Files:**
- Modify: `ui/src/composables/useOperationsDailyReport.ts:30-37`
- Test: `ui/src/components/operations/OperationsDailyReport.spec.ts`

**Interfaces:**
- Consumes: existing `report` ref and `startOperationsDailyReport` acceptance promise.
- Produces: a failed replacement POST leaves the last completed `report` rendered while exposing the new error.

- [x] **Step 1: Write the failing test**

Extend the component test with a completed first run, then a rejected second POST; assert the prior section and summary remain visible with the replacement error.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: FAIL because `start()` clears `report.value` before the second POST is accepted.

- [x] **Step 3: Implement the minimal state change**

Remove only the pre-acceptance `report.value = null` assignment. Keep `reset()` as the explicit full-clear operation and continue replacing the snapshot after the new run is accepted/polled.

- [x] **Step 4: Run the focused test and verify it passes**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: PASS.

### Task 4: Restore the accepted report trace when returning to the operations view

**Files:**
- Modify: `ui/src/components/operations/OperationsDailyReport.vue:1-17`
- Test: `ui/src/components/operations/OperationsDailyReport.spec.ts`

**Interfaces:**
- Consumes: `props.active`, shared `ExecutionTraceLike`, and the accepted report `runId`.
- Produces: a report component that resubscribes its own accepted run whenever it becomes active again.

- [x] **Step 1: Write the failing test**

Add a test that accepts a report, changes `active` to false, simulates another trace subscription, changes `active` to true, and asserts the report run ID is subscribed again.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: FAIL because the component currently has no accepted-run ID or active-state watcher.

- [x] **Step 3: Implement the minimal trace restoration**

Track the accepted `runId` in a ref, set it after the start POST succeeds, and watch `active`; on a false-to-true transition, call `trace.subscribe(acceptedRunId)` if both are present.

- [x] **Step 4: Run the focused test and verify it passes**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: PASS.

### Task 5: Render each section’s server-resolved time window

**Files:**
- Modify: `ui/src/components/operations/OperationsDailyReport.vue:1-69`
- Modify: `ui/src/types/operationsReport.ts:1-24`
- Test: `ui/src/components/operations/OperationsDailyReport.spec.ts`

**Interfaces:**
- Consumes: backend `timeResolution` fields `status`, `fromInclusive`, `toExclusive`, `source`, `explanation`, and `empty`.
- Produces: completed-section UI showing the resolved range when present and the backend explanation, including the empty-period case.

- [x] **Step 1: Write the failing test**

Include `timeResolution` in the mocked completed section and assert the rendered report contains its explanation and resolved boundary text; include an empty period assertion that does not fabricate a range.

- [x] **Step 2: Run the focused test and verify it fails**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: FAIL because the section template currently ignores `timeResolution`.

- [x] **Step 3: Implement the minimal DTO and rendering projection**

Replace the loose metadata type with a typed optional interface and add a small safe formatter/label mapping in the component. Render backend explanation always, render the formatted range only when both boundaries are non-empty, and visibly label empty/default/explicit states.

- [x] **Step 4: Run the focused test and verify it passes**

Run: `npm --prefix ui run test:unit -- src/components/operations/OperationsDailyReport.spec.ts`

Expected: PASS.

### Final verification

- [x] Run backend focused tests: `.\mvnw.cmd -Dtest=OperationsAnalysisServiceTest,CustomerServiceExecutionServiceTest,OperationsDailyReportServiceTest test`
- [x] Run frontend unit tests: `npm --prefix ui run test:unit`
- [x] Run frontend typecheck/build: `npm --prefix ui run build`
- [x] Inspect `git diff --check` and the final diff for scope and secret leakage.
