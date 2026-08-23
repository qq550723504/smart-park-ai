# 智慧园区告警工作流架构

## 1. 文档范围

本文档描述当前项目 `springaialibaba` 的运行时架构、代码分层、告警处理流程、数据边界和扩展方式。文档以 `src/main/java/com/example/smartpark` 的当前实现为准，示例数据来自 Mock 适配器。

项目当前定位是一个可学习、可替换外部系统的智慧园区告警与能耗异常处理样例，重点演示以下能力：

- 使用 Spring Boot 承载应用和 HTTP 接口
- 使用 Spring AI DashScope ChatModel 完成告警分诊和诊断
- 使用 Alibaba Cloud AI Graph 编排可恢复、可暂停的工作流
- 通过端口接口隔离告警、设备、能耗、知识库和工单系统
- 通过人工审批控制高风险或证据不足的自动化动作
- 通过 REST 查询状态，通过 SSE 订阅工作流事件
- 在对外响应和事件流中隐藏敏感诊断内容

## 2. 总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│                         Web API 层                          │
│ AlertWorkflowController / ApprovalController                │
│ WorkflowEventController / ApiExceptionHandler               │
└───────────────┬───────────────────────────────┬─────────────┘
                │ REST 状态、审批                │ SSE 事件
                ▼                               ▼
┌─────────────────────────────────────────────────────────────┐
│                     工作流编排层                            │
│ AlertWorkflow                                                │
│  ├─ StateGraph / CompiledGraph                               │
│  ├─ AlertWorkflowNodes                                       │
│  ├─ AlertWorkflowState                                       │
│  ├─ WorkflowExecutionStore                                   │
│  └─ WorkflowEventPublisher                                   │
└───────────────┬─────────────────────────────────────────────┘
                │ 调用领域服务、Agent 和端口
                ▼
┌──────────────────────┐  ┌───────────────────────────────────┐
│ Agent 与工具层        │  │ 领域模型层                         │
│ AlertTriageAgent      │  │ Alert / Diagnosis / Device         │
│ AlertDiagnosisAgent   │  │ KnowledgeDocument / WorkOrder      │
│ PromptCatalog         │  │ EnergyReading / ParkContext        │
│ AlertQueryTool        │  │ RiskLevel / WorkflowStatus         │
│ DeviceQueryTool       │  │ ApprovalDecision 等                │
│ EnergyQueryTool 等    │  └───────────────────────────────────┘
└───────────────┬──────┘
                │ 只通过 Port 读取或写入
                ▼
┌─────────────────────────────────────────────────────────────┐
│                         端口层                               │
│ AlertPort / DevicePort / EnergyPort / KnowledgePort          │
│ WorkOrderPort / SecurityPort                                 │
└───────────────┬─────────────────────────────────────────────┘
                │ 当前实现
                ▼
