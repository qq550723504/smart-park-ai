# Smart Park Alert Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a runnable Spring AI Alibaba learning project that diagnoses smart-park device alerts through DashScope, deterministic Mock tools, a Graph workflow, human approval, REST APIs, and SSE events.

**Architecture:** Keep park-system integrations behind `DevicePort`, `AlertPort`, `WorkOrderPort`, and `KnowledgePort`. Use Spring AI Alibaba tools and two structured-output agents for model work, while Java nodes own deterministic context collection, risk gating, idempotency, and side effects. Use a `StateGraph` with an interruptible approval node and expose the workflow through stable DTOs rather than Graph internals.

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI 1.1.2, Spring AI Alibaba 1.1.2.2, Maven Wrapper, DashScope, Spring Web, Bean Validation, JUnit 5, Mockito, and Reactor SSE.

**Spec:** `docs/superpowers/specs/2026-08-23-smart-park-alert-workflow-design.md`

## Global Constraints

- The first implementation uses only local Mock adapters for devices, alerts, work orders, and knowledge documents.
- `AI_DASHSCOPE_API_KEY` is the only API-key input; no secret is written to source, configuration, tests, logs, or documentation.
- Java 17+ is required; Maven Wrapper is committed so Maven need not be installed separately.
- Graph state is the single workflow orchestration state; model responses must be converted into structured domain objects before entering business state.
- The model must not decide whether a write operation bypasses approval; `riskGate` is deterministic Java logic.
- Tests must not call DashScope or the network by default.
- Low-risk and high-risk fixtures must be deterministic and resettable.
- Every task ends with a focused test run and an explicit Git commit containing only that task.

---

## File Map

Create the following files as the implementation grows:

```text
pom.xml
mvnw
mvnw.cmd
.mvn/wrapper/maven-wrapper.properties
.gitignore
README.md
src/main/resources/application.yml
src/main/java/com/example/smartpark/SmartParkApplication.java
src/main/java/com/example/smartpark/model/*.java
src/main/java/com/example/smartpark/park/*Port.java
src/main/java/com/example/smartpark/park/mock/MockParkSystem.java
src/main/java/com/example/smartpark/tool/*.java
src/main/java/com/example/smartpark/agent/*.java
src/main/java/com/example/smartpark/workflow/*.java
src/main/java/com/example/smartpark/web/*.java
src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java
src/test/java/com/example/smartpark/workflow/*.java
src/test/java/com/example/smartpark/web/*.java
```

`model` contains serializable domain values and DTO-independent business state. `park` contains integration ports and the in-memory implementation. `tool` contains model-callable wrappers around those ports. `agent` owns prompts and structured model calls. `workflow` owns Graph state, nodes, routing, approval, and event publishing. `web` owns HTTP/SSE contracts and never exposes `OverAllState` directly.

---

### Task 1: Scaffold the Spring Boot application and dependency boundary

**Files:**
- Create: `pom.xml`
- Create: `mvnw`
- Create: `mvnw.cmd`
- Create: `.mvn/wrapper/maven-wrapper.properties`
- Create: `.gitignore`
- Create: `src/main/resources/application.yml`
- Create: `src/main/java/com/example/smartpark/SmartParkApplication.java`
- Create: `src/test/java/com/example/smartpark/SmartParkApplicationTest.java`

**Interfaces:**
- Produces a Spring Boot application whose main class is `com.example.smartpark.SmartParkApplication`.
- Produces managed dependency versions: Boot `3.5.8`, Spring AI `1.1.2`, and Spring AI Alibaba `1.1.2.2`, matching the current official repository version set at planning time.
- Produces `spring.ai.dashscope.api-key=${AI_DASHSCOPE_API_KEY:}` and a configurable default model `qwen-plus`.

- [ ] **Step 1: Create the failing application-context test**

```java
@SpringBootTest(
        properties = {
                "spring.ai.dashscope.api-key=test-key",
                "spring.ai.dashscope.chat.options.model=qwen-plus"
        })
class SmartParkApplicationTest {

    @Test
    void applicationContextLoads() {
    }
}
```

- [ ] **Step 2: Run the test to verify the scaffold is absent**

Run: `./mvnw.cmd -Dtest=SmartParkApplicationTest test`

