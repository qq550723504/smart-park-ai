# Capability Boundaries Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按告警、能耗和未来安防能力拆分领域模型、端口、工具与 Mock 适配器，同时保持现有工作流、HTTP API、DashScope 配置和种子场景行为不变。

**Architecture:** 保留 `agent/`、`workflow/`、`web/` 作为跨场景应用层；将场景模型、端口和工具移动到能力包，其中聚合告警历史的 `ParkContext` 属于 `model/alert/`，`model/common/` 不依赖告警能力；用共享 `MockParkDataStore` 加五个端口适配器替代聚合 `MockParkSystem`。Spring 运行时通过 `MockParkConfiguration` 注入端口，测试通过 `MockParkFixture` 组装同一组适配器。

**Tech Stack:** Java 17, Spring Boot 3.5.8, Spring AI Alibaba 1.1.2.2, JUnit 5, AssertJ, Maven Wrapper。

**Spec:** `docs/superpowers/specs/2026-08-23-capability-boundaries-design.md`

## Global Constraints

- 不引入 Maven 多模块。
- 不改变现有 REST、SSE、Graph 节点、工作流状态、审批幂等键和 DashScope 配置。
- `ALT-TEMP-001`、`ALT-POWER-001`、`ALT-ENERGY-001` 的业务行为保持不变。
- Mock 适配器只能读写内存种子数据，不控制真实园区设备。
- 安防模型和端口只预留能力边界，本次不接入真实摄像头、门禁或人员系统。
- 每个任务遵守 TDD：先写一个会失败的行为测试，确认失败后再实现，最后跑回归测试。

---

### Task 1: 按能力移动领域模型和端口

**Files:**
- Move: `src/main/java/com/example/smartpark/model/Alert.java` -> `src/main/java/com/example/smartpark/model/alert/Alert.java`
- Move: `src/main/java/com/example/smartpark/model/AlertClassification.java` -> `src/main/java/com/example/smartpark/model/alert/AlertClassification.java`
- Move: `src/main/java/com/example/smartpark/model/EnergyReading.java` -> `src/main/java/com/example/smartpark/model/energy/EnergyReading.java`
- Move: `src/main/java/com/example/smartpark/model/ParkContext.java` into `src/main/java/com/example/smartpark/model/alert/ParkContext.java`
- Move: `src/main/java/com/example/smartpark/model/ApprovalDecision.java`, `Diagnosis.java`, `Device.java`, `KnowledgeDocument.java`, `RiskLevel.java`, `WorkflowStatus.java`, `WorkOrder.java` into `src/main/java/com/example/smartpark/model/common/`
- Move: `src/main/java/com/example/smartpark/park/AlertPort.java` -> `src/main/java/com/example/smartpark/port/alert/AlertPort.java`
- Move: `src/main/java/com/example/smartpark/park/DevicePort.java` -> `src/main/java/com/example/smartpark/port/device/DevicePort.java`
- Move: `src/main/java/com/example/smartpark/park/EnergyPort.java` -> `src/main/java/com/example/smartpark/port/energy/EnergyPort.java`
- Move: `src/main/java/com/example/smartpark/park/KnowledgePort.java` -> `src/main/java/com/example/smartpark/port/knowledge/KnowledgePort.java`
- Move: `src/main/java/com/example/smartpark/park/WorkOrderPort.java` -> `src/main/java/com/example/smartpark/port/workorder/WorkOrderPort.java`
- Modify: all Java imports and package declarations that reference the moved classes.
- Test: existing model tests plus compilation of the full test source set.

**Interfaces:**
- Produces `com.example.smartpark.model.alert.Alert`, `com.example.smartpark.model.alert.AlertClassification`, `com.example.smartpark.model.alert.ParkContext`, `com.example.smartpark.model.energy.EnergyReading`.
- Produces `com.example.smartpark.port.*` interfaces with the existing method signatures unchanged.

- [ ] **Step 1: Write the failing package-boundary test**

Add `src/test/java/com/example/smartpark/architecture/CapabilityPackageTest.java` that loads the moved classes and asserts their package names:

```java
@Test
void scenarioModelsLiveInCapabilityPackages() {
    assertThat(Alert.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    assertThat(ParkContext.class.getPackageName()).isEqualTo("com.example.smartpark.model.alert");
    assertThat(EnergyReading.class.getPackageName()).isEqualTo("com.example.smartpark.model.energy");
    assertThat(EnergyPort.class.getPackageName()).isEqualTo("com.example.smartpark.port.energy");
}
```

