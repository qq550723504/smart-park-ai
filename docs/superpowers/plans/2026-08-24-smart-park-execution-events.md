# 智慧园区统一执行事件与轨迹栏实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 建立三项 P1 能力共享的、严格有序且默认脱敏的执行事件协议，并在不破坏原告警工作流 API 的前提下提供统一右侧轨迹栏。

**Architecture:** 新建 application-owned `execution` 能力包，事件 payload 采用封闭的判别联合；通用发布器负责单 run 严格序号、回放和终止。原 `WorkflowEvent` 通过单向适配器投影到新协议，旧 SSE 保持原样。

**Tech Stack:** Java 17 records/sealed interfaces, Reactor Sinks/Flux, Spring MVC SSE, Vue 3, TypeScript discriminated unions, Vitest, Vue Test Utils.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Depends on:** `2026-08-24-spring-ai-alibaba-2-upgrade.md` 完成。

**Global constraints:** 在线真实链路且不做运行时降级；旧 `/api/workflows/**` 契约兼容；事件只承载展示所需安全摘要，不得包含 prompt、原始音频、SQL 凭据、身份证明或未脱敏安防数据；每个 run 的 sequence 必须单调连续；UI 不得伪造工具调用。

## 文件结构与职责

- `src/main/java/com/example/smartpark/execution/model/*`：场景、阶段、状态、事件类型、actor 和封闭 payload DTO。
- `src/main/java/com/example/smartpark/execution/ExecutionEventPublisher.java`：发布、历史、订阅、完成接口。
- `src/main/java/com/example/smartpark/execution/InMemoryExecutionEventPublisher.java`：线程安全单 run 序号与 replay 实现。
- `src/main/java/com/example/smartpark/execution/LegacyWorkflowEventAdapter.java`：旧事件到统一事件的兼容投影。
- `src/main/java/com/example/smartpark/web/ExecutionEventController.java`：`GET /api/executions/{runId}/events` 与历史查询。
- `src/test/java/com/example/smartpark/execution/*Test.java`：协议、不变量、脱敏和并发测试。
- `ui/src/types/execution.ts`：与 Java 一一对应的判别联合。
- `ui/src/services/executionApi.ts`：统一 SSE 订阅。
- `ui/src/composables/useExecutionTrace.ts`：去重、排序、断线和终止状态。
- `ui/src/components/execution/ExecutionTraceRail.vue`：右侧公共轨迹栏。
- `ui/src/components/execution/ExecutionEventCard.vue`：按 payload 类型渲染。

## Task 1：定义封闭协议及不变量

- [ ] 先写 `ExecutionEventTest.java`，覆盖必填字段、正序号、终态、敏感键拒绝和 payload 类型匹配：

```java
@Test
void rejectsSensitiveDisplayFields() {
    assertThatThrownBy(() -> DisplayPayload.toolCall(
            "EnergyQueryTool", Map.of("apiKey", "secret")))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] 运行确认因类型尚不存在而失败：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionEventTest test
```

- [ ] 创建枚举 `ExecutionScenario`、`ExecutionStage`、`ExecutionEventType`、`ExecutionStatus`，场景固定为 `VOICE`、`EXPERT_COLLABORATION`、`OPERATIONS_ANALYSIS`、`ALERT_WORKFLOW`。

- [ ] 创建 `ExecutionEvent` record，字段严格为：

```java
public record ExecutionEvent(
        UUID eventId, UUID runId, long sequence, Instant timestamp,
        ExecutionScenario scenario, String actor, ExecutionStage stage,
        ExecutionEventType eventType, ExecutionStatus status,
        String safeSummary, DisplayPayload displayPayload) {}
```

- [ ] `DisplayPayload` 使用 sealed interface，只允许 `TextPayload`、`ToolCallPayload`、`ExpertHandoffPayload`、`SqlPayload`、`ChartPayload`、`AudioPayload`、`ErrorPayload`；禁止 `Map<String,Object>` 作为顶层 payload。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionEventTest test
git add -- src/main/java/com/example/smartpark/execution/model src/test/java/com/example/smartpark/execution/ExecutionEventTest.java
git commit -m "feat: define safe execution event contract"
```

## Task 2：实现严格序号发布器

- [ ] 先写 `ExecutionEventPublisherTest.java`，覆盖同 run 1..N 连续、不同 run 独立、8 线程并发无重复、订阅者收到历史和后续事件、完成后拒绝发布、未知 run 不被查询隐式创建。

- [ ] 运行确认失败：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionEventPublisherTest test
```

