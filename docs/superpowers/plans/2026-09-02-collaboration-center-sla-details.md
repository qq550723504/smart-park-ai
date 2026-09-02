# Collaboration Center SLA Details Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the read-only AI collaboration center with deterministic demo-SLA status and a safe detail drawer while preserving existing domain boundaries and scene jump behavior.

**Architecture:** Keep alert workflows and customer tickets as separate domain models. Extend the application-layer `CollaborationWorkItem` projection with safe timestamps and SLA metadata calculated by an injected `Clock`; the existing list endpoint returns the extended projection, so the drawer does not need a second endpoint or privileged domain lookup.

**Tech Stack:** Spring Boot 4, Java 17 records, JUnit 5/AssertJ, Vue 3, TypeScript, Vitest, Vite.

**Spec:** Approved in chat on 2026-09-02; no separate architectural spec is required because this is a bounded extension of the existing collaboration-center read flow.

## Global Constraints

- Keep `WorkflowExecutionStore` optional so the collaboration center remains available when DashScope alert workflow beans are disabled.
- Keep `CustomerTicketReader` and `WorkflowExecutionStore` separate; do not create a shared persistence/domain entity.
- The collaboration center remains read-only: no approval, dispatch, ticket mutation, device control, or new authentication mechanism.
- Safe projections must not expose diagnosis正文、审批意见、知识正文、原始工具结果或敏感身份数据。
- Demo SLA policy is presentation metadata, not a production scheduling contract; label it clearly in the UI.
- Preserve `X-Demo-Role` authorization: only `CUSTOMER_AGENT` and `ADMIN` can read the queue.
- Use fixed clocks in tests; do not make tests depend on wall-clock time or external services.

---

### Task 1: Add safe SLA metadata to the application projection

**Files:**
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationWorkItem.java`
- Create: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationSlaPolicy.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java`
- Test: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java`
- Create: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationSlaPolicyTest.java`

**Interfaces:**
- `CollaborationSlaPolicy.evaluate(Source source, Priority priority, Status status, Instant openedAt, Instant now): SlaEvaluation`
- `CollaborationWorkItem` adds `Instant openedAt`, `Instant slaDueAt`, and `SlaState slaState`, where `SlaState` is `ON_TRACK`, `DUE_SOON`, `OVERDUE`, `COMPLETED`, or `NOT_APPLICABLE`.
- `CollaborationCenterService` keeps its existing public constructors and adds a constructor accepting `Clock` for deterministic tests; the production configuration supplies `Clock.systemUTC()`.

- [ ] **Step 1: Write failing policy and projection tests**

Add tests that fix `now` at `2026-09-02T10:00:00Z` and assert:

```java
assertThat(policy.evaluate(Source.ALERT_WORKFLOW, Priority.HIGH,
        Status.WAITING_APPROVAL, Instant.parse("2026-09-02T09:40:00Z"), now).state())
        .isEqualTo(SlaState.DUE_SOON);
assertThat(policy.evaluate(Source.CUSTOMER_TICKET, Priority.NORMAL,
        Status.WAITING_AGENT, Instant.parse("2026-09-02T05:00:00Z"), now).state())
        .isEqualTo(SlaState.OVERDUE);
assertThat(policy.evaluate(Source.ALERT_WORKFLOW, Priority.HIGH,
        Status.COMPLETED, Instant.parse("2026-09-02T08:00:00Z"), now).state())
        .isEqualTo(SlaState.COMPLETED);
```

Also assert the service returns `openedAt`, `slaDueAt`, and the expected state for both an alert snapshot and a customer ticket.

- [ ] **Step 2: Run focused tests to verify the new contract fails**

Run: `./mvnw -q -Dtest=CollaborationSlaPolicyTest,CollaborationCenterServiceTest test` (Windows: `.\mvnw.cmd -q "-Dtest=CollaborationSlaPolicyTest,CollaborationCenterServiceTest" test`)

Expected: compilation/test failure because the new SLA types and projection fields do not exist.

- [ ] **Step 3: Implement the deterministic policy**

Use these exact demo windows: high-priority alerts 30 minutes, normal alerts 2 hours, customer tickets 4 hours. Treat terminal statuses (`COMPLETED`, `REJECTED`, `RESOLVED`, `CLOSED`, `CANCELLED`) as `COMPLETED`; use `NOT_APPLICABLE` only when no valid opened timestamp exists. Mark `DUE_SOON` when remaining time is at most 20% of the source window and still positive.

Derive `openedAt` from the alert occurred time or the ticket creation time, falling back to the current safe projection timestamp only when the source has no earlier timestamp. Compute the SLA using the injected clock and keep the existing safe summary/location extraction unchanged.

- [ ] **Step 4: Run focused tests to verify the implementation passes**

Run the same focused Maven command. Expected: all policy and projection tests pass.

- [ ] **Step 5: Commit the backend projection change**

```bash
git add src/main/java/com/example/smartpark/collaborationcenter src/test/java/com/example/smartpark/collaborationcenter
git commit -m "feat: add collaboration center demo SLA metadata"
```

### Task 2: Extend the existing read response without adding a mutation path

