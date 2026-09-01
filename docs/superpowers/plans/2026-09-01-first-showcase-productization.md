# 第一批智慧园区展示产品化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将园区客服、停车/空间分析和 AI 治理概览产品化接入现有演示中心，并保持服务端验证、安全边界和现有接口兼容。

**Architecture:** 抽取应用层能力快照与运营指标服务，新增隔离式客服场景预检和只读治理聚合服务；Web 层只做协议映射。Vue 首页继续由服务端场景目录驱动，工作台新增治理页签，运营分析只扩展受控推荐问题，不复制查询引擎。

**Tech Stack:** Java 17, Spring Boot 4, Spring AI Alibaba 2.0, JUnit 5, Vue 3, TypeScript, Vitest, Vite, Element Plus, PostgreSQL analytics views, PowerShell Pester-style tests.

**Spec:** `docs/superpowers/specs/2026-09-01-first-batch-showcase-productization-design.md`

## Global Constraints

- 复用现有 `KnowledgePort`、`CustomerAnswerPort`、`ShowcasePreflightService`、`MetricCatalog`、`OperationsAnalysisService`、`AuditTrail`、`FeedbackService` 和 `OperationsMetrics` 数据源。
- 客服预检只能使用隔离会话/工单存储，不得改变正式会话数、工单数或产生副作用。
- `READY` 只由当前进程内有效验证收据决定；前端不得根据 Bean、配置或静态结果推断 `READY`。
- 新增治理接口只返回计数、模式、状态、时间和固定边界，不返回原始问题、回答正文、知识正文、审批评论、审计资源 ID 或身份信息。
- 不新增停车预测、实时交通调度、房间级空间优化、自动设备控制或生产认证/租户隔离。
- 比率分母为零时返回 `null`；任何失败不得用静态业务数字兜底。
- 保持 `/api/operations/capabilities`、`/api/operations/metrics` 和现有场景 ID 的 JSON 兼容。
- 默认自动化测试不调用外网；在线预检只在显式配置真实凭据时执行，并报告未执行原因。

---

### Task 1: 抽取应用层能力与运营指标服务

**Files:**
- Create: `src/main/java/com/example/smartpark/operations/OperationsCapabilitiesService.java`
- Create: `src/main/java/com/example/smartpark/operations/OperationsCapabilitiesSnapshot.java`
- Move: `src/main/java/com/example/smartpark/web/OperationsMetrics.java` → `src/main/java/com/example/smartpark/operations/OperationsMetrics.java`
- Modify: `src/main/java/com/example/smartpark/web/OperationsCapabilitiesController.java`
- Modify: `src/main/java/com/example/smartpark/web/OperationsController.java`
- Modify: `src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java`
- Test: `src/test/java/com/example/smartpark/operations/OperationsCapabilitiesServiceTest.java`
- Test: `src/test/java/com/example/smartpark/operations/OperationsMetricsTest.java`
- Modify: existing imports in `src/test/java/com/example/smartpark/web/*Test.java`

**Interfaces:**
- Produces `OperationsCapabilitiesSnapshot snapshot()` with fields `knowledgeMode`, `customerAnswerMode`, `vectorStore`, `analyticsEnabled`, `collaborationEnabled`, `voiceEnabled`.
- Produces `OperationsMetrics.Snapshot snapshot()` with the existing nine metric fields and no JSON field changes.
- Controllers continue serving `/api/operations/capabilities` and `/api/operations/metrics`.

- [ ] **Step 1: Write failing tests for capability normalization and gating**

  Add tests proving unknown knowledge/answer modes fall back to `mock`, RAG maps to `simple-vector-store`, collaboration reflects provider availability, and voice is true only when both voice and local-demo flags are true.

- [ ] **Step 2: Run the focused tests and verify failure**

  Run: `.\mvnw.cmd -Dtest=OperationsCapabilitiesServiceTest test`

  Expected: FAIL because the application-layer service and snapshot do not exist.

- [ ] **Step 3: Move metrics and implement the two application services**

  Move the class without changing its constructor behavior or snapshot field order. Make both controllers delegate to application services; do not inject a controller into another service. Update configuration wiring and test imports.