Expected: FAIL because `pom.xml` and the application class do not exist yet. If Java is unavailable, record that as an environment blocker and continue with static validation until a runtime is available.

- [ ] **Step 3: Generate the Maven Wrapper from the standard Maven Wrapper distribution**

Use the official wrapper files rather than hand-writing shell behavior. Set the wrapper distribution to Maven `3.9.11` in `.mvn/wrapper/maven-wrapper.properties`; keep the generated scripts unchanged except for line-ending normalization.

- [ ] **Step 4: Add the minimal dependency set**

Add the Spring AI BOM and Spring AI Alibaba BOM, then these dependencies:

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter-dashscope</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-agent-framework</artifactId>
</dependency>
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-graph-core</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-web</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

Add the repositories required by the verified official examples only when dependency resolution requires them; do not add arbitrary mirrors.

- [ ] **Step 5: Add the application class and non-secret configuration**

```java
@SpringBootApplication
public class SmartParkApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartParkApplication.class, args);
    }
}
```

Use YAML defaults that keep the app bootable without a real key, but make the real call fail clearly when the key is absent. Do not put a sample key in the file.

- [ ] **Step 6: Run the context test**

Run: `./mvnw.cmd -Dtest=SmartParkApplicationTest test`

Expected: PASS with the context loading and no secret warning caused by a committed value.

- [ ] **Step 7: Commit the scaffold**

```bash
git add pom.xml mvnw mvnw.cmd .mvn/wrapper/maven-wrapper.properties .gitignore src/main src/test
git commit -m "build: scaffold smart park Spring AI application"
```

---

### Task 2: Add domain values, integration ports, and deterministic Mock data

**Files:**
- Create: `src/main/java/com/example/smartpark/model/Alert.java`
- Create: `src/main/java/com/example/smartpark/model/AlertClassification.java`
- Create: `src/main/java/com/example/smartpark/model/Device.java`
- Create: `src/main/java/com/example/smartpark/model/Diagnosis.java`
- Create: `src/main/java/com/example/smartpark/model/KnowledgeDocument.java`
- Create: `src/main/java/com/example/smartpark/model/ParkContext.java`
- Create: `src/main/java/com/example/smartpark/model/WorkOrder.java`
- Create: `src/main/java/com/example/smartpark/model/ApprovalDecision.java`
- Create: `src/main/java/com/example/smartpark/model/RiskLevel.java`
- Create: `src/main/java/com/example/smartpark/model/WorkflowStatus.java`
- Create: `src/main/java/com/example/smartpark/park/DevicePort.java`
- Create: `src/main/java/com/example/smartpark/park/AlertPort.java`
- Create: `src/main/java/com/example/smartpark/park/WorkOrderPort.java`
- Create: `src/main/java/com/example/smartpark/park/KnowledgePort.java`
- Create: `src/main/java/com/example/smartpark/park/mock/MockParkSystem.java`
- Create: `src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java`

**Interfaces:**

```java
public interface DevicePort {
    Device getDevice(String deviceId);
}

public interface AlertPort {
    Alert getAlert(String alertId);
    List<Alert> findHistory(String deviceId);
}

public interface WorkOrderPort {
    List<WorkOrder> findByWorkflowId(String workflowId);
    WorkOrder create(String workflowId, String alertId, String summary);
}

public interface KnowledgePort {
    List<KnowledgeDocument> search(String query);
}
```

- [ ] **Step 1: Write tests for the two required fixtures**