┌─────────────────────────────────────────────────────────────┐
│                      Mock 适配器层                           │
│ MockAlertAdapter / MockDeviceAdapter / MockEnergyAdapter     │
│ MockKnowledgeAdapter / MockWorkOrderAdapter                  │
│ MockParkDataStore / MockParkConfiguration                     │
└─────────────────────────────────────────────────────────────┘
```

### 2.1 启动与 Bean 装配

应用入口是 `SmartParkApplication`。Spring Boot 扫描组件并装配以下核心 Bean：

- `MockParkConfiguration`：在 `spring.ai.dashscope.enabled=true` 或未配置时提供 Mock 园区适配器
- Agent：`AlertTriageAgent`、`AlertDiagnosisAgent`
- 工具：告警、设备、能耗、知识库和工单查询工具
- `AlertWorkflow`：构建并编译状态图
- Web Controller：暴露工作流启动、状态查询、审批和 SSE 事件接口

Mock 适配器当前用于替代真实园区系统。替换为生产系统时，应实现对应 `Port`，而不是修改工作流节点和领域模型。

## 3. 代码分层与职责

### 3.1 `model`：领域模型

领域模型使用不可变类型表达工作流中的业务事实和结果：

| 包 | 主要类型 | 职责 |
|---|---|---|
| `model.alert` | `Alert`、`AlertClassification` | 告警及告警分类 |
| `model.common` | `Device`、`ParkContext` | 设备和诊断上下文 |
| `model.common` | `Diagnosis`、`KnowledgeDocument` | 诊断结果和知识文档 |
| `model.common` | `WorkOrder`、`ApprovalDecision` | 工单和人工审批决定 |
| `model.common` | `RiskLevel`、`WorkflowStatus` | 风险和工作流状态 |
| `model.energy` | `EnergyReading` | 电能表读数、基线及偏差 |
| `model.security` | `SecurityEvent` | 安全事件领域对象 |

领域模型负责基本的不变量校验，例如必填字段、枚举值和置信度范围。它不依赖 Spring Web、具体数据库或 Mock 实现。

### 3.2 `port`：外部能力边界

端口是应用层依赖的最小接口：

- `AlertPort`：查询当前告警和设备告警历史
- `DevicePort`：查询设备状态和设备信息
- `EnergyPort`：查询最新能耗读数
- `KnowledgePort`：按关键词检索知识文档
- `WorkOrderPort`：按工作流查询工单并创建工单
- `SecurityPort`：安全事件能力边界，当前主要用于架构和边界验证

工作流和工具依赖端口，而不直接依赖适配器。该设计允许把 Mock 数据替换为数据库、园区平台 API、消息系统或其他外部服务。

### 3.3 `adapter.mock`：Mock 外部系统

`MockParkDataStore` 持有设备、能耗、告警、历史告警、知识文档和工作订单数据。各 Mock Adapter 对外实现一个端口，并把访问转发给数据存储。

Mock 数据具备以下特征：

- 固定时间基准，测试结果可重复
- 覆盖温度、配电和能耗三类告警
- 包含设备历史、知识文档和能耗基线
- 工单按 `workflowId` 幂等创建
- 能耗读取是只读操作，不会控制真实设备

### 3.4 `agent`：模型调用和结构化输出

`AlertTriageAgent` 负责告警分诊，要求模型严格返回：

```json
{
  "category": "AlertClassification 枚举值",
  "priority": "LOW | MEDIUM | HIGH",
  "riskLevel": "RiskLevel 枚举值",
  "confidence": 0.0
}
```

`AlertDiagnosisAgent` 负责在已有告警、园区上下文和知识文档基础上进行诊断。它向模型提供只读工具回调，工具包括：

- `lookupDeviceStatus`
- `lookupAlert`
- `lookupAlertHistory`
- `lookupEnergyConsumption`
- `lookupWorkOrders`
- `searchParkKnowledge`

Agent 在模型响应返回后使用 Jackson 解析 JSON，并校验字段集合、类型、枚举、关联 ID、证据数组、时间格式和置信度。诊断阶段不会向模型暴露 `createWorkOrder` 工具。

`PromptCatalog` 集中管理系统提示词和用户提示词，中文业务描述与英文程序字段并存：业务内容使用中文，JSON 字段名、枚举和工具名保持代码契约不变。

### 3.5 `workflow`：状态图和运行时状态

`AlertWorkflow` 在构造时创建并编译 `StateGraph`。图的节点和边由 `AlertWorkflowNodes` 提供，状态统一由 `AlertWorkflowState` 管理。

`AlertWorkflowState` 保存工作流快照中的以下核心字段：

```text
workflowId
alertId
alert
classification
parkContext
retrievedDocuments
diagnosis
riskLevel
approval
workOrder
status
errors
eventSequence
route
resultSummary
```

状态序列化使用 `SpringAIJacksonStateSerializer`。枚举、时间、记录类型和集合在进入图状态或 HTTP 响应前转换为可序列化结构。

## 4. 告警处理流程

### 4.1 主流程

```text
START
  │
  ▼
classifyAlert
  │ 查询告警 + Agent 分诊
  ▼
collectParkContext
  │ 查询设备、历史告警、已有工单
  ▼
retrieveKnowledge
  │ 按分类检索知识文档
  ▼
diagnoseAlert
  │ Agent 调用只读工具并生成诊断
  ▼
riskGate
  ├─────────────── CREATE_WORK_ORDER ──> createWorkOrder ──┐
  ├─────────────── WAIT_FOR_APPROVAL ──> humanApproval      │
  └─────────────── REJECT ────────────> summarizeResult    │
                                      ▲                    │
                                      └────────────────────┘
                                                   │
                                                   ▼
                                           summarizeResult
                                                   │
                                                  END
