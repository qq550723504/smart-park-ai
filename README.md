# 智慧园区告警处置工作流

这是一个面向学习的 Spring AI Alibaba 智慧园区项目：接收园区告警，采集 Mock 园区上下文，生成结构化诊断，执行风险门禁，在需要时暂停等待人工审批，创建内存中的 Mock 工单，并通过 SSE 推送工作流事件。

> 安全边界：本项目中的所有园区适配器都是 `MockParkSystem` 适配器，只读取种子数据，并把工单写入内存。它不会检查、切换、重启、隔离或控制任何真实设备。不要把本示例当作生产控制系统使用。

## 环境要求

- Java 17 或更高版本（`pom.xml` 目标版本为 Java 17）
- 只有在主动运行真实聊天模型时，才需要可访问互联网的 DashScope 账号和 API Key
- 不要求系统安装 Maven，使用仓库中的 Maven Wrapper 即可

默认配置从进程环境变量 `AI_DASHSCOPE_API_KEY` 读取密钥。不要把密钥写入源码、本文档、`.env` 文件、命令行参数或 Shell 历史记录。

## 构建与测试

### Windows PowerShell

```powershell
java -version
.\mvnw.cmd --version
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests
```

下面的方式会在当前 PowerShell 进程中读取密钥，不会将密钥直接写入命令历史：

```powershell
$secureDashScopeKey = Read-Host 'DashScope API key' -AsSecureString
$env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureDashScopeKey).Password
.\mvnw.cmd spring-boot:run
```

使用 `Ctrl+C` 停止应用后，清理当前进程环境变量：

```powershell
Remove-Item Env:AI_DASHSCOPE_API_KEY
Remove-Variable secureDashScopeKey
```

### macOS/Linux

```bash
java -version
./mvnw --version
./mvnw test
./mvnw package -DskipTests
```

在 Bash 兼容 Shell 中读取密钥时不回显，也不写入 Shell 历史：

```bash
read -rsp 'DashScope API key: ' AI_DASHSCOPE_API_KEY && echo
export AI_DASHSCOPE_API_KEY
./mvnw spring-boot:run
```

使用 `Ctrl+C` 停止应用后执行：

```bash
unset AI_DASHSCOPE_API_KEY
```

启动工作流会调用配置的 `qwen-plus` 聊天模型，因此需要外部模型服务和网络。单元测试与集成测试使用测试替身或禁用 DashScope，不需要真实密钥，也不会调用真实模型。

## Mock 告警数据

应用每次启动时都会重置 `MockParkSystem` 的内存数据，并提供以下两个工作流入口：

| 告警 ID | 设备 | 种子风险提示 | 练习内容 |
| --- | --- | --- | --- |
| `ALT-TEMP-001` | `DEV-HVAC-001` | `LOW` | 温度告警分诊与 HVAC 知识检索。诊断风险较高、置信度较低或证据不足时仍可能进入审批。 |
| `ALT-POWER-001` | `DEV-POWER-001` | `HIGH` | 高风险电力告警诊断。执行到风险门禁后必须暂停等待审批。 |

工作流状态、事件、审批和工单都保存在内存中，应用重启后会丢失。

## REST 与 SSE 示例

当前 API 一共提供四个端点。项目没有认证机制，只适合在可信的本地开发环境中运行。下面使用 `curl`；在 Windows PowerShell 中请使用 `curl.exe`。

### 1. 启动工作流

```bash
curl -X POST "http://localhost:8080/api/alerts/ALT-TEMP-001/workflows"
```

也可以使用 `ALT-POWER-001` 练习必经人工审批的高风险流程。保存响应中的 `workflowId`，供后续请求使用。

### 2. 查询工作流状态

```bash
curl "http://localhost:8080/api/workflows/replace-with-workflow-id"
```

可能的状态包括：`RUNNING`、`WAITING_APPROVAL`、`COMPLETED`、`REJECTED`、`FAILED` 和 `WORK_ORDER_FAILED`。公开 DTO 会有意对诊断、操作人、工单和错误详情进行安全摘要处理。

### 3. 审批或拒绝暂停的工作流

只有当工作流处于 `WAITING_APPROVAL` 状态时，审批才有效。必填的 `idempotencyKey` 用于保证相同请求重试时返回已有结果，并阻止同一个 key 被用于不同审批内容。

