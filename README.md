# 智慧园区告警、能耗、安防与客服学习项目

这是一个面向学习的 Spring AI Alibaba 智慧园区项目，当前提供告警处置、能耗查询、安防事件查询、园区客服、知识管理以及运营审计能力。运营工作流接收园区告警，执行场景分析、结构化诊断、风险门禁、人工审批、Mock 工单和 SSE 事件；客服流程提供停车、访客通行、公共区域能耗咨询以及设施报修转人工。

> 安全边界：当前 Mock 适配器只读取种子数据，并把工单写入内存；它不会检查、切换、重启、隔离或控制任何真实设备。安防场景只有脱敏 Mock 摘要、只读查询工具和通用告警工作流入口，没有真实摄像头、门禁或人员系统接入。`SecurityEvent.evidenceSummary` 只能保存脱敏摘要，不得保存原始视频、图片、人脸特征或身份证等人员原始记录。不要把本示例当作生产控制系统使用。

## 环境要求

- Java 17 或更高版本（`pom.xml` 目标版本为 Java 17）
- 只有在主动运行真实聊天模型时，才需要可访问互联网的 DashScope 账号和 API Key
- 不要求系统安装 Maven，使用仓库中的 Maven Wrapper 即可

默认配置从进程环境变量 `AI_DASHSCOPE_API_KEY` 读取密钥。不要把密钥写入源码、本文档、`.env` 文件、命令行参数或 Shell 历史记录。
DashScope 自动配置默认开启，也可以通过 `SPRING_AI_DASHSCOPE_ENABLED=false` 显式关闭。默认 URL 是 `https://dashscope.aliyuncs.com`，由 `SPRING_AI_DASHSCOPE_BASE_URL` 覆盖；该 URL 只用于模型客户端，不会启用安防接口，也不会改变 Mock 数据源。需要使用兼容网关时，只修改当前进程的 URL 环境变量，例如：

```powershell
$env:SPRING_AI_DASHSCOPE_BASE_URL = 'https://your-compatible-gateway.example.com'
```

项目配置文件中的 DashScope URL 占位符为 `${SPRING_AI_DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com}`，不要把访问密钥或内部网关地址提交到仓库。

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

### 可选的真实 DashScope 连通性验证

默认测试不会访问网络。只有同时设置当前进程的 `AI_DASHSCOPE_API_KEY`，并显式传入 `run.dashscope.smoke=true`，才会执行一次真实 `qwen-plus` 调用：

```powershell
$secureDashScopeKey = Read-Host 'DashScope API key' -AsSecureString
$env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureDashScopeKey).Password
.\mvnw.cmd -Drun.dashscope.smoke=true -Dtest=DashScopeSmokeTest test
Remove-Item Env:AI_DASHSCOPE_API_KEY
Remove-Variable secureDashScopeKey
```

验证内容只检查模型返回非空，不会输出 API Key 或完整模型响应。没有 Key 时，该测试会安全跳过。

## 当前能力与 Mock 数据

应用每次启动时都会重置共享 Mock 内存数据，并提供以下四个告警工作流入口：

| 告警 ID | 设备 | 种子风险提示 | 练习内容 |
| --- | --- | --- | --- |
| `ALT-TEMP-001` | `DEV-HVAC-001` | `LOW` | 温度告警分诊与 HVAC 知识检索。诊断风险较高、置信度较低或证据不足时仍可能进入审批。 |
| `ALT-POWER-001` | `DEV-POWER-001` | `HIGH` | 高风险电力告警诊断。执行到风险门禁后必须暂停等待审批。 |
| `ALT-ENERGY-001` | `DEV-ENERGY-001` | `HIGH` | 建筑能耗高于基线的异常诊断。Agent 可以通过只读能耗工具查询当前值、基线和峰值功率；由于当前种子风险为 `HIGH`，执行到风险门禁后必须等待审批。 |
| `ALT-ACCESS-001` | `DEV-ACCESS-001` | `HIGH` | 非开放时段连续门禁拒绝事件。Agent 只能通过安防工具查询 `REDACTED:` 脱敏摘要，不会取得人员身份或原始媒体；流程必须等待人工审批。 |