```

### 4.2 节点职责

1. `classifyAlert`
   - 从 `AlertPort` 获取告警
   - 调用 `AlertTriageAgent`
   - 写入分类、优先级、风险级别和置信度

2. `collectParkContext`
   - 通过 `DevicePort` 获取设备
   - 通过 `AlertPort` 获取设备历史告警
   - 通过 `WorkOrderPort` 获取当前工作流已有工单
   - 组装 `ParkContext`

3. `retrieveKnowledge`
   - 根据分类枚举生成检索词
   - 通过 `KnowledgePort` 查询匹配文档
   - 没有匹配文档时保留空列表，后续风险闸门会视为证据不足

4. `diagnoseAlert`
   - 将告警、园区上下文和知识文档交给 `AlertDiagnosisAgent`
   - Agent 可以查询设备、告警历史、能耗、知识和已有工单
   - 写入结构化 `Diagnosis`

5. `riskGate`
   - 由 `AlertWorkflowNodes.RiskGate` 决定下一条路由
   - 高风险、低置信度或无知识证据时进入人工审批
   - 只有风险可接受且证据充分时才允许直接创建工单

6. `humanApproval`
   - 使用 Graph interruption 暂停工作流
   - 状态为 `WAITING_APPROVAL`
   - 审批接口提交后恢复原 Graph thread
   - 同意进入工单创建，拒绝进入结果汇总

7. `createWorkOrder`
   - 先按 `workflowId` 查询已有工单
   - 没有工单时调用 `WorkOrderPort.create`
   - 重试或恢复时返回已有工单，避免重复写入

8. `summarizeResult`
   - 根据审批决定和工单结果生成最终状态
   - 正常结束为 `COMPLETED`
   - 审批拒绝为 `REJECTED`

### 4.3 风险闸门规则

默认置信度阈值是 `0.75`。以下任一条件成立，路由为 `WAIT_FOR_APPROVAL`：

- 原始告警风险提示为高风险
- 分诊风险级别为高风险
- 诊断风险级别为高风险
- 分诊置信度低于 `0.75`
- 诊断置信度低于 `0.75`
- 检索不到知识文档

否则路由为 `CREATE_WORK_ORDER`。当前实现没有自动化的 `REJECT` 路由，拒绝主要由人工审批产生。

## 5. 工作流生命周期与幂等

### 5.1 启动

`POST /api/alerts/{alertId}/workflows` 调用 `AlertWorkflow.start`：

1. 校验告警编号
2. 根据告警编号查询已有执行记录
3. 如果已存在，直接返回已有快照
4. 创建 `workflowId` 和 Graph thread ID
5. 注册执行记录并发布 `STARTED` 事件
6. 执行状态图，直到完成、暂停或失败

### 5.2 暂停与恢复

当风险闸门需要人工审批时，Graph 返回 `InterruptionMetadata`。执行记录保存中断信息，工作流状态更新为 `WAITING_APPROVAL`。

`POST /api/workflows/{workflowId}/approval` 调用 `AlertWorkflow.approve`：

- 只允许对 `WAITING_APPROVAL` 工作流审批
- 使用 `idempotencyKey` 防止重复提交
- 相同幂等键和相同请求体会返回当前结果
- 相同幂等键但请求体不同会报冲突
- 恢复原 Graph thread，继续创建工单或结束流程

### 5.3 运行时存储

当前 `WorkflowExecutionStore` 使用内存实现，Graph checkpoint 使用 `MemorySaver`。因此当前实现适合本地学习、演示和测试，不适合直接作为多实例生产部署方案。

生产化时应替换：

- 内存执行存储为持久化数据库或工作流存储
- `MemorySaver` 为持久化 checkpoint
- 内存事件流为可持久化消息或事件存储
- 单机幂等校验为跨实例一致的唯一约束或分布式锁

## 6. Web 接口与事件流

### 6.1 REST 接口

| 方法 | 路径 | 作用 |
|---|---|---|
| `POST` | `/api/alerts/{alertId}/workflows` | 启动或返回告警对应工作流 |
| `GET` | `/api/workflows/{workflowId}` | 查询工作流快照 |
| `POST` | `/api/workflows/{workflowId}/approval` | 提交人工审批 |
| `GET` | `/api/workflows/{workflowId}/events` | 订阅工作流 SSE 事件 |

Controller 只负责请求校验、调用工作流服务和 DTO 转换，不直接访问 Mock 数据存储。

### 6.2 SSE 事件

`WorkflowEventPublisher` 为每个工作流维护一个 Reactor `Sinks.Many`，事件流使用 replay-all，因此订阅者可以收到已经发布的历史事件。

典型事件类型包括：

- `STARTED`
- `NODE_STARTED`
- `TOOL_CALLED`
- `NODE_COMPLETED`
- `PAUSED`
- `RESUMED`
- `COMPLETED`
- `FAILED`

事件包含工作流编号、序号、事件类型、节点、时间戳和安全摘要。事件摘要经过 `WorkflowEvent.redact` 处理。

## 7. 安全与数据边界

### 7.1 Agent 工具边界

诊断 Agent 只接收只读工具。工单写入由工作流节点控制，并且必须经过风险闸门；高风险或证据不足的流程必须先等待人工审批。

### 7.2 对外响应脱敏

`WebDtos` 对诊断、审批和工单内容使用固定的脱敏文本：

- 诊断根因、摘要、证据和建议不直接返回原文
- 审批人身份和审批评论不直接返回原文
- 工单摘要和证据不直接返回原文
- 标识符、状态、时间和事件序号保留用于跟踪流程

`WorkflowEvent` 对 API key、secret、token、password 等敏感字段进行脱敏。`SensitiveDataTest`、`SecurityBoundaryTest` 和 `CapabilityPackageTest` 用于验证这些边界不会被意外突破。

### 7.3 当前安全假设

当前项目没有实现用户认证、授权、租户隔离或生产级密钥管理。DashScope API Key 通过 Spring 配置和环境变量提供，真实部署时应接入密钥管理服务，并在网关或应用层补充身份认证和权限校验。

## 8. 可替换点与生产化路径

### 8.1 外部系统接入

新增真实系统时实现对应端口，例如：

```text
第三方告警平台 -> AlertPort
设备管理平台   -> DevicePort
能耗平台       -> EnergyPort
知识库/搜索服务 -> KnowledgePort
工单平台       -> WorkOrderPort
安全平台       -> SecurityPort
```

适配器应负责协议转换、超时、重试、外部错误映射和数据格式转换。工作流节点只处理领域语义和流程路由。

### 8.2 持久化和多实例

生产部署至少需要持久化以下内容：

- 工作流状态和 Graph checkpoint
- 工作流与告警的唯一关联
- 审批请求及幂等键
- 工单创建结果
- 工作流事件和事件序号

多实例场景下，启动、审批和工单创建必须使用数据库唯一约束、事务或分布式协调机制保证一致性。

### 8.3 可观测性

建议为每次工作流统一记录：

- `workflowId`、`alertId`、`graphThreadId`
- 节点开始、结束和耗时
- 外部端口调用耗时与失败原因
- Agent 调用模型、令牌用量和结构化输出校验结果
- 风险闸门输入和最终路由
- 审批人、审批时间和幂等结果

日志中不得记录 API Key、完整模型提示词中的敏感字段或未脱敏的诊断内容。

## 9. 测试策略

测试按架构边界分组：

- `model`：验证领域对象不变量和序列化行为
- `adapter.mock`：验证 Mock 数据和端口边界
- `agent`：使用 `TestChatModel` 验证提示词、工具列表和结构化输出校验
- `tool`：验证空参数、未知资源和结构化错误结果
- `workflow`：验证正常路径、风险闸门、人工审批、幂等、失败恢复和能耗流程
- `web`：验证 REST DTO、状态码、SSE 格式和响应脱敏
- `architecture`：验证能力包依赖和安全边界
- `integration`：可选验证真实 DashScope 连通性

运行全部测试：

```bash
./mvnw test
```

Windows PowerShell：

```powershell
.\mvnw.cmd test
```

真实 DashScope 测试需要配置 `AI_DASHSCOPE_API_KEY`，否则仅运行 Mock 和离线测试。

## 10. 关键设计决策

1. **工作流负责副作用，Agent 负责判断。** Agent 可以读取事实并提出诊断，但不能直接创建工单。
2. **端口隔离外部系统。** 业务流程不绑定 Mock、数据库或具体厂商 API。
3. **高风险默认人工确认。** 风险高、置信度低或证据为空时不自动创建工单。
4. **状态和事件分离。** 状态用于查询当前结果，事件用于展示流程进度和审计轨迹。
5. **输出默认脱敏。** 外部 API 保留流程跟踪所需字段，隐藏诊断、审批和工单业务内容。
6. **当前实现优先可测试性。** 内存存储和固定 Mock 数据降低学习和测试成本，生产化时通过端口和存储接口替换。
