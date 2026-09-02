# 客服执行轨迹接入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为每次客服请求提供独立、可重放且安全脱敏的统一执行轨迹，并在客服工作台复用现有轨迹栏展示。

**Architecture:** 新增 `CustomerServiceExecutionService` 作为客服工作流与统一事件发布器之间的应用层包装器；`CustomerServiceWorkflow` 继续是会话、幂等和工单状态的唯一来源。客服同步 HTTP 响应通过 `X-Execution-Run-Id` 返回观测 run，前端在收到响应后订阅现有 SSE 历史，不新增异步客服协议或第二套事件存储。

**Tech Stack:** Java 17, Spring Boot 4, Spring MVC, JUnit 5, MockMvc, Vue 3, TypeScript, Vitest, existing `ExecutionEventPublisher` and `useExecutionTrace`.

**Spec:** `docs/superpowers/specs/2026-09-02-customer-execution-trace-design.md`

## Global Constraints

- 每次新会话或回复请求使用独立 `runId`，不向已终态 run 追加消息。
- 新增 `CUSTOMER_SERVICE` 执行场景，保持已有四类场景行为和字段兼容。
- 事件只使用固定阶段、枚举、知识引用数量和安全摘要，不写入用户问题、回答原文、Prompt、供应商响应、个人信息或知识正文。
- 轨迹发布失败不得阻断客服业务结果；未处理工作流异常仍发布稳定 `FAILED` 后按现有错误处理抛出。
- 客服 JSON 响应字段和状态码保持兼容，仅成功响应增加可选 `X-Execution-Run-Id` 响应头。
- 统一事件查询继续使用 `/api/executions/{runId}` 和 `/api/executions/{runId}/events`。
- 事件与客服会话均为进程内存储，沿用事件发布器 30 分钟终态保留。

---

### Task 1: Add the customer-service execution application boundary

**Files:**
- Create: `src/main/java/com/example/smartpark/customer/CustomerServiceExecutionService.java`
- Modify: `src/main/java/com/example/smartpark/execution/model/ExecutionScenario.java`
- Create: `src/test/java/com/example/smartpark/customer/CustomerServiceExecutionServiceTest.java`

**Interfaces:**
- `CustomerServiceExecutionService(CustomerServiceWorkflow workflow, ExecutionEventPublisher publisher)`; a null publisher is allowed only for standalone compatibility construction and disables observation.
- Nested immutable result: `CustomerServiceExecutionResult(UUID runId, CustomerServiceResult result)`.
- Methods: `handle(String question, String idempotencyKey)` and `reply(String sessionId, String question, String idempotencyKey)`.

- [ ] **Step 1: Write failing tests**

Use a recording publisher and a real workflow backed by `MockParkFixture`. Assert that one request creates a UUID and publishes exactly `RUN_STARTED`, `NODE_STARTED`, `NODE_COMPLETED`, `NODE_STARTED`, `NODE_COMPLETED`, `NODE_STARTED`, `NODE_COMPLETED`, `COMPLETED`; every event uses `ExecutionScenario.CUSTOMER_SERVICE`. Add repair/knowledge-missing coverage, different IDs for two requests, and an injected workflow exception that publishes only stable `客服请求执行失败` without exception text.

- [ ] **Step 2: Run the focused test and verify it fails**

Run `./mvnw.cmd -q "-Dtest=CustomerServiceExecutionServiceTest" test`; expect compilation failure because the wrapper and enum member do not exist.

- [ ] **Step 3: Implement the wrapper**

Publish sequence `RUN_STARTED/INPUT_CAPTURE/RUNNING/客服请求已接收`, understanding start/completion, knowledge-tool start/completion, response start/completion, and `COMPLETED/COMPLETION/SUCCEEDED/客服请求处理完成`. Use fixed actor `customer-service`, sequence `0`, no display payload, and only `knowledgeCitations.size()` as a dynamic value. On workflow exception publish `FAILED/FAILURE/FAILED/客服请求执行失败`, then rethrow. Catch publisher failures, log a stable technical message, and continue.

- [ ] **Step 4: Run compatibility tests**

Run `./mvnw.cmd -q "-Dtest=CustomerServiceExecutionServiceTest,ExecutionEventPublisherTest,CustomerServiceWorkflowTest" test`; expect all pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/customer/CustomerServiceExecutionService.java src/main/java/com/example/smartpark/execution/model/ExecutionScenario.java src/test/java/com/example/smartpark/customer/CustomerServiceExecutionServiceTest.java
git commit -m "feat: publish customer service execution trace"
```

### Task 2: Wire the wrapper into the customer REST boundary

**Files:**
- Modify: `src/main/java/com/example/smartpark/web/CustomerServiceController.java`
- Modify: `src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java`
- Modify: `src/test/java/com/example/smartpark/web/CustomerServiceControllerTest.java`

**Interfaces:**
- Spring creates the wrapper with the existing workflow and `ExecutionEventPublisher`.
- Existing one-argument and two-argument Controller constructors remain available for standalone tests; Spring uses an `@Autowired` constructor with the publisher.
- The two POST methods return `ResponseEntity<WebDtos.CustomerServiceResponse>` and add `X-Execution-Run-Id`; JSON, status codes, audit actions and idempotency remain unchanged.

- [ ] **Step 1: Add failing MVC assertions**

Assert both POST endpoints return `200`, a non-null `X-Execution-Run-Id`, the existing `sessionId`, and no `executionRunId` JSON field. Also cover validation failures without the header, exact idempotent body equality, and unchanged conversation reads.

- [ ] **Step 2: Run `./mvnw.cmd -q "-Dtest=CustomerServiceControllerTest" test` and verify the new header assertions fail.**

- [ ] **Step 3: Add the wrapper bean and map successful results through `ResponseEntity.ok().header("X-Execution-Run-Id", execution.runId().toString()).body(WebDtos.from(execution.result()))`.** Keep standalone constructors observation-disabled.

- [ ] **Step 4: Run `./mvnw.cmd -q "-Dtest=CustomerServiceControllerTest,CustomerServiceWorkflowTest" test`.**

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/example/smartpark/web/CustomerServiceController.java src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java src/test/java/com/example/smartpark/web/CustomerServiceControllerTest.java
git commit -m "feat: expose customer execution run header"
```