- [ ] **Step 2: Run the boundary test to verify it fails**

Run: `.\mvnw.cmd -q '-Dtest=CapabilityPackageTest' test`

Expected: test compilation or assertion failure because the current classes are still in `model` and `park`.

- [ ] **Step 3: Move files and update package declarations/imports**

Create the capability directories, move each listed file, update package declarations, and use `rg -n "com\.example\.smartpark\.(model|park)" src` to update every stale import. Do not change record fields, port methods, validation, or behavior.

- [ ] **Step 4: Run focused and full compilation tests**

Run: `.\mvnw.cmd -q '-Dtest=CapabilityPackageTest,EnergyReadingTest,DiagnosisTest' test`

Then run: `.\mvnw.cmd -q -DskipTests compile`

Expected: all focused tests pass and production compilation succeeds.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: group park models and ports by capability"
```

### Task 2: 按能力移动只读工具并保持 Agent 工具集合

**Files:**
- Move: `src/main/java/com/example/smartpark/tool/AlertQueryTool.java` -> `src/main/java/com/example/smartpark/tool/alert/AlertQueryTool.java`
- Move: `src/main/java/com/example/smartpark/tool/DeviceQueryTool.java` -> `src/main/java/com/example/smartpark/tool/device/DeviceQueryTool.java`
- Move: `src/main/java/com/example/smartpark/tool/EnergyQueryTool.java` -> `src/main/java/com/example/smartpark/tool/energy/EnergyQueryTool.java`
- Move: `src/main/java/com/example/smartpark/tool/ParkKnowledgeTool.java` -> `src/main/java/com/example/smartpark/tool/knowledge/ParkKnowledgeTool.java`
- Move: `src/main/java/com/example/smartpark/tool/WorkOrderTool.java` -> `src/main/java/com/example/smartpark/tool/workorder/WorkOrderTool.java`
- Modify: `src/main/java/com/example/smartpark/agent/AlertDiagnosisAgent.java`
- Test: `src/test/java/com/example/smartpark/agent/AlertDiagnosisAgentTest.java`, `src/test/java/com/example/smartpark/tool/ParkToolsTest.java`, and a package-boundary assertion.

**Interfaces:**
- Tool method names remain `lookupAlert`, `lookupAlertHistory`, `lookupDeviceStatus`, `lookupEnergyConsumption`, `searchParkKnowledge`, `lookupWorkOrders`, and `createWorkOrder`.
- `AlertDiagnosisAgent` continues to expose only read-only callbacks to the model; `createWorkOrder` remains excluded.

- [ ] **Step 1: Add the failing tool-package assertion**

Extend `CapabilityPackageTest` with:

```java
@Test
void scenarioToolsLiveInCapabilityPackages() {
    assertThat(EnergyQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.energy");
    assertThat(AlertQueryTool.class.getPackageName()).isEqualTo("com.example.smartpark.tool.alert");
}
```

- [ ] **Step 2: Run it and confirm the old package fails**

Run: `.\mvnw.cmd -q '-Dtest=CapabilityPackageTest' test`

Expected: failure until the tool classes move.

- [ ] **Step 3: Move tools and update Agent imports**

Move the five tool files, update package declarations, update all tests and `AlertDiagnosisAgent` imports, and keep the six-argument constructor plus backward-compatible five-argument test constructor behavior unchanged.

- [ ] **Step 4: Verify tool callback behavior**

Run: `.\mvnw.cmd -q '-Dtest=CapabilityPackageTest,AlertDiagnosisAgentTest,EnergyQueryToolTest,ParkToolsTest' test`

Expected: package assertions pass, `lookupEnergyConsumption` remains exposed, and `createWorkOrder` remains absent from diagnosis callbacks.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: group park tools by capability"
```

### Task 3: 抽取共享 Mock 数据存储

**Files:**
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockParkDataStore.java`
- Move fixture seeding and mutable maps from `src/main/java/com/example/smartpark/park/mock/MockParkSystem.java` into `MockParkDataStore`.
- Test: `src/test/java/com/example/smartpark/adapter/mock/MockParkDataStoreTest.java`

**Interfaces:**
- `MockParkDataStore()` seeds all current devices, alerts, history, energy readings, knowledge documents, and empty work orders.
- `void reset()` restores the exact initial fixture state.
- Package-private or narrowly scoped accessors expose the maps and work-order sequence only to adapters in `adapter.mock`.
- `WorkOrder buildWorkOrder(String workflowId, String alertId, String summary)` remains idempotent through the shared store.

- [ ] **Step 1: Write failing reset/idempotency tests**

Add tests that create a work order through the store, call `reset()`, and assert the work order disappears; also assert `create` for the same workflow returns the same work-order ID.

- [ ] **Step 2: Run the tests and confirm the store does not exist**

Run: `.\mvnw.cmd -q '-Dtest=MockParkDataStoreTest' test`

Expected: compilation failure because `MockParkDataStore` has not been created.

- [ ] **Step 3: Implement the store by extracting fixture behavior**

Move the existing constants, maps, seeding methods, lookup helper, and work-order sequence into the store. Preserve all IDs, timestamps, text, validation, and sorting behavior. Do not add security fixtures yet.

- [ ] **Step 4: Run store and existing Mock tests**

Run: `.\mvnw.cmd -q '-Dtest=MockParkDataStoreTest,MockParkSystemTest' test`

Expected: the new store tests pass; existing tests may still use the old aggregate until Task 4.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: extract shared mock park data store"
```

### Task 4: 按端口拆分 Mock 适配器和测试 Fixture

**Files:**
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockAlertAdapter.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockDeviceAdapter.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockEnergyAdapter.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockKnowledgeAdapter.java`
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockWorkOrderAdapter.java`
- Delete after migration: `src/main/java/com/example/smartpark/park/mock/MockParkSystem.java`
- Create: `src/test/java/com/example/smartpark/adapter/mock/MockParkFixture.java`
- Move: `src/test/java/com/example/smartpark/park/mock/MockParkSystemTest.java` -> `src/test/java/com/example/smartpark/adapter/mock/MockParkFixtureTest.java`
- Modify: all remaining tests currently importing or constructing `MockParkSystem`.
- Test: `src/test/java/com/example/smartpark/adapter/mock/MockAdapterBoundaryTest.java`

**Interfaces:**
- Each adapter implements exactly one port and receives one `MockParkDataStore` in its constructor.
- `MockParkFixture` owns one store and exposes `alerts()`, `devices()`, `energy()`, `knowledge()`, and `workOrders()` adapters plus `reset()`.
- No adapter implements another capability port.

- [ ] **Step 1: Write failing adapter-boundary tests**

Add tests that assert each adapter is assignable to its own port and not to an unrelated port, and that the energy adapter returns the existing `DEV-ENERGY-001` reading.

- [ ] **Step 2: Run boundary tests and confirm adapters are missing**

Run: `.\mvnw.cmd -q '-Dtest=MockAdapterBoundaryTest' test`

Expected: compilation failure because the adapter classes do not exist.

- [ ] **Step 3: Implement the five adapters and test fixture**

Delegate each port method to `MockParkDataStore`. Keep `MockWorkOrderAdapter.create` and `findByWorkflowId` backed by the same store instance used by alert and device adapters. Move the current aggregate tests to use `MockParkFixture` without changing assertions.

- [ ] **Step 4: Verify adapter behavior and full test compilation**

Run: `.\mvnw.cmd -q '-Dtest=MockAdapterBoundaryTest,MockParkDataStoreTest,MockParkFixtureTest,EnergyQueryToolTest' test`

Then run: `.\mvnw.cmd -q -DskipTests test-compile`

Expected: adapter boundaries, reset behavior, energy lookup, and migrated tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: split mock park adapters by port"
```

### Task 5: 更新 Spring 运行时装配为端口依赖

**Files:**
- Create: `src/main/java/com/example/smartpark/adapter/mock/MockParkConfiguration.java`
- Modify: `src/main/java/com/example/smartpark/web/AlertWorkflowController.java`
- Modify: `src/main/java/com/example/smartpark/agent/AlertDiagnosisAgent.java` imports only as required by moved tools.
- Test: `src/test/java/com/example/smartpark/SmartParkApplicationTest.java` and a new context assertion in `src/test/java/com/example/smartpark/adapter/mock/MockParkConfigurationTest.java`.

**Interfaces:**
- `MockParkConfiguration` registers one shared `MockParkDataStore` and one bean for each of the five port adapters under the existing DashScope-enabled condition.
- `AlertWorkflowRuntimeConfiguration.alertWorkflow(...)` consumes `AlertPort`, `DevicePort`, `WorkOrderPort`, and `KnowledgePort`, not `MockParkSystem`.
- `AlertWorkflow` receives the same port implementations as before; no workflow method signature changes.

- [ ] **Step 1: Add a failing context-bean test**

Add a Spring test that starts the application and asserts the context contains one `AlertPort`, one `DevicePort`, one `EnergyPort`, one `KnowledgePort`, and one `WorkOrderPort`, with no `MockParkSystem` bean type.

- [ ] **Step 2: Run the context test and capture the old wiring failure**

Run: `.\mvnw.cmd -q '-Dtest=MockParkConfigurationTest' test`

Expected: failure because the current runtime exposes only the aggregate `MockParkSystem` bean.

- [ ] **Step 3: Register adapter beans and change workflow assembly**

Move the runtime Mock bean methods into `MockParkConfiguration`, inject port interfaces into `alertWorkflow`, and remove the `MockParkSystem` dependency from the web configuration. Keep all conditions and in-memory stores enabled exactly as before.

- [ ] **Step 4: Verify application context and HTTP behavior**

Run: `.\mvnw.cmd -q '-Dtest=MockParkConfigurationTest,SmartParkApplicationTest,AlertWorkflowControllerTest,WorkflowEventControllerTest,WorkflowRuntimeControllerTest' test`

Expected: context starts, all required ports resolve, and existing endpoint tests pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java src/test/java
git commit -m "refactor: wire smart park runtime through capability ports"
```

### Task 6: 安防边界预留与文档同步

**Files:**
- Create: `src/main/java/com/example/smartpark/model/security/SecurityEvent.java`
- Create: `src/main/java/com/example/smartpark/port/security/SecurityPort.java`
- Create: `src/test/java/com/example/smartpark/architecture/SecurityBoundaryTest.java`
- Modify: `src/test/java/com/example/smartpark/adapter/mock/MockParkConfigurationTest.java` to assert `applicationContext.getBeansOfType(SecurityPort.class)` is empty.
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-08-23-capability-boundaries-design.md` to record the final `ParkContext` package and the zero-runtime-security-bean constraint.

**Interfaces:**
- `SecurityEvent` is a minimal immutable boundary model with `eventId`, `parkId`, `buildingId`, `eventType`, `occurredAt`, and redacted `evidenceSummary`; it must reject blank identifiers and blank evidence.
- `SecurityPort` exposes only `SecurityEvent getEvent(String eventId)` and does not expose camera bytes, face embeddings, or raw person records.
- No Spring Bean or Mock implementation is added for security in this task; the port is a compile-time boundary for the next scenario.

- [ ] **Step 1: Write the failing security boundary test**

Assert that `SecurityEvent` is in `model.security`, `SecurityPort` is in `port.security`, and `SecurityEvent` rejects blank `evidenceSummary`.

- [ ] **Step 2: Run the test and verify the boundary is absent**

Run: `.\mvnw.cmd -q '-Dtest=SecurityBoundaryTest' test`

Expected: compilation failure because the security boundary types do not exist.

- [ ] **Step 3: Add the minimal security boundary types**

Implement only the immutable record validation and port signature above. Do not add security workflow branches, tools, HTTP endpoints, or real adapters.

- [ ] **Step 4: Update README and run full verification**

Document the new package layout, the future security boundary, and the fact that no security data source is wired yet. Keep `SecurityPort` as a compile-time-only boundary and run:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package -DskipTests
git diff --check
```

Expected: all tests pass except the intentionally skipped real DashScope smoke test, packaging succeeds, and `git diff --check` reports no whitespace errors.

- [ ] **Step 5: Commit**

```powershell
git add README.md src/main/java src/test/java docs/superpowers/specs
git commit -m "refactor: reserve security capability boundary"
```

### Task 7: 最终回归与工作区审计

**Files:**
- Verify all files changed by Tasks 1–6.
- Do not modify unrelated files or commit `target/`.

- [ ] **Step 1: Run the complete verification commands**

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package -DskipTests
git diff --check d76c9dd..HEAD
git status --short
```

- [ ] **Step 2: Check required results**

Confirm test failures and errors are zero, the real DashScope smoke test is skipped unless explicitly enabled with a key, the packaged JAR exists under `target/`, and `target/` remains ignored.

- [ ] **Step 3: Review the final diff**

Run `git diff d76c9dd..HEAD --stat` and `git log --oneline --decorate -8`. Confirm every post-design commit is scoped to the capability-boundary migration and that the pre-existing `application.yml` DashScope URL configuration remains present.