工作流状态、事件、审批和工单都保存在内存中，应用重启后会丢失。

当前能力边界如下：

- **告警（alert）：** `AlertPort`、告警查询工具和通用告警工作流负责告警读取、诊断、风险门禁、人工审批与 Mock 工单。
- **能耗（energy）：** `EnergyPort`、`EnergyReading` 和 `EnergyQueryTool` 负责只读能耗查询；通用 Graph 会将 `ENERGY` 告警路由到 `energyAnalysis` 节点，生成当前值、基线、偏差比例和峰值需求分析，再进入知识检索和诊断。
- **安防（security）：** `SecurityEvent`、`SecurityPort`、`MockSecurityAdapter` 和 `SecurityQueryTool` 提供脱敏 Mock 事件的只读查询。通用 Graph 会将 `ACCESS` 告警路由到 `securityReview` 节点，校验事件引用和 `REDACTED:` 摘要后再进入知识检索、诊断和强制人工审批。
- **客服（customer service）：** `CustomerServiceWorkflow` 使用确定性的 Mock 意图识别、关键词检索和安全答复模板处理停车、访客、能耗和报修问题，当前客服答复不调用 DashScope。自动回答仅在有知识支持时返回 `reason: SUPPORTED`、`needsHuman: false` 和至少一个不重复的知识文档 `citationId`；检索结果还必须达到 `smartpark.customer.minimum-knowledge-score`（默认 `0.70`，可由 `SMARTPARK_CUSTOMER_MINIMUM_KNOWLEDGE_SCORE` 覆盖），低于阈值按知识不足转人工。知识不足、策略限制或检索异常会返回固定安全答复并创建 `WAITING_AGENT` 工单，人工转接回答可以不带引用。工单只保存通用安全摘要，不复制用户问题，检索轨迹也只保存查询标识、文档 ID 和时间，不保存正文或异常消息。会话与工单分别通过 `port.customer.CustomerSessionStore` 和 `port.customer.CustomerTicketPort` 访问，默认实现为 `adapter.mock.InMemoryCustomerSessionStore` 和 `adapter.mock.InMemoryCustomerTicketAdapter`。工单端口是当前工单状态的唯一来源；会话过期或因容量被淘汰时，对应工单会一并删除。`Idempotency-Key` 按 `handle/reply` 操作及 reply 目标会话隔离，重试返回请求当时的稳定结果。生产接入仍需要真实 RAG/大模型适配器、内容安全、持久化会话/工单存储和人工坐席系统。

`evidenceSummary` 的格式校验只是边界层的轻量输入约束，不等于认证、授权或业务脱敏。后续适配器仍必须负责真实身份认证、权限判断、租户/业务策略和原始数据脱敏，不能因为通过该格式校验就接入摄像头、门禁或人员原始数据。

当前安防适配器仅用于本地演示。接入真实系统时，必须在 `SecurityPort` 适配器前增加身份认证、细粒度授权、租户隔离、审计和专用脱敏服务，且不能把摄像头、门禁或人员原始数据接入通用告警模型。

## REST 与 SSE 示例

当前 API 按告警工作流、客服、知识管理、运营与审计、演示故障注入分组。项目没有认证机制，只适合在可信的本地开发环境中运行。下面使用 `curl`；在 Windows PowerShell 中请使用 `curl.exe`。

### 告警工作流

```bash
curl -X POST "http://localhost:8080/api/alerts/ALT-TEMP-001/workflows"
```

也可以使用 `ALT-POWER-001`、`ALT-ENERGY-001` 或 `ALT-ACCESS-001` 练习必经人工审批的高风险流程。保存响应中的 `workflowId`，供后续请求使用。

#### 查询工作流状态

```bash
curl "http://localhost:8080/api/workflows/replace-with-workflow-id"
```

