# 智慧园区设备告警智能处置设计

## 1. 目标与范围

在当前空目录构建一个面向智慧园区的 Spring AI Alibaba 学习项目。项目以“设备告警智能处置”为第一条完整业务链，练习 DashScope、Tool Calling、RAG、Agent、Graph Workflow、SSE 流式事件、状态恢复和 Human-in-the-Loop。

第一阶段只使用本地 Mock 适配器模拟园区设备、告警、工单和知识库系统。Mock 实现必须通过端口接口访问，后续替换为真实 IoT、物业工单或园区平台 API 时，不修改 Agent 和 Workflow 的业务编排。

不在第一阶段实现真实设备控制、真实工单写入、生产认证、租户管理或复杂前端。任何可能产生外部副作用的动作都通过人工审批边界隔离。

## 2. 业务场景

园区中的温控器、门禁、照明、配电柜和水泵等设备产生告警。系统需要结合当前设备状态、历史告警、关联工单和园区应急预案，输出可解释的诊断结果，并决定是否创建处置工单。

### 低风险告警

系统可以自动完成：

1. 识别告警类型和优先级。
2. 查询相关设备和历史数据。
3. 检索相关园区制度或应急预案。
4. 生成诊断摘要和建议动作。
5. 创建 Mock 工单并返回工单编号。

### 高风险告警

系统只能自动完成分析和建议，Graph 在创建工单或执行动作前暂停，等待人工确认。人工确认信息必须包含审批人、决定、意见和时间，并进入执行状态，拒绝则结束为 `REJECTED`。

## 3. 目标架构

```text
REST API
  |
  +-- 启动告警处理 ------------------------------+
  |                                             |
  +-- 查询 Workflow 状态                         v
  +-- 提交人工审批                    AlertWorkflow / StateGraph
  +-- 订阅 SSE 事件                    |
                                      +-- classifyAlert
                                      +-- collectParkContext
                                      +-- retrieveKnowledge
                                      +-- diagnoseAlert
                                      +-- riskGate
                                      +-- humanApproval (interrupt)
                                      +-- createWorkOrder
                                      +-- summarizeResult
                                                    |
                                                    v
                         Port interfaces / Mock adapters
                         +-- DevicePort
                         +-- AlertPort
                         +-- WorkOrderPort
                         +-- KnowledgePort
                                                    |
                                                    v
                              MockParkSystem in memory
```

Graph 是流程状态的唯一编排者。Agent 负责需要模型推理的分类、诊断和摘要；确定性的查询、风险判断和工单写入由普通 Java 节点与端口完成，避免把业务规则全部交给模型。

## 4. 模块边界

推荐包结构如下：

```text
com.example.smartpark
├── SmartParkApplication
├── web
│   ├── AlertWorkflowController
│   ├── ApprovalController
│   └── WorkflowEventController
├── workflow
│   ├── AlertWorkflow
│   ├── AlertWorkflowState
│   ├── AlertWorkflowNodes
│   └── WorkflowEventPublisher
├── agent
│   ├── AlertTriageAgent
│   ├── AlertDiagnosisAgent
│   └── PromptCatalog
├── tool
│   ├── DeviceQueryTool
│   ├── AlertQueryTool
│   ├── WorkOrderTool
│   └── ParkKnowledgeTool
├── park
│   ├── DevicePort
│   ├── AlertPort
│   ├── WorkOrderPort
│   ├── KnowledgePort
│   └── mock
└── model
    ├── Device
    ├── Alert
    ├── WorkOrder
    ├── KnowledgeDocument
    └── ApprovalDecision
```

`agent` 不直接依赖 Mock 类，只依赖 Spring AI 的模型和工具接口。`workflow` 不直接访问内存 Map，而是依赖 `park` 端口。`web` 不暴露 Graph 内部对象，使用稳定的请求、响应和事件 DTO。

## 5. Mock 适配器

Mock 系统提供一组固定、可重复的园区数据：

- 园区 `PARK-A`。
- 楼栋 `A1`、`A2`。
- 设备包括空调、配电柜、门禁和水泵。
- 至少包含低风险温度异常和高风险配电异常两种告警。
- 历史告警和工单数据支持按设备编号查询。
- 创建工单返回确定格式的 Mock 编号，例如 `MOCK-WO-1001`。
- 知识库至少包含设备过热、漏水和配电异常三份文档。

Mock 数据必须支持测试重置，测试不能依赖测试执行顺序。默认实现只在进程内保存状态，不模拟真实设备写操作。

## 6. Workflow 状态

`AlertWorkflowState` 至少包含：

- `workflowId`
- `alertId`
- `alert`
- `classification`
- `parkContext`
- `retrievedDocuments`
- `diagnosis`
- `riskLevel`
- `approval`
- `workOrder`
- `status`
- `errors`
- `eventSequence`

状态中的对象必须可序列化或能稳定转换为 Map，确保后续接入 Graph checkpoint 时可以恢复。模型原始响应不作为业务状态的唯一来源，关键结论必须转换为结构化领域对象。

