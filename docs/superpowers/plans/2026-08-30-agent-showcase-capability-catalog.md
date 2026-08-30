# Agent Showcase Capability Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Introduce a conservative, server-owned catalog of customer-facing Agent showcase scenarios so the UI can offer a scenario only after the current deployment has completed a recent real online verification.

**Architecture:** Add a small showcase application capability that projects four fixed scenario definitions through a read-only GET /api/showcase/scenarios endpoint. ScenarioVerificationRegistry owns ephemeral, per-process verification receipts; the later online-preflight slice records actual success or failure through this interface. The catalog has one safe state machine: feature disabled → DISABLED; enabled but not recently verified → NOT_READY; enabled with a current successful receipt → READY. The existing GET /api/operations/capabilities response remains unchanged for its current UI consumers.

**Tech Stack:** Java 17 records/enums, Spring Boot MVC and configuration properties, Vue 3, TypeScript, Vitest, Vue Test Utils.

**Spec:** docs/superpowers/specs/2026-08-30-smart-park-agent-showcase-ui-design.md

## Global Constraints

- Do not change, remove, or reinterpret any existing field of GET /api/operations/capabilities.
- The customer catalog has no public write endpoint. A browser, a query parameter, or a frontend clock must never make a scenario READY.
- READY means an explicitly recorded, unexpired success from the same running application process; configuration flags and Spring bean existence alone are insufficient.
- NOT_READY and DISABLED have short Chinese reasons containing neither exception text, provider names, hostnames, environment-variable names, prompt content, credentials, SQL, nor unredacted business records.
- live is true only when status equals READY; every other status returns live: false and is not launchable by later UI slices.
- This slice creates no customer-facing visual UI. The approved customer visual must wait for a usable, approved real visual asset; ImageGen was unavailable during the design phase and CSS, SVG, or emoji substitutes are prohibited.
- Keep the project real-chain rule: no Mock result, pre-recorded run, or client timer may be represented as an online showcase verification.
- The known baseline failure in ReadOnlyQueryExecutorTest.executesWhitelistedQueriesWithBoundParameters is a separate date-fixture issue. Do not change it in this slice; run focused Maven tests and report the full-suite baseline separately.

---

## File Structure and Responsibilities

- src/main/java/com/example/smartpark/showcase/ShowcaseScenarioId.java: the four stable catalog identifiers.
- src/main/java/com/example/smartpark/showcase/ShowcaseScenarioStatus.java: the three externally visible state values.
- src/main/java/com/example/smartpark/showcase/ShowcaseScenario.java: immutable application DTO with safety invariants for live and unavailableReason.
- src/main/java/com/example/smartpark/showcase/ScenarioVerificationRegistry.java: package-owned interface through which the later online preflight records verified success or failure.
- src/main/java/com/example/smartpark/showcase/InMemoryScenarioVerificationRegistry.java: thread-safe, ephemeral receipt store that invalidates an earlier success after failure.
- src/main/java/com/example/smartpark/showcase/ShowcaseProperties.java: validated smartpark.showcase.verification-ttl configuration, default 15 minutes.
- src/main/java/com/example/smartpark/showcase/ShowcaseScenarioCatalog.java: fixed scenario copy, state-precedence rules, and safe projection from configuration plus verification receipts.
- src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java: Spring wiring for the registry and catalog; it reads legacy capability inputs without editing their legacy endpoint.
- src/main/java/com/example/smartpark/web/ShowcaseScenarioController.java: read-only JSON boundary at GET /api/showcase/scenarios.
- src/test/java/com/example/smartpark/showcase/InMemoryScenarioVerificationRegistryTest.java: expiry, replacement, and failure-invalidation contract.
- src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java: state machine and safe-copy contract.
- src/test/java/com/example/smartpark/web/ShowcaseScenarioControllerTest.java: HTTP serialization and non-leakage contract.
- ui/src/services/workflowApi.ts: TypeScript response contract and getShowcaseScenarios fetcher for a later homepage slice.
- ui/src/services/workflowApi.spec.ts: response typing and request-path tests; no showcase component is mounted in this slice.
- README.md: documents the endpoint and that it reports recent verification rather than configuration alone.

### Task 1: Define the Catalog Contract and Verification Receipt Store