- [ ] 实现发布器；用每 run 锁保护 `sequence + history + sink` 原子提交。`history` 返回不可变副本，`events` 使用 replay，terminal event 发布后完成流。实现 `remove(UUID runId)`，只允许清理终态 run。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionEventPublisherTest test
git add -- src/main/java/com/example/smartpark/execution/ExecutionEventPublisher.java src/main/java/com/example/smartpark/execution/InMemoryExecutionEventPublisher.java src/test/java/com/example/smartpark/execution/ExecutionEventPublisherTest.java
git commit -m "feat: publish ordered execution traces"
```

## Task 3：为旧工作流增加只增不改适配器

- [ ] 写 `LegacyWorkflowEventAdapterTest.java`，逐一映射旧八类事件，断言：`workflowId` 稳定转换为 UUIDv5 runId、sequence 原样保留、`redactedSummary` 成为 `safeSummary`、未知敏感文本仍为 `[REDACTED]`。

- [ ] 实现适配器，禁止修改 `WorkflowEvent` 和旧发布器的公开形状。在现有发布路径中用装饰器同步投影；统一发布失败时旧工作流显式失败，不能静默丢轨迹。

- [ ] 同时验证新旧契约并提交：

```powershell
.\mvnw.cmd -B -Dtest=LegacyWorkflowEventAdapterTest,WorkflowEventPublisherTest,WorkflowEventControllerTest,AlertWorkflowTest test
git add -- src/main/java/com/example/smartpark/execution/LegacyWorkflowEventAdapter.java src/main/java/com/example/smartpark/workflow src/test/java/com/example/smartpark/execution/LegacyWorkflowEventAdapterTest.java
git commit -m "feat: project legacy workflow events into execution trace"
```

## Task 4：开放统一历史与 SSE API

- [ ] 写 `ExecutionEventControllerTest.java`，使用 `@WebMvcTest` 和 `@MockitoBean` 覆盖：run 摘要、具名 SSE 的 id/event/data、未知 run 404、terminal 后完成、JSON 多态字段 `payloadType`。

- [ ] 实现 `ExecutionEventController` 与只读 `ExecutionDtos`；不得开放发布端点。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionEventControllerTest,WorkflowEventControllerTest test
git add -- src/main/java/com/example/smartpark/web/ExecutionEventController.java src/main/java/com/example/smartpark/web/ExecutionDtos.java src/test/java/com/example/smartpark/web/ExecutionEventControllerTest.java
git commit -m "feat: expose unified execution event stream"
```

## Task 5：建立前端类型与 SSE composable

- [ ] 在 `ui/package.json` 增加 `test:unit` 及 Vitest、Vue Test Utils、jsdom；先创建 `useExecutionTrace.spec.ts`，用假的 EventSource 覆盖去重、乱序重排、序号缺口报错、终态关闭、解析失败。

- [ ] 运行确认失败：

```powershell
Push-Location ui
npm.cmd install
npm.cmd run test:unit -- useExecutionTrace
Pop-Location
```

- [ ] 创建 `execution.ts`，`DisplayPayload` 必须是 `payloadType` 判别联合；创建 `executionApi.ts` 和 `useExecutionTrace.ts`，注册固定具名 SSE 事件，不用前端定时器制造过程事件。

- [ ] 验证并提交锁文件：

```powershell
Push-Location ui
npm.cmd run test:unit -- useExecutionTrace
npm.cmd run typecheck
Pop-Location
git add -- ui/package.json ui/package-lock.json ui/src/types/execution.ts ui/src/services/executionApi.ts ui/src/composables/useExecutionTrace.ts ui/src/composables/useExecutionTrace.spec.ts
git commit -m "feat: consume typed execution events in ui"
```

## Task 6：实现统一右侧轨迹栏

- [ ] 先写 `ExecutionEventCard.spec.ts` 和 `ExecutionTraceRail.spec.ts`，覆盖七种 payload、actor/stage/status、错误态、空态、自动滚动关闭与可访问标签。

- [ ] 实现组件；SQL 卡只展示安全 SQL/参数名，不显示连接串；Audio 卡只展示时长/状态，不回放原始输入。

- [ ] 将 `App.vue` 重构为顶层场景导航和共享右侧 rail 容器，保留原 `workflow` 和 `customer` 内容。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git add -- ui/src/App.vue ui/src/components/execution ui/src/styles.css
git commit -m "feat: add shared execution trace rail"
```

## Task 7：跨层回归

- [ ] 执行：

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
Pop-Location
git diff --check
```

预期：旧工作流 UI/API 与统一轨迹均通过；无 skipped 的新单测。

## 完成闸门

- 旧 API 完全兼容且可同时消费统一轨迹。
- 并发事件严格连续，终态流可关闭，未知 run 为 404。
- Java/TypeScript payload 类型一一对应且无任意顶层 Map。
- 右侧轨迹只显示真实后端事件，安全测试证明敏感字段不能通过。