## 7. Workflow 节点

### `classifyAlert`

输入告警，调用 `AlertTriageAgent`，输出结构化的告警类型、优先级、风险等级和置信度。模型无法产生合法结构时进入错误状态，不自动猜测。

### `collectParkContext`

确定性调用设备、历史告警和关联工单端口，形成诊断上下文。

### `retrieveKnowledge`

根据告警类型和设备信息检索 Mock 知识库。第一阶段可以使用确定性的关键词检索；接口保留向量检索扩展点，后续再接 Embedding 和 pgvector。

### `diagnoseAlert`

调用 `AlertDiagnosisAgent`，结合上下文和知识文档生成：原因假设、证据、建议动作、风险说明和是否需要人工审批。输出必须是结构化对象。

### `riskGate`

由 Java 规则判断：高风险或诊断置信度低于阈值时转入人工审批；低风险且满足规则时进入工单节点。该节点不让模型决定是否绕过审批。

### `humanApproval`

使用 Graph 中断能力暂停流程，返回待审批数据。审批接口提交后恢复同一个 `workflowId`，拒绝则结束，批准才允许继续。

### `createWorkOrder`

通过 `WorkOrderPort` 创建 Mock 工单。该节点必须具备幂等检查：同一个 `workflowId` 恢复或重复提交不能创建多个工单。

### `summarizeResult`

生成面向园区运营人员的最终摘要，包含告警、证据、诊断、审批和工单结果，不隐藏失败节点。

## 8. HTTP 与 SSE 接口

### 启动流程

`POST /api/alerts/{alertId}/workflows`

返回 `workflowId`、当前状态和是否等待审批。重复启动同一告警时返回已有运行中的流程或明确拒绝，不无条件创建重复流程。

### 查询状态

`GET /api/workflows/{workflowId}`

返回当前状态、诊断结果、审批信息、工单信息和错误信息。

### 人工审批

`POST /api/workflows/{workflowId}/approval`

请求包含 `decision`、`reviewer` 和 `comment`。只有 `WAITING_APPROVAL` 状态允许审批；审批后恢复原 Graph 线程。

### 事件流

`GET /api/workflows/{workflowId}/events`

使用 SSE 推送节点开始、节点完成、工具调用、人工暂停、恢复、失败和流程完成事件。事件包含 `workflowId`、递增序号、事件类型、节点名、时间和脱敏摘要，不发送 API Key 或完整敏感模型报文。

## 9. 错误与安全边界

- DashScope 超时或限流：流程进入可识别的失败状态，保留已完成节点状态。
- 工具查询失败：诊断 Agent 不得把缺失数据当成真实数据，应输出证据不足并提高人工审批等级。
- 结构化输出解析失败：有限次数重试，仍失败则停止流程。
- 工单创建失败：不返回虚假的工单编号，状态保留为 `WORK_ORDER_FAILED`。
- 所有外部写操作都经过 `riskGate` 和审批策略；第一阶段 Mock 也按此约束实现。
- API Key 仅从环境变量读取；日志只输出脱敏摘要。
- 审批恢复必须校验 workflow 状态和审批幂等键，防止重复恢复。

## 10. 测试策略

### 单元测试

- 每个 Mock 端口的查询、创建和重置行为。
- `riskGate` 的低风险、高风险、低置信度和缺少证据分支。
- `AlertWorkflowState` 的状态合并和序列化。
- 工单创建幂等性。

### Agent 测试

- 使用固定 ChatModel 测试替身验证 Prompt 输入和结构化输出解析。
- 测试模型返回非法 JSON、空内容和工具失败时的降级行为。
- 不在单元测试中调用真实 DashScope。

### Workflow 测试

- 低风险告警自动完成并创建一个 Mock 工单。
- 高风险告警在审批节点暂停。
- 批准后恢复并创建工单。
- 拒绝后结束且不创建工单。
- 恢复、重复审批和重复启动不会导致重复副作用。

### Web 测试

- 启动流程、查询状态、审批和 SSE 端点。
- 无效告警编号、无效 workflow 状态、重复审批和非法决策返回明确 HTTP 错误。

真实 DashScope 验证作为独立集成测试，不纳入默认测试命令；只有用户自行配置 `AI_DASHSCOPE_API_KEY` 后才执行。

## 11. 分阶段实现顺序

1. 项目骨架、领域模型、Mock 端口和可重复测试数据。
2. DashScope、Tool Calling 和两个 Agent。
3. Graph Workflow、风险分支和人工审批恢复。
4. REST、SSE、错误状态和幂等性。
5. Mock 知识库升级为 Embedding/RAG。
6. 增加 PostgreSQL checkpoint、真实系统适配器和评估数据集。

第一份实现计划只覆盖第 1 至第 4 阶段，确保先完成一条可运行、可测试、可演示的告警处置链；RAG 向量化和真实系统接入作为后续独立计划。