**Files:**

- Create: src/main/java/com/example/smartpark/showcase/ShowcaseScenarioId.java
- Create: src/main/java/com/example/smartpark/showcase/ShowcaseScenarioStatus.java
- Create: src/main/java/com/example/smartpark/showcase/ShowcaseScenario.java
- Create: src/main/java/com/example/smartpark/showcase/ScenarioVerificationRegistry.java
- Create: src/main/java/com/example/smartpark/showcase/InMemoryScenarioVerificationRegistry.java
- Test: src/test/java/com/example/smartpark/showcase/InMemoryScenarioVerificationRegistryTest.java

**Interfaces:**

- Produces ShowcaseScenarioId with exactly ALERT_WORKFLOW, EXPERT_COLLABORATION, OPERATIONS_ANALYSIS, and VOICE_ASSISTANT.
- Produces ShowcaseScenarioStatus with exactly READY, NOT_READY, and DISABLED.
- Produces ScenarioVerificationRegistry so the next online-preflight plan can invoke recordSuccess(ShowcaseScenarioId, Instant) after a real scenario succeeds and recordFailure(ShowcaseScenarioId) after it fails.
- Produces Optional<Instant> lastSuccessfulAt(ShowcaseScenarioId, Instant, Duration); callers supply now and TTL so expiry tests need no system clock or sleeping.

- [ ] **Step 1: Write the failing registry contract tests**

~~~java
@Test
void makesOnlyAnUnexpiredSuccessAvailable() {
    Instant verifiedAt = Instant.parse("2026-08-30T10:00:00Z");
    registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, verifiedAt);

    assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.EXPERT_COLLABORATION,
            verifiedAt.plus(Duration.ofMinutes(14)), Duration.ofMinutes(15))).contains(verifiedAt);
    assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.EXPERT_COLLABORATION,
            verifiedAt.plus(Duration.ofMinutes(15)), Duration.ofMinutes(15))).isEmpty();
}

@Test
void failureInvalidatesAnEarlierSuccess() {
    registry.recordSuccess(ShowcaseScenarioId.OPERATIONS_ANALYSIS, Instant.parse("2026-08-30T10:00:00Z"));
    registry.recordFailure(ShowcaseScenarioId.OPERATIONS_ANALYSIS);

    assertThat(registry.lastSuccessfulAt(ShowcaseScenarioId.OPERATIONS_ANALYSIS,
            Instant.parse("2026-08-30T10:01:00Z"), Duration.ofMinutes(15))).isEmpty();
}
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=InMemoryScenarioVerificationRegistryTest" test
~~~

Expected: compilation fails because com.example.smartpark.showcase types do not exist.

- [ ] **Step 3: Write the minimal closed contract and store**

~~~java
public interface ScenarioVerificationRegistry {
    void recordSuccess(ShowcaseScenarioId scenarioId, Instant verifiedAt);
    void recordFailure(ShowcaseScenarioId scenarioId);
    Optional<Instant> lastSuccessfulAt(ShowcaseScenarioId scenarioId, Instant now, Duration ttl);
}

public final class InMemoryScenarioVerificationRegistry implements ScenarioVerificationRegistry {
    private final ConcurrentMap<ShowcaseScenarioId, Instant> successes = new ConcurrentHashMap<>();

    @Override public void recordSuccess(ShowcaseScenarioId id, Instant verifiedAt) {
        successes.put(Objects.requireNonNull(id), Objects.requireNonNull(verifiedAt));
    }
    @Override public void recordFailure(ShowcaseScenarioId id) {
        successes.remove(Objects.requireNonNull(id));
    }
    @Override public Optional<Instant> lastSuccessfulAt(ShowcaseScenarioId id, Instant now, Duration ttl) {
        Instant verifiedAt = successes.get(Objects.requireNonNull(id));
        return verifiedAt != null && verifiedAt.plus(ttl).isAfter(now) ? Optional.of(verifiedAt) : Optional.empty();
    }
}
~~~

Implement ShowcaseScenario as a record with fields exactly id, status, live, title, businessQuestion, expectedDurationSeconds, requiredCapabilities, proofTypes, humanBoundary, unavailableReason, and lastVerifiedAt. Its compact constructor enforces live == (status == READY); READY has unavailableReason == null and a non-null lastVerifiedAt; each non-ready state has live == false, a non-blank safe reason, and lastVerifiedAt == null.