### Task 3: Preserve the run header in the frontend API client

**Files:**
- Modify: `ui/src/types/workflow.ts`
- Modify: `ui/src/services/workflowApi.ts`
- Modify: `ui/src/services/workflowApi.spec.ts`

**Interfaces:**
- `CustomerServiceResponse` gains optional client-only `executionRunId?: string`; it is never sent in requests or expected in JSON.
- `askCustomerService` and `replyCustomerSession` decode `X-Execution-Run-Id`; unrelated API functions keep the current helper.

- [ ] **Step 1: Add failing fetch tests**

Return a JSON response with `X-Execution-Run-Id: run-customer-1` and assert both customer methods expose `executionRunId`; assert a missing header yields `undefined`.

- [ ] **Step 2: Run `npm run test:unit -- src/services/workflowApi.spec.ts` from `ui`; expect the new assertions to fail.**

- [ ] **Step 3: Implement a customer-only response helper**

Reuse the existing error handling, read the response header after JSON decoding, and attach the optional property only for the two customer methods. Do not alter request bodies or other endpoints.

- [ ] **Step 4: Run the focused test and `npm run typecheck`.**

- [ ] **Step 5: Commit**

```powershell
git add ui/src/types/workflow.ts ui/src/services/workflowApi.ts ui/src/services/workflowApi.spec.ts
git commit -m "feat: retain customer execution run id"
```

### Task 4: Subscribe the customer console to the shared trace

**Files:**
- Modify: `ui/src/types/execution.ts`
- Modify: `ui/src/components/CustomerServiceConsole.vue`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/CustomerServiceConsole.spec.ts`

**Interfaces:**
- TypeScript `ExecutionScenario` gains `CUSTOMER_SERVICE`.
- `CustomerServiceConsole` accepts optional `trace?: ExecutionTraceLike` and keeps all existing props/events.

- [ ] **Step 1: Add failing console tests**

Mount with a fake trace and mocked customer responses carrying `executionRunId`; after new-session and reply requests assert `trace.subscribe` receives the corresponding ID. Cover missing headers, a throwing subscriber, and stale responses after `requestGeneration` changes.

- [ ] **Step 2: Run `npm run test:unit -- src/components/CustomerServiceConsole.spec.ts` from `ui`; expect no subscription.**

- [ ] **Step 3: Implement lifecycle-safe subscription**

After the existing generation check, store the latest run ID and call `trace.subscribe` inside `try/catch`. Watch `active` to re-subscribe the latest ID when returning; clear it in `resetConversation`. Pass `:trace="trace"` from `OperationsWorkbench`. Never fabricate customer events.

- [ ] **Step 4: Run focused UI tests and typecheck**

Run `npm run test:unit -- src/components/CustomerServiceConsole.spec.ts src/components/OperationsWorkbench.spec.ts src/components/execution/ExecutionTraceRail.spec.ts` and `npm run typecheck`.

- [ ] **Step 5: Commit**

```powershell
git add ui/src/types/execution.ts ui/src/components/CustomerServiceConsole.vue ui/src/components/OperationsWorkbench.vue ui/src/components/CustomerServiceConsole.spec.ts
git commit -m "feat: show customer service execution trace"
```

### Task 5: Document the additive trace contract

**Files:** `README.md`, `docs/customer-capabilities.md`, `docs/architecture.md`

- [ ] **Step 1:** Document `CUSTOMER_SERVICE`, one run per request, response-header discovery, synchronous history replay, safe summaries, and no business-state changes.
- [ ] **Step 2:** Run `rg -n "CUSTOMER_SERVICE|X-Execution-Run-Id|客服.*轨迹" README.md docs/customer-capabilities.md docs/architecture.md` and `git diff --check`.
- [ ] **Step 3: Commit**

```powershell
git add README.md docs/customer-capabilities.md docs/architecture.md
git commit -m "docs: describe customer execution trace"
```

### Task 6: Full verification

- [ ] **Step 1:** Run `./mvnw.cmd -q test`.
- [ ] **Step 2:** From `ui`, run `npm run typecheck`, `npm run test:unit`, and `npm run build`; the known large-chunk warning is non-fatal.
- [ ] **Step 3:** Run `git diff --check`, `git status --short`, `git diff --stat origin/main...HEAD`, and scan the new public surfaces for `SQL|Prompt|password|credential|vendor|secret`.
- [ ] **Step 4:** Mark the plan complete only when the worktree is clean and all commands pass; do not create a no-op verification commit.