- [ ] **Step 4: Run focused and compatibility tests**

  Run: `.\mvnw.cmd -Dtest=OperationsCapabilitiesServiceTest,OperationsMetricsTest,OperationsCapabilitiesControllerTest,OperationsControllerTest test`

  Expected: PASS with the existing JSON contracts unchanged.

- [ ] **Step 5: Commit**

  ```powershell
  git add src/main/java/com/example/smartpark/operations src/main/java/com/example/smartpark/web src/test/java/com/example/smartpark/operations src/test/java/com/example/smartpark/web
  git commit -m "refactor: move operations status into application layer"
  ```

### Task 2: Add the customer showcase scenario and isolated preflight

**Files:**
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseScenarioId.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseLaunchInput.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseScenario.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseScenarioCatalog.java`
- Create: `src/main/java/com/example/smartpark/showcase/CustomerServicePreflightProbe.java`
- Modify: `src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java`
- Test: `src/test/java/com/example/smartpark/showcase/CustomerServicePreflightProbeTest.java`
- Modify: `src/test/java/com/example/smartpark/showcase/ShowcaseScenarioTest.java`
- Modify: `src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java`
- Modify: `src/test/java/com/example/smartpark/showcase/ShowcasePreflightServiceTest.java`

**Interfaces:**
- `ShowcaseScenarioId.CUSTOMER_SERVICE` is a server-owned ID.
- `ShowcaseLaunchInput.forScenario(CUSTOMER_SERVICE)` returns `new ShowcaseLaunchInput(null, "访客停车怎么收费？")`.
- `CustomerServicePreflightProbe` implements `ShowcasePreflightProbe` and returns `CUSTOMER_SERVICE` from `scenarioId()`.

- [ ] **Step 1: Add failing scenario and probe tests**

  Test the fixed launch input, allowed disabled reason, catalog construction, and probe success/failure. For success use a fake answer port returning a non-empty answer with one citation. Assert the formal session store count and ticket adapter remain zero. Add failures for empty answer, missing citation, human handoff, and unexpected ticket creation.

- [ ] **Step 2: Run focused tests and verify failure**

  Run: `.\mvnw.cmd -Dtest=CustomerServicePreflightProbeTest,ShowcaseScenarioTest,ShowcaseScenarioCatalogTest test`

  Expected: FAIL because the enum case, launch input branch, disabled reason, catalog entry and probe are absent.

- [ ] **Step 3: Implement the customer scenario and probe**

  Add the enum branch everywhere exhaustive switches occur. The catalog entry must use the existing verification registry and return `NOT_READY` without a receipt. The probe must instantiate temporary `InMemoryCustomerSessionStore` and `InMemoryCustomerTicketAdapter`, reuse the configured knowledge/answer ports, execute the fixed question, and inspect the resulting `CustomerServiceResponse`/conversation contract without touching formal stores.

- [ ] **Step 4: Run focused tests and the full showcase package**

  Run: `.\mvnw.cmd -Dtest=CustomerServicePreflightProbeTest,ShowcaseScenarioTest,ShowcaseScenarioCatalogTest,ShowcasePreflightServiceTest,ShowcasePreflightRegistrationTest test`

  Expected: PASS; no existing four-scenario tests should regress except tests intentionally updated to five scenarios.

- [ ] **Step 5: Commit**

  ```powershell
  git add src/main/java/com/example/smartpark/showcase src/test/java/com/example/smartpark/showcase
  git commit -m "feat: add customer service showcase preflight"
  ```

### Task 3: Update five-scenario verification and documentation contracts

**Files:**
- Modify: `scripts/verify-showcase.ps1`
- Modify: `scripts/verify-showcase.tests.ps1`
- Modify: `README.md`
- Modify: `docs/customer-capabilities.md`
- Modify: `docs/architecture.md`
- Test: relevant PowerShell assertions in `scripts/verify-showcase.tests.ps1`

**Interfaces:**
- `Assert-ShowcaseReport` must require exactly `ALERT_WORKFLOW`, `EXPERT_COLLABORATION`, `OPERATIONS_ANALYSIS`, `VOICE_ASSISTANT`, and `CUSTOMER_SERVICE`.
- Public documentation must describe real-time voice as currently available only when enabled and preflighted; remove the stale “当前版本未纳入” claim.

- [ ] **Step 1: Write failing PowerShell contract assertions**

  Add a fixture report containing the five IDs and assertions that four IDs fail, duplicate IDs fail, and a missing/invalid `verifiedAt` fails.

- [ ] **Step 2: Run the contract tests and verify failure**

  Run: `Invoke-Pester .\scripts\verify-showcase.tests.ps1`

  Expected: FAIL while the script still expects four scenarios.

- [ ] **Step 3: Update the script and documentation**

  Add the customer ID to the expected set and keep failure output sanitized. Update README capability tables, customer capability status, architecture statements, and the first-batch showcase reference without changing production-boundary warnings.

- [ ] **Step 4: Run PowerShell tests**

  Run: `Invoke-Pester .\scripts\verify-showcase.tests.ps1`

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```powershell
  git add scripts README.md docs/customer-capabilities.md docs/architecture.md
  git commit -m "docs: align showcase and voice capability contracts"
  ```

### Task 4: Add the safe governance overview backend

**Files:**
- Create: `src/main/java/com/example/smartpark/governance/GovernanceOverview.java`
- Create: `src/main/java/com/example/smartpark/governance/GovernanceOverviewService.java`
- Create: `src/main/java/com/example/smartpark/web/GovernanceOverviewController.java`
- Modify: `src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java`
- Test: `src/test/java/com/example/smartpark/governance/GovernanceOverviewServiceTest.java`
- Test: `src/test/java/com/example/smartpark/web/GovernanceOverviewControllerTest.java`

**Interfaces:**
- `GovernanceOverviewService.snapshot()` returns `GovernanceOverview`.
- `GovernanceOverview` contains `capturedAt`, `ScenarioCounts`, `OperationsCapabilitiesSnapshot`, `BusinessCounts`, `GovernanceCounts`, and `List<String> boundaries`.
- `GET /api/governance/overview` returns the JSON snapshot without a role header; it exposes safe aggregates only.

- [ ] **Step 1: Define the DTO and failing aggregation tests**

  Use deterministic clock and test doubles for scenario catalog, capabilities, metrics, feedback, audit, and knowledge. Assert all counts, `capturedAt`, fixed boundaries, and `null` completion/positive-feedback rates when denominators are zero. Assert serialized output contains no resource IDs or free-text fields.

- [ ] **Step 2: Run focused tests and verify failure**

  Run: `.\mvnw.cmd -Dtest=GovernanceOverviewServiceTest,GovernanceOverviewControllerTest test`

  Expected: FAIL because the DTO, service and controller do not exist.

- [ ] **Step 3: Implement the service and controller**

  Aggregate the moved `OperationsMetrics.Snapshot`, capability service, scenario catalog and existing safe count services under one timestamp. Return fixed Chinese boundary labels covering in-memory state, demo role, analytics read-only access, high-risk human confirmation and non-production control status. Do not add a raw audit-entry list to this endpoint.

- [ ] **Step 4: Run focused backend tests**

  Run: `.\mvnw.cmd -Dtest=GovernanceOverviewServiceTest,GovernanceOverviewControllerTest,OperationsMetricsTest test`

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```powershell
  git add src/main/java/com/example/smartpark/governance src/main/java/com/example/smartpark/web/GovernanceOverviewController.java src/main/java/com/example/smartpark/web/CustomerServiceRuntimeConfiguration.java src/test/java/com/example/smartpark/governance src/test/java/com/example/smartpark/web/GovernanceOverviewControllerTest.java
  git commit -m "feat: expose safe governance overview"
  ```

### Task 5: Wire the customer scenario into the Vue guided launch

**Files:**
- Modify: `ui/src/types/workbench.ts`
- Modify: `ui/src/App.vue`
- Modify: `ui/src/components/showcase/ShowcaseHome.vue`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/CustomerServiceConsole.vue`
- Modify: `ui/src/services/workflowApi.ts`
- Test: `ui/src/components/showcase/ShowcaseHome.spec.ts`
- Test: `ui/src/components/CustomerServiceConsole.spec.ts`
- Test: `ui/src/App.spec.ts`