- [ ] **Step 4: Run the focused tests to verify the receipt store and invariants pass**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=InMemoryScenarioVerificationRegistryTest" test
~~~

Expected: PASS with no time-based sleeps.

- [ ] **Step 5: Commit the contract slice**

~~~powershell
git add -- src/main/java/com/example/smartpark/showcase src/test/java/com/example/smartpark/showcase/InMemoryScenarioVerificationRegistryTest.java
git commit -m "feat: define showcase verification contract"
~~~

### Task 2: Project Existing Runtime Inputs into Conservative Scenario States

**Files:**

- Create: src/main/java/com/example/smartpark/showcase/ShowcaseProperties.java
- Create: src/main/java/com/example/smartpark/showcase/ShowcaseScenarioCatalog.java
- Create: src/main/java/com/example/smartpark/showcase/ShowcaseConfiguration.java
- Test: src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java

**Interfaces:**

- Consumes ScenarioVerificationRegistry, ShowcaseScenarioId, the existing ExpertCollaborationService provider, and existing analytics/voice configuration values.
- Produces List<ShowcaseScenario> scenarios(Instant now) in the fixed identifier order from Task 1.
- Produces ShowcaseProperties.getVerificationTtl(); default is exactly Duration.ofMinutes(15), and values shorter than one minute fail configuration binding.

- [ ] **Step 1: Write failing state-precedence tests**

~~~java
@Test
void doesNotTreatConfiguredRuntimeAsAReadyShowcase() {
    ShowcaseScenario scenario = catalog.scenarios(NOW).stream()
            .filter(item -> item.id() == ShowcaseScenarioId.EXPERT_COLLABORATION)
            .findFirst().orElseThrow();

    assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.NOT_READY);
    assertThat(scenario.live()).isFalse();
    assertThat(scenario.unavailableReason()).isEqualTo("本次部署尚未完成在线验证");
}

@Test
void makesAConfiguredScenarioReadyOnlyWithAnUnexpiredReceipt() {
    registry.recordSuccess(ShowcaseScenarioId.EXPERT_COLLABORATION, NOW.minus(Duration.ofMinutes(1)));

    ShowcaseScenario scenario = catalog.scenarios(NOW).stream()
            .filter(item -> item.id() == ShowcaseScenarioId.EXPERT_COLLABORATION)
            .findFirst().orElseThrow();

    assertThat(scenario.status()).isEqualTo(ShowcaseScenarioStatus.READY);
    assertThat(scenario.live()).isTrue();
    assertThat(scenario.lastVerifiedAt()).isEqualTo(NOW.minus(Duration.ofMinutes(1)));
}

@Test
void returnsDisabledWithoutDisclosingConfigurationDetails() {
    ShowcaseScenario voice = catalog.scenarios(NOW).stream()
            .filter(item -> item.id() == ShowcaseScenarioId.VOICE_ASSISTANT)
            .findFirst().orElseThrow();

    assertThat(voice.status()).isEqualTo(ShowcaseScenarioStatus.DISABLED);
    assertThat(voice.unavailableReason()).isEqualTo("本次部署未启用语音体验");
    assertThat(voice.unavailableReason()).doesNotContain("smartpark", "DASHSCOPE", "api-key");
}
~~~

- [ ] **Step 2: Run the test to verify it fails**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=ShowcaseScenarioCatalogTest" test
~~~

Expected: compilation fails because ShowcaseScenarioCatalog and ShowcaseProperties do not exist.

- [ ] **Step 3: Implement the state machine with fixed safe copy**

Create ShowcaseProperties using @ConfigurationProperties(prefix = "smartpark.showcase"), with one Duration verificationTtl = Duration.ofMinutes(15), then reject values shorter than one minute in a @PostConstruct method.

Implement one private scenario(...) method in ShowcaseScenarioCatalog; do not expose a mutable Map or accept catalog copy from HTTP input. Apply this precedence:

~~~java
if (!featureEnabled) {
    return unavailable(id, ShowcaseScenarioStatus.DISABLED, disabledReason);
}
return registry.lastSuccessfulAt(id, now, properties.getVerificationTtl())
        .map(verifiedAt -> ready(id, verifiedAt))
        .orElseGet(() -> unavailable(id, ShowcaseScenarioStatus.NOT_READY, "本次部署尚未完成在线验证"));
~~~

Use only this fixed customer-safe copy:

| ID | title | expectedDurationSeconds | proofTypes | humanBoundary |
| --- | --- | ---: | --- | --- |
| ALERT_WORKFLOW | 告警处置 | 45 | 告警上下文、处置知识、风险闸门 | 高风险处置必须由审批人确认 |
| EXPERT_COLLABORATION | 跨域专家协作 | 40 | 专家分工、工具证据、汇总结论 | 证据不足时保留人工复核 |
| OPERATIONS_ANALYSIS | 运营分析 | 30 | 指标口径、只读查询、结果图表 | 只读数据，不自动执行操作 |
| VOICE_ASSISTANT | 实时语音助手 | 30 | 语音识别、工具调用、语音回答 | 不执行设备控制或自动审批 |

Use analyticsEnabled and voiceEnabled only to select DISABLED. Use the existing ObjectProvider<ExpertCollaborationService> only to select DISABLED for a deployment without its collaboration runtime. For alert workflow, treat either knowledgeMode != "rag" or customerAnswerMode != "dashscope" as disabled so default Mock configuration is never advertised as a customer Agent showcase. No state becomes READY through these checks.

Wire the catalog in ShowcaseConfiguration with Clock.systemUTC() as the production clock. Constructor tests pass a fixed Clock and supplied registry; do not use system time directly in the catalog.

- [ ] **Step 4: Run focused catalog tests**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=ShowcaseScenarioCatalogTest,OperationsCapabilitiesControllerTest" test
~~~

Expected: PASS; legacy controller assertions remain unchanged and the new catalog never returns READY without a fresh receipt.

- [ ] **Step 5: Commit the catalog slice**

~~~powershell
git add -- src/main/java/com/example/smartpark/showcase src/test/java/com/example/smartpark/showcase/ShowcaseScenarioCatalogTest.java
git commit -m "feat: add conservative showcase scenario catalog"
~~~

### Task 3: Expose the Read-only Catalog Without Changing Legacy Capabilities

**Files:**

- Create: src/main/java/com/example/smartpark/web/ShowcaseScenarioController.java
- Test: src/test/java/com/example/smartpark/web/ShowcaseScenarioControllerTest.java
- Modify: README.md

**Interfaces:**

- Consumes ShowcaseScenarioCatalog.
- Produces GET /api/showcase/scenarios with one top-level JSON object: { "capturedAt": "<ISO-8601 UTC>", "scenarios": [ ... ] }.
- Leaves GET /api/operations/capabilities compatible in JSON field names and shapes.

- [ ] **Step 1: Write the failing MVC tests for the boundary**

~~~java
@WebMvcTest(ShowcaseScenarioController.class)
class ShowcaseScenarioControllerTest {
    @Test
    void returnsCustomerSafeCatalogAndNoWriteRoute() throws Exception {
        mockMvc.perform(get("/api/showcase/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAt").value("2026-08-30T10:00:00Z"))
                .andExpect(jsonPath("$.scenarios[0].id").value("ALERT_WORKFLOW"))
                .andExpect(jsonPath("$.scenarios[0].live").value(false))
                .andExpect(jsonPath("$.scenarios[0].unavailableReason").value("本次部署尚未完成在线验证"));

        mockMvc.perform(post("/api/showcase/scenarios")).andExpect(status().isMethodNotAllowed());
    }
}
~~~

Assert that the customer-visible response omits internal implementation tokens such as jdbc:, api-key, and prompt; do not add a production exception-text seam solely for this assertion.

- [ ] **Step 2: Run the test to verify it fails**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=ShowcaseScenarioControllerTest" test
~~~

Expected: compilation fails because ShowcaseScenarioController is absent.

- [ ] **Step 3: Implement a GET-only controller and document it**

~~~java
@RestController
@RequestMapping("/api/showcase")
public final class ShowcaseScenarioController {
    private final ShowcaseScenarioCatalog catalog;
    private final Clock clock;