```java
@Test
void lowRiskTemperatureAlertIsAvailable() {
    Alert alert = mockParkSystem.getAlert("ALT-TEMP-001");

    assertThat(alert.riskHint()).isEqualTo(RiskLevel.LOW);
    assertThat(alert.deviceId()).isEqualTo("DEV-HVAC-001");
}

@Test
void highRiskPowerAlertRequiresTheHighRiskFixture() {
    Alert alert = mockParkSystem.getAlert("ALT-POWER-001");

    assertThat(alert.riskHint()).isEqualTo(RiskLevel.HIGH);
    assertThat(mockParkSystem.findHistory("DEV-POWER-001")).isNotEmpty();
}

@Test
void creatingTheSameWorkflowTwiceIsIdempotent() {
    WorkOrder first = mockParkSystem.create("wf-1", "ALT-TEMP-001", "temperature anomaly");
    WorkOrder second = mockParkSystem.create("wf-1", "ALT-TEMP-001", "temperature anomaly");

    assertThat(second.id()).isEqualTo(first.id());
    assertThat(mockParkSystem.findByWorkflowId("wf-1")).hasSize(1);
}
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run: `./mvnw.cmd -Dtest=MockParkSystemTest test`

Expected: FAIL because the domain records, ports, fixtures, and idempotent store are not implemented.

- [ ] **Step 3: Implement immutable domain records and enums**

Use Java records for data values. Include explicit fields required by the spec, including IDs, timestamps, device and alert relationships, evidence, risk, approval, and workflow status. Define `ParkContext` as the immutable aggregate of one `Device`, alert history, related work orders, and the alert's park/building identifiers. Keep `WorkflowStatus` values at least `RUNNING`, `WAITING_APPROVAL`, `COMPLETED`, `REJECTED`, `FAILED`, and `WORK_ORDER_FAILED`.

- [ ] **Step 4: Implement the four ports and `MockParkSystem`**

Use `ConcurrentHashMap` for devices, alerts, histories, documents, and workflow-indexed work orders. Seed `PARK-A`, buildings `A1`/`A2`, HVAC, power, access, and pump devices; seed temperature and power alerts; seed overheating, leak, and power-emergency documents. Expose `reset()` for tests. `create` must return the existing work order for an already-seen workflow ID.

- [ ] **Step 5: Run the focused test and verify it passes**

Run: `./mvnw.cmd -Dtest=MockParkSystemTest test`

Expected: PASS with repeatable fixture data and exactly one work order for a repeated workflow ID.

- [ ] **Step 6: Commit the domain and Mock boundary**

```bash
git add src/main/java/com/example/smartpark/model src/main/java/com/example/smartpark/park src/test/java/com/example/smartpark/park
git commit -m "feat: add smart park domain and mock ports"
```

---

### Task 3: Implement Tool Calling and structured-output agents

**Files:**
- Create: `src/main/java/com/example/smartpark/tool/DeviceQueryTool.java`
- Create: `src/main/java/com/example/smartpark/tool/AlertQueryTool.java`
- Create: `src/main/java/com/example/smartpark/tool/WorkOrderTool.java`
- Create: `src/main/java/com/example/smartpark/tool/ParkKnowledgeTool.java`
- Create: `src/main/java/com/example/smartpark/agent/AlertTriageAgent.java`
- Create: `src/main/java/com/example/smartpark/agent/AlertDiagnosisAgent.java`
- Create: `src/main/java/com/example/smartpark/agent/PromptCatalog.java`
- Create: `src/test/java/com/example/smartpark/agent/TestChatModel.java`
- Create: `src/test/java/com/example/smartpark/agent/AlertTriageAgentTest.java`
- Create: `src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java`

**Interfaces:**
- Tools are Spring beans with `@Tool` methods and return serializable records; each tool calls a port, never `MockParkSystem` directly.
- `AlertTriageAgent.classify(Alert alert)` returns `AlertClassification`.
- `AlertDiagnosisAgent.diagnose(Alert alert, ParkContext context, List<KnowledgeDocument> documents)` returns `Diagnosis`.
- Both agents accept a `ChatModel` or `ChatClient.Builder` through constructor injection so tests can provide a fixed model.

- [ ] **Step 1: Write fixed-model tests for valid and invalid structured output**

```java
@Test
void triageConvertsTheModelResponseIntoAlertClassification() {
    ChatModel model = new TestChatModel("""
            {"category":"TEMPERATURE","priority":"MEDIUM","riskLevel":"LOW","confidence":0.92}
            """);

    AlertClassification result = new AlertTriageAgent(model).classify(alert);

    assertThat(result.riskLevel()).isEqualTo(RiskLevel.LOW);
    assertThat(result.confidence()).isEqualTo(0.92);
}