**Interfaces:**
- `ShowcaseScenarioId` includes `CUSTOMER_SERVICE`.
- `GuidedWorkbenchView` includes `customer`.
- `App.vue` maps `CUSTOMER_SERVICE` to `customer`.
- `CustomerServiceConsole` accepts `active?: boolean` and `launchRequest?: ScenarioLaunchRequest | null`.

- [ ] **Step 1: Add failing Vue tests**

  Extend the showcase fixture with a ready customer scenario and assert it renders/selects. Assert a guided customer launch sends exactly one `askCustomerService("访客停车怎么收费？", idempotencyKey)` call, reports success, and does not send a report/approval request. Add a remount/hidden-return case proving the accepted request is not duplicated.

- [ ] **Step 2: Run focused UI tests and verify failure**

  Run: `Set-Location ui; npm run test:unit -- ShowcaseHome.spec.ts CustomerServiceConsole.spec.ts App.spec.ts`

  Expected: FAIL because the new ID, route mapping and guided launch props do not exist.

- [ ] **Step 3: Implement the customer card and guided launch**

  Add the customer icon/title mapping and include it in priority ordering. Pass `active` and `launchRequest` to `CustomerServiceConsole`; use `useGuidedLaunch` with scenario ID `CUSTOMER_SERVICE`, a one-time idempotency key, and the server-owned question. Keep manual repair submission unchanged.