    @GetMapping("/scenarios")
    public ShowcaseScenarioCatalogResponse scenarios() {
        Instant capturedAt = clock.instant();
        return new ShowcaseScenarioCatalogResponse(capturedAt, catalog.scenarios(capturedAt));
    }
}
~~~

Place ShowcaseScenarioCatalogResponse beside the controller as a record. Do not add @PostMapping, actuator mutation, demo-role bypass, or a request parameter that can alter verification state. Add this endpoint to the README API table with the exact explanation: 返回最近在线验证驱动的客户演示场景；未验证或失效场景不可启动。

- [ ] **Step 4: Run focused web compatibility tests**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=ShowcaseScenarioControllerTest,OperationsCapabilitiesControllerTest" test
~~~

Expected: PASS; POST /api/showcase/scenarios has no handler and legacy capability tests still pass.

- [ ] **Step 5: Commit the boundary slice**

~~~powershell
git add -- src/main/java/com/example/smartpark/web/ShowcaseScenarioController.java src/test/java/com/example/smartpark/web/ShowcaseScenarioControllerTest.java README.md
git commit -m "feat: expose showcase scenario catalog"
~~~

### Task 4: Add the Typed Frontend Contract Without Building a Visual Placeholder

**Files:**

- Modify: ui/src/services/workflowApi.ts
- Create: ui/src/services/workflowApi.spec.ts

**Interfaces:**

- Consumes the GET /api/showcase/scenarios JSON from Task 3.
- Produces ShowcaseScenario, ShowcaseScenarioStatus, ShowcaseScenarioCatalog, and getShowcaseScenarios(): Promise<ShowcaseScenarioCatalog> for the later visual homepage plan.
- Does not modify ui/src/App.vue, styles, routing, or existing scenario component mount behavior.

- [ ] **Step 1: Write failing API-client tests**

~~~ts
it("loads the server-owned showcase catalog without manufacturing readiness", async () => {
  globalThis.fetch = vi.fn().mockResolvedValue(new Response(JSON.stringify({
    capturedAt: "2026-08-30T10:00:00Z",
    scenarios: [{
      id: "OPERATIONS_ANALYSIS", status: "NOT_READY", live: false,
      title: "运营分析", businessQuestion: "过去几天哪座楼能耗偏离基线？",
      expectedDurationSeconds: 30, requiredCapabilities: ["模型", "只读数据"],
      proofTypes: ["指标口径", "只读查询"], humanBoundary: "只读数据，不自动执行操作",
      unavailableReason: "本次部署尚未完成在线验证", lastVerifiedAt: null,
    }],
  }), { status: 200 }))

  await expect(getShowcaseScenarios()).resolves.toMatchObject({
    scenarios: [expect.objectContaining({ id: "OPERATIONS_ANALYSIS", live: false })],
  })
  expect(globalThis.fetch).toHaveBeenCalledWith("/api/showcase/scenarios", expect.any(Object))
})
~~~

- [ ] **Step 2: Run the test to verify the fetcher and types are missing**

Run:

~~~powershell
Push-Location ui
npm.cmd run test:unit -- workflowApi
Pop-Location
~~~

Expected: FAIL because getShowcaseScenarios and its types do not exist.

- [ ] **Step 3: Implement exact types and reuse the existing request helper**

~~~ts
export type ShowcaseScenarioStatus = "READY" | "NOT_READY" | "DISABLED"

export interface ShowcaseScenario {
  id: "ALERT_WORKFLOW" | "EXPERT_COLLABORATION" | "OPERATIONS_ANALYSIS" | "VOICE_ASSISTANT"
  status: ShowcaseScenarioStatus
  live: boolean
  title: string
  businessQuestion: string
  expectedDurationSeconds: number
  requiredCapabilities: string[]
  proofTypes: string[]
  humanBoundary: string
  unavailableReason: string | null
  lastVerifiedAt: string | null
}

export interface ShowcaseScenarioCatalog {
  capturedAt: string
  scenarios: ShowcaseScenario[]
}

export function getShowcaseScenarios() {
  return request<ShowcaseScenarioCatalog>("/api/showcase/scenarios")
}
~~~

Do not derive live from status in the client, replace an unavailable reason, or create a local fallback scenario. The later customer surface presents these server-owned values verbatim.

