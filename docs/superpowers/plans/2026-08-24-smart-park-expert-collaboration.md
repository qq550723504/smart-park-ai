# 智慧园区多专家 Agent 协作实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 由 Supervisor 动态选择能耗、设备、安防专家并真正并行分析，展示可验证的职责差异、交接轨迹、证据和综合结论。

**Architecture:** Supervisor `ReactAgent` 只产出结构化调度计划和综合结论，不持有领域工具；`StateGraph.addParallelConditionalEdges` 依据受校验的 selectedDomains 启动对应专家分支。专家只能访问各自的只读工具与知识域，统一返回 `ExpertFinding`。

**Tech Stack:** Spring AI Alibaba Agent Framework/Graph 2.0, ReactAgent, StateGraph, ChatModel, AgentTool, Reactor, Vue 3, Vitest.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Depends on:** 2.0 基线与统一执行事件计划完成。

**Global constraints:** 动态调度；单领域不得调用无关专家；跨领域示例必须调用三专家；真实工具调用才可展示；Supervisor 禁止直接查询领域数据；安防只允许脱敏摘要；专家失败不得被综合成确定结论；总运行上限 40 秒、单专家 15 秒、最多并行 3。

## 文件结构与职责

- `src/main/java/com/example/smartpark/collaboration/model/*`：Domain、SupervisorPlan、ExpertFinding、CollaborationRun、Synthesis。
- `src/main/java/com/example/smartpark/collaboration/expert/*`：三个专家构建器、system instructions、finding validator。
- `src/main/java/com/example/smartpark/collaboration/supervisor/*`：计划 Agent、计划校验、综合 Agent、综合校验。
- `src/main/java/com/example/smartpark/collaboration/ExpertCollaborationGraph.java`：动态并行图与 merge。
- `src/main/java/com/example/smartpark/collaboration/ExpertCollaborationService.java`：run 生命周期、超时和事件。
- `src/main/java/com/example/smartpark/web/ExpertCollaborationController.java`：runs/status。
- `src/test/java/com/example/smartpark/architecture/ExpertToolOwnershipTest.java`：静态职责边界。
- `ui/src/components/collaboration/*`：问题、专家卡、交接、综合结论。

## Task 1：定义协作契约

- [ ] 先写 `ExpertFindingTest.java` 和 `SupervisorPlanTest.java`，覆盖三个 domain、状态 `SUPPORTED|INSUFFICIENT_EVIDENCE|FAILED`、非空证据引用、0..1 confidence、失败不得携带确定性结论、selectedDomains 去重且最多 3。

- [ ] 实现 records：

```java
public record ExpertFinding(
        ExpertDomain domain, FindingStatus status, String conclusion,
        List<String> evidenceRefs, double confidence, List<String> nextChecks) {}
```

```java
public record SupervisorPlan(
        String normalizedQuestion, Set<ExpertDomain> selectedDomains,
        Map<ExpertDomain, String> assignments, String selectionReason) {}
```

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExpertFindingTest,SupervisorPlanTest test
git add -- src/main/java/com/example/smartpark/collaboration/model src/test/java/com/example/smartpark/collaboration/model
git commit -m "feat: define expert collaboration contracts"
```

## Task 2：从代码结构锁定真实职责差异

- [ ] 先写 `ExpertToolOwnershipTest.java`，读取三个 expert 配置的工具集合并断言：能耗仅 `EnergyQueryTool + energy knowledge`；设备仅 `DeviceQueryTool + AlertQueryTool + read-only WorkOrderTool + device knowledge`；安防仅 `SecurityQueryTool + security knowledge`；Supervisor 工具集合为空。

- [ ] 创建 `EnergyExpertConfiguration`、`DeviceExpertConfiguration`、`SecurityExpertConfiguration` 和三个独立 system prompt 资源。工具通过构造器显式注入，禁止共享“全部工具”列表再靠 prompt 约束。

- [ ] 为 `ParkKnowledgeTool` 增加 domain-scoped facade，确保专家只能检索其知识域；安防 facade 二次验证输出是 `PublicMetadata`/脱敏摘要。

- [ ] 验证现有只读工具测试与新边界：

```powershell
.\mvnw.cmd -B -Dtest=ExpertToolOwnershipTest,ParkToolsTest,SecurityQueryToolTest,SecurityBoundaryTest test
```

- [ ] 提交：

```powershell
git add -- src/main/java/com/example/smartpark/collaboration/expert src/main/resources/prompts/experts src/test/java/com/example/smartpark/architecture/ExpertToolOwnershipTest.java
git commit -m "feat: isolate expert tool ownership"
```

## Task 3：实现结构化 Supervisor 计划和校验

- [ ] 先写 `SupervisorPlannerTest.java`，用固定模型响应覆盖：能耗问题仅 ENERGY；设备离线仅 DEVICE；安防仅 SECURITY；“A2 夜间能耗升高且门禁告警、冷机离线”选择三者；非法领域、空选择、任务遗漏被拒绝。

- [ ] 使用 `ReactAgent` 构建无工具 planner，输出严格 JSON schema `SupervisorPlan`。`SupervisorPlanValidator` 根据领域词、实体和任务覆盖做确定性校验；不得把模型自由文本直接作为分支键。

- [ ] 对可回答问题 selectedDomains 为空时显式失败；对真正歧义问题返回澄清状态，不默认全派发。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=SupervisorPlannerTest,SupervisorPlanValidatorTest test
git add -- src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorPlanner.java src/main/java/com/example/smartpark/collaboration/supervisor/SupervisorPlanValidator.java src/test/java/com/example/smartpark/collaboration/supervisor
git commit -m "feat: plan dynamic expert dispatch"
```

## Task 4：实现专家 Agent 与 finding 校验