- [ ] **Step 4: Run focused UI tests**

  Run: `Set-Location ui; npm run test:unit -- ShowcaseHome.spec.ts CustomerServiceConsole.spec.ts App.spec.ts`

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```powershell
  git add ui/src/types/workbench.ts ui/src/App.vue ui/src/components/showcase/ShowcaseHome.vue ui/src/components/OperationsWorkbench.vue ui/src/components/CustomerServiceConsole.vue ui/src/services/workflowApi.ts ui/src/components/showcase/ShowcaseHome.spec.ts ui/src/components/CustomerServiceConsole.spec.ts ui/src/App.spec.ts
  git commit -m "feat: add guided customer showcase entry"
  ```

### Task 6: Group parking and space analysis recommendations

**Files:**
- Modify: `ui/src/components/analytics/OperationsAnalysisPage.vue`
- Modify: `ui/src/components/analytics/analytics.css`
- Modify: `ui/src/components/analytics/OperationsAnalysisPage.spec.ts`

**Interfaces:**
- Replace the string-only recommendation list with `{ group: string; label: string; question: string }[]`.
- Keep the existing `question` ref and `selectRecommendedQuestion(question: string)` behavior.

- [ ] **Step 1: Add failing tests for grouped presets**

  Assert the page renders the five group labels and contains these exact questions: `过去5天各停车区域停车利用率`, `过去5天各停车区域进场量`, `过去5天各楼宇平均占用人数`, `过去5天各楼宇能耗与占用人数关系`, and `过去5天各楼宇能耗空间分布`. Assert clicking a preset fills the existing analysis input rather than bypassing submission.

- [ ] **Step 2: Run the focused test and verify failure**

  Run: `Set-Location ui; npm run test:unit -- OperationsAnalysisPage.spec.ts`

  Expected: FAIL because the current list is ungrouped and missing the parking/occupancy wording.

- [ ] **Step 3: Implement grouped presentation only**

  Add group headings and keep all existing energy, alert and device questions. Do not add a new API call or chart type; the selected question continues through `useOperationsAnalysis`.

- [ ] **Step 4: Run the focused test**

  Run: `Set-Location ui; npm run test:unit -- OperationsAnalysisPage.spec.ts`

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```powershell
  git add ui/src/components/analytics/OperationsAnalysisPage.vue ui/src/components/analytics/analytics.css ui/src/components/analytics/OperationsAnalysisPage.spec.ts
  git commit -m "feat: group operations analysis showcase prompts"
  ```

### Task 7: Build the governance center and homepage status summary