- [ ] **Step 4: Run API and existing frontend regression checks**

Run:

~~~powershell
Push-Location ui
npm.cmd run test:unit -- workflowApi
npm.cmd run test:unit
npm.cmd run typecheck
npm.cmd run build
Pop-Location
~~~

Expected: all frontend unit tests, typecheck, and production build pass; current App.vue behavior remains unchanged.

- [ ] **Step 5: Commit the client contract slice**

~~~powershell
git add -- ui/src/services/workflowApi.ts ui/src/services/workflowApi.spec.ts
git commit -m "feat: add typed showcase catalog client"
~~~

### Task 5: Verify the Catalog Slice and Record the Handoff Boundary

**Files:**

- Modify: README.md

**Interfaces:**

- Consumes the backend controller and typed frontend client from Tasks 1–4.
- Produces a documented verification command set and an explicit next-slice input: an online preflight calls ScenarioVerificationRegistry only after actual completion of each candidate scenario.

- [ ] **Step 1: Add the exact local verification note**

Add an Agent 客户演示目录 README subsection with this command:

~~~powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/showcase/scenarios | Select-Object -ExpandProperty Content
~~~

Document these exact interpretations: READY represents a recent same-process online verification; a fresh process has no receipt and correctly reports enabled-but-unverified scenarios as NOT_READY. Operators run the later explicit online preflight before presenting a customer demo. The endpoint does not itself call a model or provider.

- [ ] **Step 2: Run focused cross-layer checks**

Run:

~~~powershell
.\mvnw.cmd -B "-Dtest=InMemoryScenarioVerificationRegistryTest,ShowcaseScenarioCatalogTest,ShowcaseScenarioControllerTest,OperationsCapabilitiesControllerTest" test
Push-Location ui
npm.cmd ci
npm.cmd run test:unit
npm.cmd run typecheck
npm.cmd run build
Pop-Location
git diff --check
~~~

Expected: all named Java tests, all frontend unit tests, typecheck, and build pass; git diff --check prints nothing. Do not run or report mvn test as green until the separate database-relative fixture defect is repaired.

- [ ] **Step 3: Inspect the final response shape without mutating readiness**

Start the application only with an explicit local configuration already approved for the machine, then call the GET endpoint once. Verify that a deployment lacking receipts returns live: false for every non-disabled scenario and that JSON has no configuration keys, hostnames, provider error bodies, prompts, SQL, or secrets. Stop the process after the check.

- [ ] **Step 4: Commit the verification and documentation updates**

~~~powershell
git add -- README.md
git commit -m "docs: explain showcase verification catalog"
~~~

## Plan Self-Review

### Spec coverage

- Compatible capability contract: Tasks 2–3 add a separate endpoint and regression-test the existing capability controller.
- READY, NOT_READY, DISABLED, safe reason, timestamp, and non-launchable state: Tasks 1–3 define and test every response invariant.
- Online truth rather than bean/config presence: Task 2 makes a current receipt mandatory for READY; Task 5 records that GET never probes or invents success.
- No leaked prompts, keys, raw errors, SQL, or topology: Task 3 asserts response non-leakage; Task 2 owns fixed safe copy.
- Reuse of the existing Vue application and no second app: Task 4 adds a typed fetcher only and leaves App.vue unchanged.
- Required visual asset before customer UI: stated as a global constraint; this plan deliberately contains no visual component or placeholder.
- Customer homepage, task stages, evidence-track visual adaptation, cancellation/result states, and individual online preflight implementations are separate delivery plans because each has independent UI/runtime acceptance gates. The next plan consumes this API and an approved visual asset rather than duplicate state logic.

### Placeholder scan

The plan has no blank or deferred implementation instructions. New types, response fields, state priorities, customer copy, commands, and commit paths are named explicitly.

### Type consistency

- Java enum strings and TypeScript unions use the same four IDs and three status values.
- ShowcaseScenarioCatalog returns List<ShowcaseScenario>; ShowcaseScenarioController wraps the list with capturedAt; TypeScript ShowcaseScenarioCatalog matches the same shape.
- The future preflight integration calls ScenarioVerificationRegistry.recordSuccess or recordFailure, both fully defined in Task 1.