可能的状态包括：`RUNNING`、`WAITING_APPROVAL`、`COMPLETED`、`REJECTED`、`FAILED` 和 `WORK_ORDER_FAILED`。公开 DTO 会有意对诊断、操作人、工单和错误详情进行安全摘要处理。

#### 审批或拒绝暂停的工作流

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

#### 通过 SSE 订阅工作流事件

```bash
curl -N -H "Accept: text/event-stream" "http://localhost:8080/api/workflows/replace-with-workflow-id/events"
```

内存事件发布器会重放指定工作流的事件，并在收到 `COMPLETED` 或 `FAILED` 事件后结束流。SSE 使用脱敏后的公开 DTO，不会暴露内部 Graph 状态。

### 客服

```bash
curl -X POST "http://localhost:8080/api/customer-service/sessions" \
  -H "Content-Type: application/json" \
  --data '{"question":"访客停车怎么收费？"}'
```

设施报修示例会返回 `needsHuman: true` 和内存客服工单：

```bash
curl -X POST "http://localhost:8080/api/customer-service/sessions" \
  -H "Content-Type: application/json" \
  --data '{"question":"A1 洗手间漏水，需要报修"}'
```

可通过 `GET /api/customer-service/sessions/{sessionId}` 查询本次会话结果，并通过 `POST /api/customer-service/sessions/{sessionId}/messages` 在同一会话中继续提问。无法识别的追问会继承上一轮意图；会话转人工后停止自动回复。`GET /api/customer-service/sessions/{sessionId}/conversation` 返回消息历史和安全检索轨迹，轨迹只包含检索词和 Mock 文档 ID。客服坐席或管理员可通过 `GET /api/customer-service/tickets` 查看人工工单，并通过 `PATCH /api/customer-service/tickets/{ticketId}` 推进 `WAITING_AGENT`、`ASSIGNED`、`IN_PROGRESS`、`RESOLVED`、`CLOSED` 等状态。知识检索发生异常时，首次咨询和会话追问均返回 HTTP 200 的固定人工转接答复，而不是暴露异常或返回 500。当前没有身份认证，请勿输入身份证、手机号等个人敏感信息。

### 知识管理、运营与审计、演示故障注入

前端可切换查看者、操作员、审批人、客服坐席和管理员角色。角色通过 `X-Demo-Role` 请求头演示接口授权边界，它不是生产认证方案。工作流响应会返回风险门禁原因；`GET /api/workflows/{workflowId}/observability` 汇总安全事件、工具调用和失败节点；管理员可以通过 `POST /api/demo/faults` 注入一次性的知识库检索故障，演示失败路径。`GET /api/operations/metrics` 返回工作流、客服会话、人工工单、知识文档、反馈和审计记录数量；`GET /api/operations/capabilities` 只返回 `knowledgeMode`、`customerAnswerMode` 和 `vectorStore` 三项安全运行模式（当前为 `mock`、`mock`、`none`，表示没有向量存储），不返回 API Key、Base URL、Prompt、模型原始响应或知识正文。管理员可通过 `GET /api/audit` 查看只包含角色、动作、资源 ID、结果和时间的安全审计记录。

管理员可通过 `GET /api/knowledge` 查看知识文档元数据，通过 `POST /api/knowledge` 新增文档，并通过 `PATCH /api/knowledge/{documentId}/active` 启用或停用文档。当前 Mock 实现会立即更新进程内关键词索引：新增文档可被客服检索命中，停用后不再命中；这不是 Embedding 或向量 RAG。公开响应与审计记录均不返回知识正文；知识不足时进入审批或转人工。客服坐席、审批人或管理员可通过 `POST /api/feedback` 提交枚举化反馈，管理员可通过 `GET /api/feedback` 查看反馈记录。当前不接受自由文本，避免反馈接口成为敏感信息旁路。

## 学习路线