**Files:**
- Create: `ui/src/types/governance.ts`
- Create: `ui/src/services/governanceApi.ts`
- Create: `ui/src/components/governance/GovernanceOverviewPage.vue`
- Create: `ui/src/components/governance/governance.css`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/components/showcase/ShowcaseHome.vue`
- Modify: `ui/src/styles/workbench-primitives.css` only if a shared layout token is required
- Test: `ui/src/components/governance/GovernanceOverviewPage.spec.ts`
- Modify: `ui/src/components/showcase/ShowcaseHome.spec.ts`
- Modify: `ui/src/components/OperationsWorkbench.spec.ts`

**Interfaces:**
- `getGovernanceOverview(): Promise<GovernanceOverview>` calls `GET /api/governance/overview`.
- `getAuditEntries(role)` remains the only source of admin audit details.
- `GovernanceOverviewPage` accepts `role: DemoRole` and `active?: boolean`.

- [ ] **Step 1: Add failing page and API tests**

  Mock the overview endpoint and assert cards render scenario counts, capability modes, business counters, governance counters and fixed boundaries. Assert zero-denominator rates render `暂无样本`. Assert non-admin does not call `getAuditEntries`; admin does. Assert overview failure displays retry and leaves no static metric values. Add a workbench navigation test and homepage one-line summary test.

- [ ] **Step 2: Run focused tests and verify failure**

  Run: `Set-Location ui; npm run test:unit -- GovernanceOverviewPage.spec.ts ShowcaseHome.spec.ts OperationsWorkbench.spec.ts`

  Expected: FAIL because the governance types, API, page, navigation and summary do not exist.

- [ ] **Step 3: Implement API types, page and wiring**

  Add a thin fetch service, render the safe snapshot, and conditionally fetch admin audit details. Add the `governance` workbench view and keep the execution trace rail idle. Home consumes only the scenario counts and fixed boundary summary; overview failure is a non-blocking status label. Use existing Element Plus primitives and responsive CSS patterns.

- [ ] **Step 4: Run focused tests and UI typecheck**

  Run: `Set-Location ui; npm run test:unit -- GovernanceOverviewPage.spec.ts ShowcaseHome.spec.ts OperationsWorkbench.spec.ts; npm run typecheck`

  Expected: PASS.

- [ ] **Step 5: Commit**

  ```powershell
  git add ui/src/types/governance.ts ui/src/services/governanceApi.ts ui/src/components/governance ui/src/components/OperationsWorkbench.vue ui/src/components/showcase/ShowcaseHome.vue ui/src/components/showcase/ShowcaseHome.spec.ts ui/src/components/OperationsWorkbench.spec.ts
  git commit -m "feat: add governance center to showcase workbench"
  ```

### Task 8: Full regression, browser acceptance and handoff

**Files:**
- Modify only files needed to correct test or accessibility findings from Tasks 1–7.
- Test: existing backend and frontend test suites plus `scripts/verify-showcase.tests.ps1`.

**Interfaces:**
- No new public interfaces. This task validates the interfaces produced by Tasks 1–7.

- [ ] **Step 1: Run complete backend and frontend verification**

  Run:

  ```powershell
  .\mvnw.cmd test
  Set-Location ui
  npm run test:unit
  npm run build
  Set-Location ..
  Invoke-Pester .\scripts\verify-showcase.tests.ps1
  ```

  Expected: all commands exit with code 0. If credentials are absent, do not run or claim online model verification.

- [ ] **Step 2: Start the local demo stack and inspect the real UI**

  Start the documented local Compose stack, open the frontend, and verify: customer card selection, one-time parking question, manual repair handoff, grouped parking/space prompts, governance tab for a non-admin, admin audit details, overview retry, keyboard focus, and narrow viewport layout.

- [ ] **Step 3: Run five-scenario preflight when explicitly configured**

  With the required local demo credentials, run `.\scripts\verify-showcase.ps1` and confirm the report contains exactly five unique `READY` scenarios. Without credentials, record the run as not executed.

- [ ] **Step 4: Inspect the final diff for boundary regressions**

  Run: `git diff --check; git status --short; git log -8 --oneline`

  Confirm no secrets, raw knowledge text, sensitive security data, static success metrics, or unrelated refactors entered the diff.

- [ ] **Step 5: Commit any verification-only corrections**

  If Task 8 found a defect, stage only the exact corrected paths using the file lists from Tasks 1–7 and commit them with `git commit -m "test: verify first showcase productization batch"`. If all checks pass without corrections, leave the worktree unchanged after recording the verification output.
