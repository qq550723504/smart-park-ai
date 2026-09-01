# 统一协同中心只读版 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有运营工作台中增加一个受权限保护的只读协同中心，统一呈现告警流程和客服工单，并跳回原场景处理。

**Architecture:** 在 `collaborationcenter` 应用边界新增只读查询服务和稳定 DTO，不修改 `WorkflowSnapshot`、`WorkOrder` 或 `CustomerTicket` 领域模型。REST 层负责角色与查询参数门禁，Vue 层只消费投影并通过已有工作台视图切换，不复制任何写操作。

**Tech Stack:** Spring Boot 4、Java 17、JUnit 5、AssertJ、Spring MVC MockMvc、Vue 3、TypeScript、Vitest、Element Plus。

**Spec:** `docs/superpowers/specs/2026-09-01-collaboration-center-design.md`

## Global Constraints

- 本批只读，不新增自动派单、自动审批、设备控制、持久化或生产认证。
- 不把告警工单和客服工单合并成领域对象；只新增应用层投影 `CollaborationWorkItem`。
- 不返回审批人、审批意见、诊断正文、知识正文、原始工具结果、人员身份或敏感标识。
- 角色仅允许 `CUSTOMER_AGENT` 或 `ADMIN`；其他角色沿用现有权限错误。
- 默认最多返回 50 条工作项，非法 `source`、`status`、`limit` 参数返回 HTTP 400。
- 所有实现按 TDD：先写一个会失败的测试，确认失败后再写最小生产代码。

---

### Task 1: 建立协同中心只读投影与查询服务