```bash
curl -X POST "http://localhost:8080/api/workflows/replace-with-workflow-id/approval" -H "Content-Type: application/json" --data '{"decision":"APPROVE","reviewer":"operator-1","comment":"safe to dispatch Mock work order","idempotencyKey":"approval-request-001"}'
```

请求 JSON 格式：

```json
{
  "decision": "APPROVE",
  "reviewer": "operator-1",
  "comment": "safe to dispatch Mock work order",
  "idempotencyKey": "approval-request-001"
}
```

`decision` 取值为 `APPROVE` 或 `REJECT`。Mock 审批可能创建内存中的 Mock 工单，但不会授权或控制真实设备。

### 4. 通过 SSE 订阅工作流事件

```bash
curl -N -H "Accept: text/event-stream" "http://localhost:8080/api/workflows/replace-with-workflow-id/events"
```

内存事件发布器会重放指定工作流的事件，并在收到 `COMPLETED` 或 `FAILED` 事件后结束流。SSE 使用脱敏后的公开 DTO，不会暴露内部 Graph 状态。

## 学习路线

| 学习主题 | 代码位置 | 本项目展示的内容 |
| --- | --- | --- |
| `ChatModel` / `ChatClient` | `com.example.smartpark.agent` | `AlertTriageAgent` 直接调用 `ChatModel`；`AlertDiagnosisAgent` 基于注入的模型构建 `ChatClient`。 |
| Tool Calling | `com.example.smartpark.tool` 与 `AlertDiagnosisAgent` | 将 `@Tool` 方法转换为回调并传给诊断调用。诊断只接收经过审计的只读回调，工单创建仍由确定性的工作流动作负责。 |
| 结构化输出 | `AlertTriageAgent`、`AlertDiagnosisAgent`、`PromptCatalog` | Prompt 要求 JSON；Agent 使用 Jackson 解析，拒绝缺失字段和多余字段，校验枚举与范围，再构造类型安全的记录和领域对象。示例没有把校验隐藏在自动输出转换器后面。 |
| Graph 与状态 | `com.example.smartpark.workflow.AlertWorkflow`、`AlertWorkflowNodes`、`AlertWorkflowState` | 将 Spring AI Alibaba `StateGraph` 编译为有序、条件化的节点，并使用明确的状态键和 reducer。 |
| 中断与恢复 | `AlertWorkflowNodes.HumanApprovalAction`、`AlertWorkflow.approve` | 高风险或不确定流程产生中断元数据，保存内存执行状态，接收操作人反馈，并恢复同一个 Graph 线程。 |
| 风险门禁 | `AlertWorkflowNodes.RiskGate` | 高风险、置信度低于阈值或缺少知识证据时进入人工审批，否则可以直接创建 Mock 工单。 |
| 幂等性 | `ApprovalDecision`、`AlertWorkflow`、`WorkflowExecutionStore`、`MockParkSystem` | 审批重试由 `idempotencyKey` 标识；工作流启动和 Mock 工单写入也在内存中保持工作流级别的身份。 |
| SSE | `WorkflowEventPublisher`、`WorkflowEventController`、`WebDtos.WorkflowEventDto` | 使用 Reactor `Flux` 将可重放的工作流事件转换为脱敏的 Spring `ServerSentEvent`，并在终态事件后关闭。 |

## 暂后练习

这是第一个可运行的垂直切片，尚未达到生产级别。以下内容是后续练习，并非当前已经提供的功能：

- **Embedding/RAG：** `MockParkSystem.search` 只是内存中的确定性关键词匹配。当前没有 Embedding 模型、向量数据库、数据导入流程或 RAG 链路。
- **PostgreSQL checkpoint：** Graph 执行、事件、审批、幂等记录和工单都保存在进程内存中。当前没有 PostgreSQL checkpoint 或重启恢复能力。
- **认证与授权：** 四个 HTTP 端点没有身份、角色、租户或审批策略校验。
- **真实适配器：** `AlertPort`、`DevicePort`、`KnowledgePort` 和 `WorkOrderPort` 是扩展边界，但当前只接入了 `MockParkSystem`。真实园区 API、持久化工单和设备控制适配器尚未实现。