| 学习主题 | 代码位置 | 本项目展示的内容 |
| --- | --- | --- |
| `ChatModel` / `ChatClient` | `com.example.smartpark.agent` | `AlertTriageAgent` 直接调用 `ChatModel`；`AlertDiagnosisAgent` 基于注入的模型构建 `ChatClient`。 |
| Tool Calling | `com.example.smartpark.tool` 与 `AlertDiagnosisAgent` | 将 `@Tool` 方法转换为回调并传给诊断调用。诊断只接收经过审计的只读回调，工单创建仍由确定性的工作流动作负责。 |
| 能耗场景 | `EnergyReading`、`EnergyPort`、`EnergyQueryTool`、`energyAnalysis` | Graph 根据告警类型进入能耗专属节点，以当前值、基线、偏差和峰值需求作为诊断证据，再复用公共风险门禁和工单处理。 |
| 安防场景 | `SecurityEvent`、`SecurityPort`、`SecurityQueryTool`、`securityReview` | Graph 根据告警类型进入安防专属节点，只向诊断提供脱敏事件摘要，再复用公共风险门禁和人工审批；明确禁止原始媒体、身份数据和控制能力。 |
| 结构化输出 | `AlertTriageAgent`、`AlertDiagnosisAgent`、`PromptCatalog` | Prompt 要求 JSON；Agent 使用 Jackson 解析，拒绝缺失字段和多余字段，校验枚举与范围，再构造类型安全的记录和领域对象。示例没有把校验隐藏在自动输出转换器后面。 |
| Graph 与状态 | `com.example.smartpark.workflow.AlertWorkflow`、`AlertWorkflowNodes`、`AlertWorkflowState` | 将 Spring AI Alibaba `StateGraph` 编译为有序、条件化的节点，并使用明确的状态键和 reducer。 |
| 中断与恢复 | `AlertWorkflowNodes.HumanApprovalAction`、`AlertWorkflow.approve` | 高风险或不确定流程产生中断元数据，保存内存执行状态，接收操作人反馈，并恢复同一个 Graph 线程。 |
| 风险门禁 | `AlertWorkflowNodes.RiskGate` | 高风险、置信度低于阈值或缺少知识证据时进入人工审批，否则可以直接创建 Mock 工单。 |
| 幂等性 | `ApprovalDecision`、`AlertWorkflow`、`WorkflowExecutionStore`、Mock 适配器 | 审批重试由 `idempotencyKey` 标识；工作流启动和 Mock 工单写入也在内存中保持工作流级别的身份。 |
| SSE | `WorkflowEventPublisher`、`WorkflowEventController`、`WebDtos.WorkflowEventDto` | 使用 Reactor `Flux` 将可重放的工作流事件转换为脱敏的 Spring `ServerSentEvent`，并在终态事件后关闭。 |

## 暂后练习

这是第一个可运行的垂直切片，尚未达到生产级别。以下内容是后续练习，并非当前已经提供的功能：

- **Embedding/RAG：** Mock 知识适配器只是内存中的确定性关键词匹配。当前没有 Embedding 模型、向量数据库、数据导入流程或 RAG 链路。
- **PostgreSQL checkpoint：** Graph 执行、事件、审批、幂等记录和工单都保存在进程内存中。当前没有 PostgreSQL checkpoint 或重启恢复能力。
- **认证与授权：** 当前演示 HTTP 接口没有生产级身份认证、租户隔离或授权策略；`X-Demo-Role` 只用于本地演示角色边界。
- **真实适配器：** `AlertPort`、`DevicePort`、`EnergyPort`、`SecurityPort`、`KnowledgePort` 和 `WorkOrderPort` 是扩展边界，当前仅接入内存 Mock 适配器。真实园区 API、智能电表、安防系统、持久化工单和设备控制适配器尚未实现。
- **生产安防接入：** 当前 `SecurityPort` 只读取固定脱敏种子数据；尚未实现摄像头、门禁、人员系统、认证授权、租户隔离、专用脱敏服务或安防数据持久化。