**Files:**
- Create: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationWorkItem.java`
- Create: `src/main/java/com/example/smartpark/collaborationcenter/WorkItemQuery.java`
- Create: `src/main/java/com/example/smartpark/collaborationcenter/CollaborationCenterService.java`
- Test: `src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java`

**Interfaces:**
- Consumes: `WorkflowExecutionStore.snapshots()`、`CustomerTicketPort.list()`。
- Produces: `CollaborationCenterService.list(WorkItemQuery query): List<CollaborationWorkItem>`。

- [ ] **Step 1: Write the failing test**

在 `CollaborationCenterServiceTest` 中构造一个告警快照和一个客服工单，断言服务返回来源前缀 ID、状态、优先级、受控摘要、更新时间和跳转路径；再增加空列表、默认 50 条上限和未知状态拒绝测试。

```java
@Test
void projectsAlertAndCustomerTicketWithoutLeakingDomainObjects() {
    List<CollaborationWorkItem> items = service.list(WorkItemQuery.defaults());

    assertThat(items).extracting(CollaborationWorkItem::id)
            .containsExactly("ALERT_WORKFLOW:wf-1", "CUSTOMER_TICKET:cs-1");
    assertThat(items.get(0).safeSummary()).doesNotContain("raw diagnosis", "approval comment");
    assertThat(items.get(0).detailPath()).isEqualTo("workflow");
    assertThat(items.get(1).detailPath()).isEqualTo("customer");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=CollaborationCenterServiceTest test`

Expected: FAIL because `CollaborationCenterService`, `CollaborationWorkItem` and `WorkItemQuery` do not exist.

- [ ] **Step 3: Write minimal implementation**

定义以下稳定类型：

```java
public record CollaborationWorkItem(
        String id, Source source, Status status, Priority priority,
        String title, String safeSummary, String parkId, String buildingId,
        String deviceId, Instant updatedAt, String detailPath) {}

public record WorkItemQuery(Source source, Status status, int limit) {
    public static WorkItemQuery defaults() { return new WorkItemQuery(null, null, 50); }
}
```

`CollaborationCenterService` 将 `WorkflowStatus` 映射为同名 `Status`，将 `CustomerTicketStatus` 映射为客服状态；告警优先级从 `RiskLevel.HIGH` 映射为 `HIGH`，客服固定为 `NORMAL`。告警摘要只从 `alertId`、`status`、`buildingId` 和 `deviceId` 组成，客服摘要只使用 `safeSummary`。合并后按 `updatedAt` 倒序并截断到 query limit。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=CollaborationCenterServiceTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/collaborationcenter src/test/java/com/example/smartpark/collaborationcenter/CollaborationCenterServiceTest.java
git commit -m "feat: add collaboration center read projection"
```

### Task 2: 暴露受权限和参数白名单保护的 REST 接口

**Files:**
- Create: `src/main/java/com/example/smartpark/web/CollaborationCenterController.java`
- Create: `src/main/java/com/example/smartpark/web/CollaborationCenterDtos.java`
- Test: `src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java`

**Interfaces:**
- Consumes: `CollaborationCenterService.list(WorkItemQuery)`、现有 `DemoRole.require`。
- Produces: `GET /api/collaboration/work-items`，返回 `List<CollaborationCenterDtos.WorkItemResponse>`。

- [ ] **Step 1: Write the failing test**

使用 MockMvc 覆盖管理员成功、普通查看者被拒绝、非法 source/status/limit 返回 400、默认 limit=50，以及 JSON 不包含领域原始字段。

```java
@Test
void adminCanReadSafeWorkItems() throws Exception {
    mockMvc.perform(get("/api/collaboration/work-items")
                    .header("X-Demo-Role", "ADMIN"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("ALERT_WORKFLOW:wf-1"))
            .andExpect(jsonPath("$[0].safeSummary").exists())
            .andExpect(jsonPath("$[0].diagnosis").doesNotExist());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn -q -Dtest=CollaborationCenterControllerTest test`

Expected: FAIL with 404 because the controller route is absent.

- [ ] **Step 3: Write minimal implementation**

Controller 签名固定为：

```java
@GetMapping("/api/collaboration/work-items")
List<CollaborationCenterDtos.WorkItemResponse> list(
        @RequestParam(required = false) String source,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "50") int limit,
        @RequestHeader(value = "X-Demo-Role", required = false) String role)
```

先执行 `DemoRole.require(role, CUSTOMER_AGENT, ADMIN)`，再用 `Enum.valueOf` 加显式错误转换解析白名单，限制 `limit` 为 1..50。DTO 只复制投影字段，不直接序列化领域对象。

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn -q -Dtest=CollaborationCenterControllerTest test`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/smartpark/web/CollaborationCenterController.java src/main/java/com/example/smartpark/web/CollaborationCenterDtos.java src/test/java/com/example/smartpark/web/CollaborationCenterControllerTest.java
git commit -m "feat: expose collaboration center read API"
```

### Task 3: 添加前端类型、API 客户端和协同中心页面

**Files:**
- Create: `ui/src/types/collaborationCenter.ts`
- Modify: `ui/src/services/workflowApi.ts`
- Create: `ui/src/components/collaboration/CollaborationCenter.vue`
- Create: `ui/src/components/collaboration/collaboration-center.css`
- Test: `ui/src/components/collaboration/CollaborationCenter.spec.ts`

**Interfaces:**
- Consumes: `GET /api/collaboration/work-items` with `X-Demo-Role`。
- Produces: `CollaborationCenter` emits `open-view` with `'workflow' | 'customer'`。

- [ ] **Step 1: Write the failing test**

先在 Vitest 中 mock `listCollaborationWorkItems`，断言管理员能看到列表与计数，筛选会再次请求白名单参数，空列表显示明确空态，点击“打开原场景”发出正确 view。

```ts
it('renders safe work items and opens their existing scene', async () => {
  vi.mocked(listCollaborationWorkItems).mockResolvedValue([alertItem, ticketItem])
  const wrapper = mount(CollaborationCenter, { props: { role: 'ADMIN' } })
  await flushPromises()
  expect(wrapper.text()).toContain('ALERT_WORKFLOW:wf-1')
  await wrapper.get('[data-work-item="CUSTOMER_TICKET:cs-1"] button').trigger('click')
  expect(wrapper.emitted('open-view')).toEqual([['customer']])
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui; npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`

Expected: FAIL because the component and API client do not exist.

- [ ] **Step 3: Write minimal implementation**

新增 `listCollaborationWorkItems(role, filters)`，组件维护 `source`、`status` 和 loading/error 状态，使用现有 Element Plus 控件。状态标签只从前端联合类型映射，未知值显示“无法识别”并不渲染原始字符串；列表项的 `detailPath` 只转换为既有 `workflow`/`customer` 事件。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ui; npm run test:unit -- src/components/collaboration/CollaborationCenter.spec.ts`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ui/src/types/collaborationCenter.ts ui/src/services/workflowApi.ts ui/src/components/collaboration
git commit -m "feat: add collaboration center queue"
```

### Task 4: 接入运营工作台导航与场景跳转

**Files:**
- Modify: `ui/src/types/workbench.ts`
- Modify: `ui/src/components/OperationsWorkbench.vue`
- Modify: `ui/src/App.vue`
- Test: `ui/src/components/OperationsWorkbench.spec.ts`
- Test: `ui/src/App.spec.ts`

**Interfaces:**
- Consumes: `CollaborationCenter` 的 `open-view` 事件。
- Produces: `WorkbenchView = 'collaboration-center'`，并保持现有场景启动请求契约不变。

- [ ] **Step 1: Write the failing test**

增加测试断言：导航在当前角色满足客服工单读取权限时显示“协同中心”；点击后渲染页面；页面发出 `open-view="workflow"` 或 `open-view="customer"` 时切换到对应既有视图。

- [ ] **Step 2: Run test to verify it fails**

Run: `cd ui; npm run test:unit -- src/components/OperationsWorkbench.spec.ts src/App.spec.ts`

Expected: FAIL because `collaboration-center` view and event wiring are absent.

- [ ] **Step 3: Write minimal implementation**

将 `collaboration-center` 加入 `WorkbenchView`，导航项仅在角色为 `CUSTOMER_AGENT` 或 `ADMIN` 时可用；模板挂载 `CollaborationCenter` 并将 `open-view` 映射到 `activeView`。从协同中心跳转时不创建新请求、不触发 API 写操作。

- [ ] **Step 4: Run test to verify it passes**

Run: `cd ui; npm run test:unit -- src/components/OperationsWorkbench.spec.ts src/App.spec.ts`

Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add ui/src/types/workbench.ts ui/src/components/OperationsWorkbench.vue ui/src/App.vue ui/src/components/OperationsWorkbench.spec.ts ui/src/App.spec.ts
git commit -m "feat: wire collaboration center into workbench"
```

### Task 5: 文档、完整验证和本地展示验收

**Files:**
- Modify: `README.md`
- Modify: `docs/architecture.md`
- Modify: `docs/customer-capabilities.md`

- [ ] **Step 1: Update documentation**

在 API 表、架构图说明和能力矩阵中增加协同中心只读接口、角色门禁、字段脱敏和“原场景仍是唯一写入口”的说明。

- [ ] **Step 2: Run backend verification**

Run: `mvn -q test`

Expected: PASS。

- [ ] **Step 3: Run frontend verification**

Run: `cd ui; npm run typecheck; npm run test:unit; npm run build`

Expected: all commands PASS；若出现 Vite chunk size warning，只记录为已有构建提示，不改变功能代码。

- [ ] **Step 4: Run showcase verifier and browser checks**

Run: `powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-showcase.tests.ps1`，并在本地 `http://localhost:5173` 验证管理员进入协同中心、筛选和跳转；使用普通查看者确认页面显示无权限态且不请求受限数据。

- [ ] **Step 5: Commit**

```bash
git add README.md docs/architecture.md docs/customer-capabilities.md
git commit -m "docs: document collaboration center showcase"
```