@Test
void malformedModelOutputDoesNotBecomeAFalseDiagnosis() {
    ChatModel model = new TestChatModel("not-json");

    assertThatThrownBy(() -> new AlertTriageAgent(model).classify(alert))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run agent tests and verify they fail**

Run: `./mvnw.cmd -Dtest='*AgentTest' test`

Expected: FAIL because tools, prompt catalog, agent classes, and response conversion do not exist.

- [ ] **Step 3: Implement deterministic tools**

Expose tools for device status, alert history, work-order lookup/creation, and knowledge search. Tool descriptions must state what data is returned and must not claim that a Mock write controls a real device. Do not expose `WorkOrderTool.create` to the diagnosis agent until the workflow reaches the explicit side-effect node.

- [ ] **Step 4: Implement prompts and structured conversion**

Use system prompts that require JSON matching the record fields, require evidence for each conclusion, and instruct the model to mark evidence as insufficient when a tool returns no data. Validate enum values, confidence range `[0,1]`, non-empty evidence, and required IDs before returning domain objects.

The test-only `TestChatModel` returns a configured `ChatResponse` without network access. It must record the last prompt and fail if called more times than the test allows, so agent tests can verify both prompt construction and bounded retry behavior.

- [ ] **Step 5: Run the agent tests and verify they pass**

Run: `./mvnw.cmd -Dtest='*AgentTest' test`

Expected: PASS without network access. Any malformed or incomplete model response must fail closed with a typed exception.

- [ ] **Step 6: Commit tools and agents**

```bash
git add src/main/java/com/example/smartpark/tool src/main/java/com/example/smartpark/agent src/test/java/com/example/smartpark/agent
git commit -m "feat: add park tools and alert agents"
```

---

### Task 4: Build the Graph workflow, risk gate, approval interrupt, and execution store

**Files:**
- Create: `src/main/java/com/example/smartpark/workflow/AlertWorkflowState.java`
- Create: `src/main/java/com/example/smartpark/workflow/WorkflowSnapshot.java`
- Create: `src/main/java/com/example/smartpark/workflow/Route.java`
- Create: `src/main/java/com/example/smartpark/workflow/AlertWorkflow.java`
- Create: `src/main/java/com/example/smartpark/workflow/AlertWorkflowNodes.java`
- Create: `src/main/java/com/example/smartpark/workflow/WorkflowExecutionStore.java`
- Create: `src/main/java/com/example/smartpark/workflow/WorkflowEvent.java`
- Create: `src/main/java/com/example/smartpark/workflow/WorkflowEventPublisher.java`
- Create: `src/test/java/com/example/smartpark/workflow/AlertWorkflowTest.java`
- Create: `src/test/java/com/example/smartpark/workflow/RiskGateTest.java`

**Interfaces:**

```java
public interface WorkflowExecutionStore {
    WorkflowSnapshot save(WorkflowSnapshot snapshot);
    Optional<WorkflowSnapshot> get(String workflowId);
    Optional<WorkflowSnapshot> findRunningByAlertId(String alertId);
}

public interface WorkflowEventPublisher {
    void publish(WorkflowEvent event);
    Flux<WorkflowEvent> events(String workflowId);
}
```

`AlertWorkflow.start(String alertId)` returns a snapshot containing `workflowId` and status. `AlertWorkflow.approve(String workflowId, ApprovalDecision decision)` validates `WAITING_APPROVAL`, resumes the same Graph thread, and returns the updated snapshot. `AlertWorkflow.status(String workflowId)` returns the latest snapshot. `WorkflowSnapshot` contains the workflow ID, alert ID, status, state payload, diagnosis, approval, work order, errors, and event sequence; it is the web-facing read model and is separate from `OverAllState`.

- [ ] **Step 1: Write the risk-gate tests first**

```java
@Test
void lowRiskWithEnoughEvidenceRoutesToWorkOrder() {
    assertThat(riskGate.route(lowRiskDiagnosis)).isEqualTo(Route.CREATE_WORK_ORDER);
}

@Test
void highRiskRoutesToHumanApproval() {
    assertThat(riskGate.route(highRiskDiagnosis)).isEqualTo(Route.WAIT_FOR_APPROVAL);
}

@Test
void lowConfidenceRoutesToHumanApprovalEvenWhenRiskHintIsLow() {
    assertThat(riskGate.route(lowConfidenceDiagnosis)).isEqualTo(Route.WAIT_FOR_APPROVAL);
}
```

- [ ] **Step 2: Write workflow tests for completion, pause, approve, and reject**

```java
@Test
void lowRiskAlertCompletesWithOneWorkOrder() {
    WorkflowSnapshot result = workflow.start("ALT-TEMP-001");

    assertThat(result.status()).isEqualTo(WorkflowStatus.COMPLETED);
    assertThat(result.workOrder()).isNotNull();
    assertThat(workOrderPort.findByWorkflowId(result.workflowId())).hasSize(1);
}

@Test
void highRiskAlertPausesAndApprovalResumesTheSameThread() {
    WorkflowSnapshot waiting = workflow.start("ALT-POWER-001");
    WorkflowSnapshot completed = workflow.approve(
            waiting.workflowId(), new ApprovalDecision("APPROVE", "operator-1", "safe to dispatch"));

    assertThat(waiting.status()).isEqualTo(WorkflowStatus.WAITING_APPROVAL);
    assertThat(completed.workflowId()).isEqualTo(waiting.workflowId());
    assertThat(completed.status()).isEqualTo(WorkflowStatus.COMPLETED);
}

@Test
void rejectionEndsWithoutCreatingAWorkOrder() {
    WorkflowSnapshot waiting = workflow.start("ALT-POWER-001");

    WorkflowSnapshot rejected = workflow.approve(
            waiting.workflowId(), new ApprovalDecision("REJECT", "operator-1", "insufficient evidence"));

    assertThat(rejected.status()).isEqualTo(WorkflowStatus.REJECTED);
    assertThat(workOrderPort.findByWorkflowId(waiting.workflowId())).isEmpty();
}
```

- [ ] **Step 3: Run workflow tests and verify they fail**

Run: `./mvnw.cmd -Dtest='*WorkflowTest,RiskGateTest' test`

Expected: FAIL because Graph state, nodes, store, event publisher, and routing do not exist.

- [ ] **Step 4: Define `AlertWorkflowState` and status merge rules**

Represent the required fields from the spec in a serializable state object. Define stable keys for `alert`, `classification`, `parkContext`, `retrievedDocuments`, `diagnosis`, `riskLevel`, `approval`, `workOrder`, `status`, `errors`, and `eventSequence`. Every node returns only its state delta; the workflow applies deltas through one merge function.

- [ ] **Step 5: Assemble the `StateGraph`**

Use the verified Graph concepts `StateGraph`, `OverAllState`, `CompiledGraph`, `RunnableConfig`, and an interruptible approval action. Add nodes in this order: `classifyAlert`, `collectParkContext`, `retrieveKnowledge`, `diagnoseAlert`, `riskGate`, `humanApproval`, `createWorkOrder`, and `summarizeResult`. Add a conditional edge after `riskGate`; low-risk goes directly to `createWorkOrder`, high-risk goes to the interruptible approval node, and rejection goes to `END` with `REJECTED`.

- [ ] **Step 6: Implement in-memory execution and event state**

Store the compiled graph, current snapshot, and Graph thread ID by `workflowId`. Publish node-start and node-complete events with a monotonically increasing sequence. Before creating a work order, query `WorkOrderPort.findByWorkflowId`; if present, reuse it and skip the side effect. Validate approval state and decision before calling Graph resume.

- [ ] **Step 7: Run workflow tests and verify they pass**

Run: `./mvnw.cmd -Dtest='*WorkflowTest,RiskGateTest' test`

Expected: PASS for low-risk completion, high-risk interruption, approval resume, rejection, duplicate approval rejection, and one-work-order idempotency.

- [ ] **Step 8: Commit the workflow core**

```bash
git add src/main/java/com/example/smartpark/workflow src/test/java/com/example/smartpark/workflow
git commit -m "feat: orchestrate alert diagnosis with graph workflow"
```

---

### Task 5: Expose REST and SSE contracts

**Files:**
- Create: `src/main/java/com/example/smartpark/web/AlertWorkflowController.java`
- Create: `src/main/java/com/example/smartpark/web/ApprovalController.java`
- Create: `src/main/java/com/example/smartpark/web/WorkflowEventController.java`
- Create: `src/main/java/com/example/smartpark/web/WebDtos.java`
- Create: `src/main/java/com/example/smartpark/web/ApiExceptionHandler.java`
- Create: `src/test/java/com/example/smartpark/web/AlertWorkflowControllerTest.java`
- Create: `src/test/java/com/example/smartpark/web/WorkflowEventControllerTest.java`

**Interfaces:**

```text
POST /api/alerts/{alertId}/workflows
GET  /api/workflows/{workflowId}
POST /api/workflows/{workflowId}/approval
GET  /api/workflows/{workflowId}/events   (text/event-stream)
```

Approval JSON is `{ "decision": "APPROVE|REJECT", "reviewer": "...", "comment": "..." }`. Response DTOs include `workflowId`, `alertId`, `status`, `diagnosis`, `approval`, `workOrder`, `errors`, and `eventSequence`; they do not include `OverAllState`, model headers, API keys, or raw prompt text.

- [ ] **Step 1: Write MockMvc tests for all HTTP boundaries**

```java
@Test
void startingKnownAlertReturnsWorkflowId() throws Exception {
    mockMvc.perform(post("/api/alerts/ALT-TEMP-001/workflows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.workflowId").isString())
            .andExpect(jsonPath("$.status").value("COMPLETED"));
}

@Test
void approvingUnknownOrNonWaitingWorkflowReturnsConflict() throws Exception {
    mockMvc.perform(post("/api/workflows/missing/approval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVE\",\"reviewer\":\"u1\",\"comment\":\"ok\"}"))
            .andExpect(status().isNotFound());
}

@Test
void approvalValidationRejectsBlankReviewer() throws Exception {
    mockMvc.perform(post("/api/workflows/wf-1/approval")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"decision\":\"APPROVE\",\"reviewer\":\"\",\"comment\":\"ok\"}"))
            .andExpect(status().isBadRequest());
}
```

- [ ] **Step 2: Run web tests and verify they fail**

Run: `./mvnw.cmd -Dtest='*ControllerTest' test`

Expected: FAIL because controllers, DTOs, validation, and exception mapping do not exist.

- [ ] **Step 3: Implement DTOs and controllers**

Use `@RestController`, constructor injection, `@Validated`, and explicit mapping from workflow snapshots to response DTOs. Return `404` for missing IDs, `409` for invalid workflow state or duplicate approval, and `400` for malformed approval input. Keep the start endpoint deterministic for the seeded fixtures.

- [ ] **Step 4: Implement the SSE endpoint**

Return `Flux<ServerSentEvent<WorkflowEventDto>>` with `MediaType.TEXT_EVENT_STREAM_VALUE`. Map each internal event to a DTO containing event ID, type, node, sequence, timestamp, and redacted summary. Complete the stream when the workflow completes or fails; do not block the request thread waiting for a model response.

- [ ] **Step 5: Run web tests and verify they pass**

Run: `./mvnw.cmd -Dtest='*ControllerTest' test`

Expected: PASS for start, status, approval validation, invalid states, unknown IDs, and SSE event shape.

- [ ] **Step 6: Commit the web layer**

```bash
git add src/main/java/com/example/smartpark/web src/test/java/com/example/smartpark/web
git commit -m "feat: expose alert workflow and SSE events"
```

---

### Task 6: Add failure-path, security, and integration coverage

**Files:**
- Modify: `src/main/java/com/example/smartpark/workflow/AlertWorkflow.java`
- Modify: `src/main/java/com/example/smartpark/workflow/AlertWorkflowNodes.java`
- Modify: `src/main/java/com/example/smartpark/web/ApiExceptionHandler.java`
- Create: `src/test/java/com/example/smartpark/workflow/AlertWorkflowFailureTest.java`
- Create: `src/test/java/com/example/smartpark/SensitiveDataTest.java`

- [ ] **Step 1: Write failure-path tests**

Cover DashScope/model timeout through a fixed failing model, tool lookup failure, malformed structured output, work-order creation failure, duplicate approval, and duplicate workflow start. Assert that failure states preserve completed evidence and never fabricate a work-order ID.

- [ ] **Step 2: Implement fail-closed transitions**

Convert model and tool exceptions to explicit workflow errors. Allow only bounded structured-output retries. Route missing evidence to approval, keep work-order failure as `WORK_ORDER_FAILED`, and ensure repeated resume cannot execute the work-order side effect twice.

- [ ] **Step 3: Add sensitive-data assertions**

Scan committed configuration and test resources for `sk-`, `Bearer `, `AI_DASHSCOPE_API_KEY=` with a non-empty value, and known secret-like literals. Assert event DTO serialization does not contain prompt text, request headers, or API-key configuration.

- [ ] **Step 4: Run the full offline test suite**

Run: `./mvnw.cmd test`

Expected: PASS without network calls. If the environment lacks Java, run `git diff --check`, dependency/model static checks, and record the exact runtime blocker rather than claiming tests passed.

- [ ] **Step 5: Commit hardening coverage**

```bash
git add src/main/java/com/example/smartpark/workflow src/main/java/com/example/smartpark/web src/test/java
git commit -m "test: cover workflow failures and sensitive data boundaries"
```

---

### Task 7: Document operation, real DashScope verification, and final review

**Files:**
- Create: `README.md`
- Modify: `src/main/resources/application.yml`
- Create: `src/test/resources/application-test.yml`

- [ ] **Step 1: Document local setup without exposing credentials**

README must include Java 17+, PowerShell and macOS/Linux environment setup, `./mvnw.cmd spring-boot:run` / `./mvnw spring-boot:run`, seeded alert IDs, REST examples, approval example, SSE curl command, and the fact that Mock adapters do not control real devices.

- [ ] **Step 2: Document the exact learning map**

Explain which package demonstrates ChatModel/ChatClient, Tool Calling, structured output, Graph state, interruption/resume, risk gates, idempotency, and SSE. Explicitly mark Embedding/RAG, PostgreSQL checkpointing, and real adapters as later exercises.

- [ ] **Step 3: Add safe test configuration**

Ensure `application-test.yml` uses a placeholder such as `test-key` only in test scope and that production/default configuration reads `AI_DASHSCOPE_API_KEY` from the environment. Do not add a `.env` file.

- [ ] **Step 4: Verify static and runtime paths**

Run:

```powershell
./mvnw.cmd test
./mvnw.cmd package -DskipTests
```

With a user-provided key in the current process only, run the application and verify:

```powershell
$env:AI_DASHSCOPE_API_KEY = '<user-provided-key>'
./mvnw.cmd spring-boot:run
Invoke-RestMethod -Method Post http://localhost:8080/api/alerts/ALT-TEMP-001/workflows
Invoke-RestMethod -Method Post http://localhost:8080/api/alerts/ALT-POWER-001/workflows
Invoke-RestMethod -Method Post http://localhost:8080/api/workflows/<workflow-id>/approval -ContentType 'application/json' -Body '{"decision":"APPROVE","reviewer":"operator-1","comment":"approved"}'
```

Never print the key, include it in command history documentation, or commit it. Unset the process environment variable after verification.

- [ ] **Step 5: Review repository ownership and final status**

Run `git status --short`, `git log --oneline --decorate -8`, `git diff HEAD~1 --check`, and `rg -n -i 'sk-|Bearer |AI_DASHSCOPE_API_KEY=.+|TODO|TBD' .`. Confirm only intended files changed and report local tests, real DashScope verification, and any missing JDK separately.

- [ ] **Step 6: Commit documentation and final verification**

```bash
git add README.md src/main/resources/application.yml src/test/resources/application-test.yml
git commit -m "docs: explain smart park alert workflow"
```

## Plan Self-Review

- Spec coverage: tasks 1-2 cover project foundation and Mock adapters; task 3 covers tools and two agents; task 4 covers every Graph node, state, risk gate, approval, resume, events, and idempotency; task 5 covers all REST/SSE endpoints; task 6 covers failure and sensitive-data behavior; task 7 covers operation and real-key verification.
- Placeholder scan: no implementation step depends on `TODO`, `TBD`, or an unspecified adapter. Version and command values are explicit; the only runtime value intentionally supplied by the user is the DashScope key.
- Type consistency: the port signatures, workflow store methods, workflow entry points, DTO fields, enum values, and test fixture IDs are repeated consistently across tasks.
- Scope: the plan ends after the first runnable vertical slice. Vector RAG, durable PostgreSQL checkpointing, real park APIs, authentication, and device control remain separate follow-up work as required by the spec.