- [ ] 为每个专家写聚焦测试，固定工具结果和模型输出，断言工具调用名称、证据引用、知识域与 `ExpertFinding.domain`；尝试调用其他专家工具必须失败。

- [ ] 构建三个 `ReactAgent`，统一输出 `ExpertFinding` JSON schema。`ExpertFindingValidator` 验证 evidenceRef 来自本次真实工具/知识调用，不存在的引用将状态降为 `INSUFFICIENT_EVIDENCE`，不能补造证据。

- [ ] 每次实际工具调用前后发布 `TOOL_CALL_STARTED/COMPLETED`；事件 safeSummary 只含工具名和安全参数摘要。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=EnergyExpertTest,DeviceExpertTest,SecurityExpertTest,ExpertFindingValidatorTest test
git add -- src/main/java/com/example/smartpark/collaboration/expert src/test/java/com/example/smartpark/collaboration/expert
git commit -m "feat: add evidence-bound park experts"
```

## Task 5：实现真正动态并行 Graph

- [ ] 先写 `ExpertCollaborationGraphTest.java`，使用三个可控 latch expert 证明：

  - 单领域只进入一个分支；
  - 三领域三个分支开始时间重叠，而非顺序执行；
  - selectedDomains 不含的 expert 调用计数为 0；
  - 一个专家失败时其他 finding 保留；
  - merge 输出按 ENERGY/DEVICE/SECURITY 稳定排序。

- [ ] 图节点固定为 `supervisorPlan -> selected expert branches -> validateFindings -> mergeFindings -> supervisorSynthesis`；通过 `addParallelConditionalEdges` 创建受控分支，不使用内建顺序 Supervisor 伪装并行。

- [ ] 分支开始/结束发布 handoff 事件，`actor` 使用 `Supervisor|EnergyExpert|DeviceExpert|SecurityExpert`。

- [ ] 验证 wall-clock 并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExpertCollaborationGraphTest test
git add -- src/main/java/com/example/smartpark/collaboration/ExpertCollaborationGraph.java src/test/java/com/example/smartpark/collaboration/ExpertCollaborationGraphTest.java
git commit -m "feat: dispatch selected experts in parallel"
```

## Task 6：受证据约束的 Supervisor 综合

- [ ] 写 `SupervisorSynthesisTest.java`，覆盖全部支持、部分证据不足、一个失败、意见冲突。断言综合只引用 finding 中存在的 evidenceRefs，并清楚标记不确定性。

- [ ] 实现无领域工具的 `SupervisorSynthesizer`，输入只有原问题、计划和已校验 findings。`SynthesisValidator` 拒绝新增数字、设备 ID、告警 ID 或证据引用。

- [ ] 如果无任何 SUPPORTED finding，终态是 `INSUFFICIENT_EVIDENCE`，不能给出肯定根因。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=SupervisorSynthesisTest,SynthesisValidatorTest test
git add -- src/main/java/com/example/smartpark/collaboration/supervisor src/test/java/com/example/smartpark/collaboration/supervisor
git commit -m "feat: synthesize evidence-bound expert findings"
```

## Task 7：run 服务、API 和超时

- [ ] 写 service/controller 测试覆盖：`POST /api/expert-collaboration/runs`、GET run、统一 SSE、澄清 409、未知 404、单专家 15 秒超时、总运行 40 秒、并发隔离、terminal 幂等。

- [ ] 实现 `ExpertCollaborationService`、store、controller 和 DTO。使用有界 executor，最大并行 3；超时取消对应 future，发布失败 finding 和 terminal 状态。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExpertCollaborationServiceTest,ExpertCollaborationControllerTest test
git add -- src/main/java/com/example/smartpark/collaboration/ExpertCollaborationService.java src/main/java/com/example/smartpark/collaboration/CollaborationRunStore.java src/main/java/com/example/smartpark/web/ExpertCollaborationController.java src/main/java/com/example/smartpark/web/ExpertCollaborationDtos.java src/test/java/com/example/smartpark/collaboration/ExpertCollaborationServiceTest.java src/test/java/com/example/smartpark/web/ExpertCollaborationControllerTest.java
git commit -m "feat: expose expert collaboration runs"
```

## Task 8：协作 UI 与交接轨迹

- [ ] 写 `useExpertCollaboration.spec.ts` 和组件测试，覆盖动态专家卡、并行进行态、未选择专家不出现“正在分析”、失败/证据不足、Supervisor 综合、共享轨迹联动。

- [ ] 实现页面：顶部问题与示例；中部 Supervisor 计划；三专家卡只对被选领域实例化；底部综合结论和证据；右侧复用统一轨迹。

- [ ] 添加单领域和三领域预置问题，但点击后仍请求真实后端，不注入固定结果。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd run test:unit -- ExpertCollaboration
npm.cmd run build
Pop-Location
git add -- ui/src/types/collaboration.ts ui/src/services/collaborationApi.ts ui/src/composables/useExpertCollaboration.ts ui/src/components/collaboration ui/src/App.vue ui/src/styles.css
git commit -m "feat: visualize expert collaboration handoffs"
```

## Task 9：配置与回归

- [ ] 在 `application.yml` 增加 `expert-timeout=15s`、`run-timeout=40s`、`max-parallel=3`，写绑定与非法值测试。

- [ ] 运行后端全测、前端单测/构建和 `git diff --check`；分别执行单领域与三领域固定模型集成测试，记录实际调用集合。

## 完成闸门

- 三专家的工具、知识域和 prompt 是代码级隔离，不只是 UI 标签不同。
- 动态路由测试证明无关专家调用数为 0；跨域测试证明三分支时间重叠。
- Supervisor 不拥有领域工具，综合不能越过 findings 造证据。
- 正常链路 20–40 秒，失败/超时显式展示且不伪造成功。