**Files:**
- Modify: `src/main/java/com/example/smartpark/web/CollaborationCenterDtos.java`
- Modify: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterConfiguration.java`
- Modify: `src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java`
- Modify: `README.md`
- Modify: `docs/architecture.md`

**Interfaces:**
- Existing `GET /api/collaboration/work-items` remains the only endpoint.
- `WorkItemResponse` adds `openedAt`, `slaDueAt`, and `slaState`; field names match the TypeScript interface exactly.
- Authorization, `source`, `status`, and `limit` validation remain unchanged.

- [ ] **Step 1: Add controller assertions before implementation**

Extend the controller test fixture and assertions to require the new fields and assert that the JSON still does not contain `diagnosis`, `approval`, `knowledgeBody`, or `toolResult`.

- [ ] **Step 2: Run the focused controller test and confirm it fails**

Run: `.\mvnw.cmd -q -Dtest=CollaborationCenterControllerTest test`

Expected: compilation or assertion failure for the missing response fields.

- [ ] **Step 3: Map the projection fields and inject the production clock**

Update `CollaborationCenterDtos.WorkItemResponse.from(...)` and the configuration bean to construct `CollaborationCenterService` with `Clock.systemUTC()`. Do not add a detail endpoint or expose domain objects.

- [ ] **Step 4: Run the focused controller test**

Run the same command. Expected: admin safe response, viewer 403, invalid source/limit 400, and no sensitive field names.

- [ ] **Step 5: Commit the API contract change**

```bash
git add src/main/java/com/example/smartpark/web src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterConfiguration.java src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java README.md docs/architecture.md
git commit -m "feat: expose collaboration center SLA metadata"
```

### Task 3: Add the safe detail drawer and SLA presentation

**Files:**
- Modify: `ui/src/types/collaborationCenter.ts`
- Modify: `ui/src/components/collaboration/CollaborationCenter.vue`
- Modify: `ui/src/components/collaboration/collaboration-center.css`
- Test: `ui/src/components/collaboration/CollaborationCenter.spec.ts`

**Interfaces:**
- `CollaborationWorkItem` adds `openedAt`, `slaDueAt`, and `slaState` unions matching the backend JSON.
- The existing `open-view` event remains `['workflow' | 'customer', workflowId?, ticketId?]`.
- The component adds an internal selected item and renders a closeable drawer; it does not call a new privileged API.

- [ ] **Step 1: Write failing component tests**

Add a fixture with SLA fields and test that clicking a queue item opens a drawer containing source, safe summary, location, status, “演示 SLA”, and the localized SLA state. Assert that the drawer contains no forbidden field labels and that clicking close removes it. Add a test that terminal items render “已完成” instead of “已超时”.

- [ ] **Step 2: Run the focused Vitest file and confirm failure**

Run: `npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`

Expected: failure because no drawer or SLA metadata is rendered yet.

- [ ] **Step 3: Implement the drawer and resilient presentation**

Keep the queue list and existing filters unchanged. Add `selectedItem`, `openDetails`, `closeDetails`, a backdrop/button with `aria-label="关闭详情"`, and an accessible dialog (`role="dialog"`, `aria-modal="true"`). Render safe fields only, format missing locations as `未提供`, map all SLA states to Chinese labels, and use a red accent only for `OVERDUE`. Preserve stale-response protection and role/error states.

- [ ] **Step 4: Run focused and workbench component tests**

Run:

```bash
npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts src/components/OperationsWorkbench.spec.ts
```

Expected: all collaboration-center and workbench tests pass, including original-scene jumps.

- [ ] **Step 5: Commit the UI change**

```bash
git add ui/src/types/collaborationCenter.ts ui/src/components/collaboration
git commit -m "feat: add collaboration center SLA details drawer"
```

### Task 4: Full verification and documentation closeout

**Files:**
- Modify: `README.md`
- Modify: `docs/customer-capabilities.md`
- Modify: `docs/architecture.md`

- [ ] **Step 1: Document demo SLA semantics and safe detail boundary**

Add the SLA policy table, explain that it is deterministic demo metadata rather than a production commitment, and document that the drawer is read-only and jumps to the existing scene for actions.

- [ ] **Step 2: Run the complete verification suite**

Run:

```powershell
.\mvnw.cmd -q test
Set-Location ui
npm run typecheck
npm run test:unit
npm run build
```

Expected: all commands exit 0. Record any existing Vite chunk-size warning separately; it is not a test failure.

- [ ] **Step 3: Run local API and UI smoke checks**

With the local Compose stack running, verify:

```powershell
Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/collaboration/work-items?limit=5' -Headers @{'X-Demo-Role'='ADMIN'}
Invoke-WebRequest -Uri 'http://127.0.0.1:5173'
```

Confirm admin access is 200, viewer access is 403, the frontend is 200, and the drawer opens from a returned work item when fixture data is present. Do not run online showcase preflight unless online capabilities are explicitly enabled.

- [ ] **Step 4: Commit documentation and report status**

```bash
git add README.md docs/architecture.md docs/customer-capabilities.md
git commit -m "docs: describe collaboration center SLA details"
git status --short
```

Expected: clean working tree and a concise report of tests, local smoke checks, and any environment-only limitations.
