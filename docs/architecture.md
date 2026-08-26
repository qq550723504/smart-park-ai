# 智慧园区 AI 工作流项目架构

> 文档基线：当前 `main` 分支实现。本文描述已经存在于代码库中的运行链路，不把未合并分支中的能力当作当前功能。

## 1. 项目定位与当前能力

项目是一个基于 Spring Boot、Spring AI Alibaba 和 Vue 3 的智慧园区 AI 工作流示例。当前 `main` 已形成四条可以独立运行的业务链路，其中告警处置、运营分析和专家协作接入统一执行轨迹，客服使用独立的会话与工单状态链路：

- 告警处置：告警分诊、园区上下文收集、知识检索、AI 诊断、风险门禁、人工审批和工单创建。
- 园区客服：基于园区知识回答停车、访客和能耗问题；报修、知识不足或策略限制时转人工。
- 运营分析：自然语言解析为受指标目录约束的查询计划，生成并校验只读 SQL，执行真实分析数据库查询，返回表格、图表和证据约束的结论。
- 专家协作：主管根据问题选择能耗、设备、安防专家，分支并行取证，经过证据校验后汇总结论。

此外，项目还提供按领域隔离的知识管理、统一执行事件流、角色边界、审计、反馈和只读 MCP 工具生态演示。

当前实现的默认目标是本地学习、演示和自动化测试。它不是生产控制系统：默认外部园区数据为 Mock，工作流和会话状态主要保存在进程内，也没有生产级身份认证、租户隔离和设备控制能力。

## 2. 总体架构

```text
┌──────────────────────────────────────────────────────────────┐
│ Vue 3 智慧园区运营控制台                                     │
│ 告警工作流 │ 园区客服 │ 专家协作 │ 运营分析 │ 统一执行轨迹栏 │
└──────────────────────────────┬───────────────────────────────┘
                               │ REST / SSE
                               ▼
┌──────────────────────────────────────────────────────────────┐
│ Web/API 边界                                                   │
│ Alert / CustomerService / ExpertCollaboration / Operations     │
│ KnowledgeAdmin / Audit / Feedback / ExecutionEvent Controllers  │
└───────────────┬───────────────────────┬────────────────────────┘
                │                       │
                ▼                       ▼
┌─────────────────────────┐   ┌────────────────────────────────┐
│ 业务工作流与能力运行时    │   │ 横切运行时                       │
│ AlertWorkflow            │   │ ExecutionEventPublisher         │
│ CustomerServiceWorkflow  │   │ AuditTrail / FeedbackService    │
│ OperationsAnalysisGraph  │   │ Demo metrics / fault injector   │
│ ExpertCollaborationGraph │   └────────────────────────────────┘
└───────────────┬─────────┘
                │ 领域模型、Agent、只读工具、端口
                ▼
┌──────────────────────────────────────────────────────────────┐
│ 领域与端口                                                     │
│ Alert / Device / Energy / Security / Knowledge / WorkOrder     │
│ Customer session / Customer ticket / analysis typed models      │
└───────────────┬───────────────────────────────────────────────┘
                │ 适配器实现
                ▼
┌──────────────────────────────────────────────────────────────┐
│ 外部能力                                                       │
│ Mock 内存数据 │ SimpleVectorStore/RAG │ DashScope │ PostgreSQL  │
│ 只读 MCP Server                                                  │
└──────────────────────────────────────────────────────────────┘
```

依赖方向保持为：`Web/API → 能力运行时 → 领域模型/端口 → 适配器`。工作流、Agent 和工具不直接依赖 Mock 存储、具体数据库或外部厂商协议。

## 3. 技术栈与启动装配

当前版本基线为：

| 层次 | 实现 |
| --- | --- |
| 后端 | Java、Spring Boot `4.0.0` |
| AI 与编排 | Spring AI `2.0.0-M1`、Spring AI Alibaba `2.0.0-M1.1`、Alibaba Cloud AI Graph |
| Web | Spring MVC、Reactor Flux、REST、SSE |
| 前端 | Vue 3、TypeScript、Vite、Element Plus |
| 分析数据库 | PostgreSQL、Flyway、JDBC；仅分析链路使用 |
| SQL 安全 | JSqlParser AST 校验、查询计划校验、`EXPLAIN` 成本检查 |
| 集成协议 | Spring AI MCP Server；默认关闭 |

`SmartParkApplication` 启动后由 Spring 条件装配以下能力：

- Mock 园区适配器：告警、设备、能耗、安防、知识、工单和客服存储。
- DashScope ChatModel 及告警 Agent；关闭 `spring.ai.dashscope.enabled` 后，告警工作流及依赖它的工具和 Controller 不注册。
- 客服回答模式：`mock` 使用确定性回答，`dashscope` 使用结构化模型回答。
- 知识模式：`mock` 使用内存关键词检索，`rag` 使用 DashScope Embedding 和进程内 `SimpleVectorStore`。
- 运营分析：只有 `smartpark.analytics.enabled=true` 时尝试装配；若分析数据库、管理员账号、只读账号或 ChatModel 配置缺失，启动期间会抛出明确错误。
- 专家协作：只有 ChatModel、三个专家工具集和协作运行时都可用时装配；能力接口据此返回 `collaborationEnabled`。
- MCP：只有 `smartpark.mcp.enabled=true` 时装配；协议和网络边界仍需由部署环境负责。

## 4. 代码分层与模块职责

### 4.1 领域模型 `model`

领域对象用不可变类型表达业务事实、状态和结果，负责必填字段、枚举、标识符、置信度和知识标签等边界校验，不依赖 Web、数据库或 Mock 实现。

| 包 | 代表类型 | 作用 |
| --- | --- | --- |
| `model.alert` | `Alert`、`AlertClassification`、`ParkContext` | 告警、分类和诊断上下文 |
| `model.common` | `Diagnosis`、`Device`、`KnowledgeDocument`、`KnowledgeMatch` | 通用诊断、设备和知识模型 |
| `model.common` | `WorkOrder`、`ApprovalDecision`、`RiskLevel`、`WorkflowStatus` | 工单、审批、风险和工作流状态 |
| `model.customer` | `CustomerServiceResult`、`CustomerTicket`、`KnowledgeCitation` | 客服回答、引用和人工工单 |
| `model.energy` | `EnergyReading` | 当前读数、基线、峰值和偏差 |
| `model.security` | `SecurityEvent` | 脱敏安防事件摘要 |

运营分析和专家协作拥有独立的类型模型，分别位于 `analytics.model` 与 `collaboration.model`，避免把分析计划或专家发现混入告警领域对象。

### 4.2 端口 `port`

端口表达应用真正需要的最小外部能力：

- `AlertPort`：读取告警及设备历史告警。
- `DevicePort`：读取设备状态。
- `EnergyPort`：读取最新能耗读数。
- `SecurityPort`：读取安全事件的脱敏摘要。
- `KnowledgePort`：按 `KnowledgeDomain` 检索知识及相似度结果。
- `KnowledgeAdminPort`：查看元数据、新增文档、启停文档；不改变工作流的只读检索依赖。
- `WorkOrderPort`：查询和创建工单。
- `CustomerSessionStore`、`CustomerTicketPort`、`CustomerAnswerPort`：隔离客服会话、工单和回答能力。

分析链路的端口由 `AnalyticsModelClient`、`OperationsAnalysisService.CostGate` 和 `ExecutionGate` 表达；专家协作的规划、专家分支、证据账本和汇总均通过独立类型和接口隔离。

### 4.3 Mock 与 RAG 适配器 `adapter`

`adapter.mock` 提供固定时间基准、四类演示告警、设备历史、能耗基线、安防摘要、知识文档和内存工单。Mock 能源读取和 MCP 工具都是只读的，不会访问或控制真实设备。

`adapter.rag` 为 `CUSTOMER_SERVICE` 与 `ALERT_OPERATIONS` 分别维护进程内向量索引。种子文档来自 `src/main/resources/knowledge/`；检索默认最多返回五条结果，并按相似度阈值过滤。知识元数据和正文写入有独立边界：管理接口只返回元数据，MCP 只返回匹配元数据，不暴露正文。

RAG 索引和客服会话都属于当前进程状态。应用重启后，RAG 重新加载种子文档；该实现不等价于多实例生产知识库。

### 4.4 Agent 与只读工具 `agent`、`tool`

告警链路包含 `AlertTriageAgent` 和 `AlertDiagnosisAgent`：

- 分诊 Agent 返回严格结构化的分类、优先级、风险级别和置信度。
- 诊断 Agent 只获得告警、设备、历史告警、能耗、知识和已有工单查询工具。
- Agent 输出经 JSON、枚举、关联 ID、证据、时间和置信度校验后，才能进入工作流状态。
- `createWorkOrder` 不作为诊断 Agent 工具暴露；副作用由工作流节点在风险闸门之后执行。

专家分支使用 `AlertQueryTool`、`DeviceQueryTool`、`EnergyQueryTool`、`SecurityQueryTool`、`ParkKnowledgeTool` 和 `WorkOrderTool` 的只读/受控工具集。每次成功工具调用生成绑定具体调用参数的证据引用，专家结论必须引用真实成功调用的证据；失败调用不授权任何结论。

## 5. 四条业务运行链路

### 5.1 告警处置工作流

```text
START
  → classifyAlert
  → collectParkContext
       ├─ ENERGY → energyAnalysis → retrieveKnowledge
       ├─ ACCESS → securityReview → retrieveKnowledge
       └─ 其他分类 → retrieveKnowledge
  → diagnoseAlert
  → riskGate
       ├─ CREATE_WORK_ORDER → createWorkOrder → summarizeResult → END
       ├─ WAIT_FOR_APPROVAL → humanApproval ─┬→ createWorkOrder → summarizeResult
       │                                      └→ summarizeResult
       └─（人工拒绝后）REJECTED
```

核心规则：风险提示高、分诊或诊断风险高、任一置信度低于 `0.75`、或没有知识证据时，必须进入人工审批。只有风险可接受且证据充分时，才允许直接创建工单。工单创建按 `workflowId` 查询已有结果，恢复或重试不会重复创建。

工作流使用 Alibaba Cloud AI Graph 的 `StateGraph` 和 `MemorySaver`。内部快照包含工作流运行所需的完整状态，Web 层再映射为安全投影。告警 `WorkflowResponse` 除 `workflowId`、`alertId`、`status`、`errors`、`eventSequence` 和 `riskReasons` 外，还可返回结构化的诊断、审批和工单投影：诊断保留 `id`、关联告警/设备 ID、`riskLevel`、`confidence` 和 `diagnosedAt`；审批保留 `decision` 和 `decidedAt`；工单保留工单/工作流/园区/楼宇/设备/告警 ID、`riskLevel`、`status`、创建和更新时间，以及审批决策。诊断、审批和工单中的自由文本及证据内容通过 Web DTO 与事件层脱敏或替换为固定安全摘要。

### 5.2 园区客服工作流

客服工作流将请求分为停车、访客、公共区域能耗、报修和未知/不支持意图：

1. 识别意图并按 `CUSTOMER_SERVICE` 领域检索知识。
2. 有足够证据时返回带知识引用的回答。
3. 报修意图直接确认并创建人工客服工单，不依赖知识检索成功。
4. 知识不足、策略限制或检索故障时不生成泛化答复，按契约转人工或返回受控失败。
5. 同一会话支持多轮提问；已进入人工处理的会话停止新的自动回答。

客服请求支持 `Idempotency-Key`。相同幂等键和相同请求体返回稳定结果，不同请求体返回冲突；同一会话使用串行协调，避免多轮消息丢失。默认会话存储是有界 TTL 内存实现，过期或容量淘汰时会协调清理关联内存工单。

### 5.3 自然语言运营分析

运营分析仅查询专用分析数据库，主流程由 `OperationsAnalysisGraph` 编排：

```text
understandQuestion
  → resolveMetricAndDimensions
  → recallAllowedSchema
  → buildQueryPlan
  → generateSql
  → validateSqlAst
  → explainAndCheckCost
  → executeReadOnlyQuery
  → buildChartSpec
  → summarizeFromResult
```

实现要点：

- 问题先解析为受 `MetricCatalog` 和分类词汇约束的指标、维度、过滤条件和时间范围。
- 指标目录和允许视图限制可查询数据范围；未确定的指标口径会暂停并要求结构化澄清。
- 模型生成的 SQL 视为不可信输入，必须通过 SQL AST、查询计划、参数绑定和时间边界校验。
- `EXPLAIN` 成本超限或安全策略拒绝时，最多允许一次受控修复；仍不合规则终止分析。
- 查询使用只读事务、超时、最大行数和最大结果字节数限制；查询失败不会伪造图表或结论。
- 图表单位来自指标目录而不是模型自由填写；最终总结必须能被已执行结果验证。

分析运行支持“启动—澄清—恢复—完成/失败”生命周期，状态和终态保留采用进程内 `AnalysisRunStore`。前端展示查询计划、SQL 安全状态、结果表、图表和结论。

### 5.4 Supervisor 专家协作

专家协作由 `ExpertCollaborationService` 和 `ExpertCollaborationGraph` 组成：

1. Supervisor 将自然语言问题解析为规范问题、必要专家领域和每个领域的任务。
2. 只选择实际需要的 `ENERGY`、`DEVICE`、`SECURITY` 分支。
3. 选中的分支共享一个截止时间并行执行；默认最大并行度为 `3`。
4. 每个专家只能调用本领域工具，并提交结构化发现、状态、结论、证据引用、置信度和后续检查。
5. `ExpertFindingValidator` 和 `SynthesisValidator` 检查领域、证据、状态和引用范围；主管不能凭空补写结论。
6. 部分分支失败会保留已完成发现；全部分支失败、汇总失败或超时则显式终止为失败。

每次运行以 UUID 标识，运行状态和专家发现写入 `CollaborationRunStore`，工具调用、专家交接和终态进入统一执行事件流。

## 6. 统一执行事件与前端控制台

`execution` 包为告警、运营分析和专家协作提供统一的 `ExecutionEvent`：

```text
eventId / runId / sequence / timestamp
scenario / actor / stage / eventType / status
safeSummary / typed displayPayload
```

当前事件场景包括 `ALERT_WORKFLOW`、`OPERATIONS_ANALYSIS` 和 `EXPERT_COLLABORATION`。事件类型覆盖运行启动、节点开始/完成、工具调用、专家交接、SQL 生成/校验/拒绝、查询完成、图表规格、暂停/恢复、失败和完成。

`InMemoryExecutionEventPublisher` 为每个运行保存历史并支持订阅；统一接口 `GET /api/executions/{runId}/events` 以 SSE 重放历史并继续推送实时事件，直到终态关闭。告警旧 SSE 接口由 `ProjectedWorkflowEventPublisher` 适配到统一事件层，保持兼容。

`DisplayPayload` 是受控的类型化展示负载：文本、工具调用、专家交接、SQL、图表、音频状态和错误分别使用不同结构。SQL 只发送经过校验的安全版本；音频负载当前只表示状态元数据。事件模型为未来语音场景预留了 `VOICE` 和音频事件枚举，但当前 `main` 分支没有语音 Session、WebSocket 或前端语音入口，不应视为当前已交付能力。

Vue 3 控制台按场景切换页面：告警工作流、园区客服、专家协作和运营分析；右侧统一执行轨迹栏为告警、专家协作和运营分析通过 `runId` 订阅后端事件。客服当前通过会话消息和工单状态展示进展，不接入统一执行事件流。`X-Demo-Role` 用于本地演示查看者、操作员、审批人、客服坐席和管理员的 UI/API 操作边界，不是生产身份系统。

## 7. 知识、审计、反馈和 MCP

### 7.1 知识管理

知识按 `CUSTOMER_SERVICE` 和 `ALERT_OPERATIONS` 分域。普通工作流只依赖 `KnowledgePort` 的检索；管理员通过 `KnowledgeAdminPort` 查看元数据、新增文档和启停文档。文档 ID、标题、正文和标签在领域边界校验，检索结果带相似度和文档 ID，避免调用方绕过知识领域。

### 7.2 审计与反馈

工作流事件描述系统如何执行，`AuditTrail` 描述角色对资源执行的动作，两者分离。客服会话、客服工单、审批、知识管理、反馈和演示故障等操作记录安全元数据，不记录用户原始问题、完整诊断正文、审批评论或敏感身份信息。反馈服务只接收目标类型、资源 ID、角色和枚举化评价。

### 7.3 只读 MCP

启用 `SMARTPARK_MCP_ENABLED=true` 后，MCP Server 暴露三个只读工具：

| 工具 | 返回 |
| --- | --- |
| `smartpark_lookup_alert` | 告警 ID、园区/楼宇/设备、分类、风险提示和发生时间 |
| `smartpark_lookup_energy` | 当前读数、基线、峰值功率、偏差和时间 |
| `smartpark_search_knowledge` | 指定知识领域内最多五条文档元数据 |

MCP 不提供知识正文、身份数据、工作流变更、工单写入、设备控制、Resources、Prompts 或 Completion。服务默认关闭，且当前没有认证、租户隔离和公网安全边界；仅适合可信本地演示。

## 8. 数据与安全边界

- **Agent 与副作用：** Agent 只读事实并提出判断；工单写入由风险门禁后的工作流节点负责。
- **安防数据：** 只允许脱敏摘要进入通用链路，不包含原始视频、图片、人脸特征或身份证等人员原始记录。
- **告警对外输出：** 告警工作流的 Web DTO 除流程跟踪字段外，还暴露 `WorkflowResponse` 的 `status`、`diagnosis`、`approval`、`workOrder`、`errors`、`eventSequence` 和 `riskReasons` 等受控投影：诊断投影包含诊断/告警/设备 ID、风险级别、置信度和时间；审批投影包含决定和时间；工单投影包含工单/工作流/园区/建筑/设备/告警 ID、风险级别、状态、嵌套审批投影和创建/更新时间。诊断、工单和证据中的自由文本，以及审核人身份和原始审批评论按脱敏契约隐藏或替换。统一事件另行暴露事件/运行 ID、序号、时间、场景、角色、阶段、事件类型、状态和安全摘要。客服、运营分析和专家协作接口按各自产品契约返回必要的回答、分析结果或专家发现。
- **SQL 分析：** 应用运行账号固定为 `smartpark_analytics_ro`，只授予四个白名单分析视图的 `SELECT`；管理员账号用于 Flyway 迁移、可选的本地演示数据刷新，以及应用启动时为只读角色同步运行时凭据。
- **密钥：** 本地和容器部署推荐通过当前进程的 `AI_DASHSCOPE_API_KEY` 环境变量注入 DashScope Key；`application.yml` 使用 Spring 配置占位符，并不强制值只能来自操作系统环境变量。真实部署应接入密钥管理服务，不写入源码、`.env`、命令行参数或 Git 历史。
- **演示授权：** `X-Demo-Role` 仅用于本地演示边界，必须由网关/应用真实认证授权替换。

## 9. API 与事件接口

### 9.1 业务 API

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `POST` | `/api/alerts/{alertId}/workflows` | 启动或复用告警工作流 |
| `GET` | `/api/workflows/{workflowId}` | 查询告警工作流脱敏快照 |
| `POST` | `/api/workflows/{workflowId}/approval` | 提交人工审批 |
| `GET` | `/api/workflows/{workflowId}/events` | 兼容告警工作流 SSE |
| `GET` | `/api/workflows/{workflowId}/observability` | 查询工具调用和失败节点汇总 |
| `POST` | `/api/customer-service/sessions` | 创建客服会话 |
| `POST` | `/api/customer-service/sessions/{sessionId}/messages` | 在会话中继续提问 |
| `GET` | `/api/customer-service/sessions/{sessionId}/conversation` | 查询对话和安全检索轨迹 |
| `GET/PATCH` | `/api/customer-service/tickets[/{ticketId}]` | 查询或推进人工客服工单 |
| `POST` | `/api/expert-collaboration/runs` | 发起专家协作 |
| `GET` | `/api/expert-collaboration/runs/{runId}` | 查询专家协作状态、发现和汇总 |
| `POST` | `/api/operations-analysis/runs` | 发起自然语言运营分析 |
| `POST` | `/api/operations-analysis/runs/{runId}/clarifications` | 提交指标口径澄清 |
| `GET` | `/api/operations-analysis/runs/{runId}` | 查询运营分析状态和结果 |
| `GET` | `/api/executions/{runId}` | 查询统一执行运行摘要 |
| `GET` | `/api/executions/{runId}/events` | 订阅统一执行 SSE |

### 9.2 运维与演示 API

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| `GET` | `/api/operations/capabilities` | 返回知识、客服、分析和专家协作能力状态 |
| `GET` | `/api/operations/metrics` | 返回工作流、客服、知识、反馈和审计计数 |
| `GET/POST/PATCH` | `/api/knowledge[/{documentId}/active]` | 管理知识元数据和启停状态 |
| `GET/POST` | `/api/feedback` | 提交或查询枚举化反馈 |
| `GET` | `/api/audit` | 查询脱敏审计记录 |
| `POST` | `/api/demo/faults` | 注入一次性演示故障 |
| `POST` | `/mcp` | 在显式启用时提供只读 MCP 工具 |

## 10. 存储、配置与生产化边界

### 10.1 当前存储

当前实现的工作流快照、Graph checkpoint、统一执行事件、客服会话/工单、专家协作运行和反馈审计主要是进程内存储。RAG 使用进程内 `SimpleVectorStore`；运营分析的事实数据可来自独立 PostgreSQL，但分析运行状态本身仍由进程内 Store 管理。

因此当前版本适合单进程演示和测试，不保证重启恢复、跨实例幂等或多实例事件一致性。

### 10.2 主要配置

| 配置 | 默认值 | 作用 |
| --- | --- | --- |
| `SPRING_AI_DASHSCOPE_ENABLED` | `true` | 注册告警模型、Agent、工具和工作流；离线演示通常显式设为 `false` |
| `SMARTPARK_KNOWLEDGE_MODE` | `mock` | `mock` 关键词检索或 `rag` 进程内向量检索 |
| `SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE` | `mock` | Mock 回答或 DashScope 结构化回答 |
| `SMARTPARK_ANALYTICS_ENABLED` | `false` | 启用专用 PostgreSQL 只读分析链路 |
| `SMARTPARK_ANALYTICS_DEMO_DATA_REFRESH_ENABLED` | `false` | 仅本地演示时刷新分析夹具时间窗口 |
| `SMARTPARK_MCP_ENABLED` | `false` | 启用只读 MCP Server |
| `SMARTPARK_EXPERT_TIMEOUT` | `15s` | 单个专家分支超时 |
| `SMARTPARK_EXPERT_RUN_TIMEOUT` | `40s` | 专家协作总运行超时 |
| `SMARTPARK_EXPERT_MAX_PARALLEL` | `3` | 专家分支和运行队列的并行上限 |

Compose 默认栈以 Mock/离线模式运行；analytics overlay 才显式启用独立 PostgreSQL 分析数据库。真实 DashScope、RAG 和分析链路需要分别配置凭据、网络和数据源。

### 10.3 生产化替换项

生产接入至少需要：

- 用持久化工作流存储和 Graph checkpoint 替换内存执行状态。
- 为工作流、审批、工单、客服请求和分析恢复建立跨实例唯一约束及幂等机制。
- 用持久化事件总线/事件存储替换进程内事件流，并设计事件序号和重放策略。
- 用 PostgreSQL/pgvector、Redis 或其他持久化知识库替换 `SimpleVectorStore`，补齐切片、导入、版本和索引治理。
- 用真实告警、设备、能耗、安防和工单平台适配器替换 Mock，并在端口层处理超时、重试、错误映射和审计。
- 用生产身份认证、细粒度授权、租户隔离和密钥管理替换 `X-Demo-Role` 与环境变量演示方案。
- 为分析数据库、MCP 和安防数据增加网络隔离、访问控制、审计和合规策略。

## 11. 测试与验证策略

测试按边界组织：

- `model`：领域不变量、序列化和脱敏模型。
- `adapter.mock`、`adapter.rag`、`adapter.mcp`：数据、检索、MCP 输出和错误边界。
- `agent`、`tool`：提示词契约、工具列表、结构化输出和只读错误结果。
- `workflow`：告警风险门禁、审批恢复、幂等、失败恢复和客服并发。
- `analytics`：指标目录、澄清、SQL AST/计划/成本安全、只读执行和结果总结约束。
- `collaboration`：动态选专家、并行分支、证据绑定、超时和汇总校验。
- `execution`、`web`：事件序号、终态关闭、SSE、DTO 和脱敏。
- `architecture`：依赖方向、能力包和安全边界。
- `integration`：显式配置时验证真实 DashScope 或 PostgreSQL 连接。

Windows PowerShell 运行后端测试：

```powershell
.\mvnw.cmd test
```

前端验证：

```powershell
Set-Location ui
npm ci
npm run build
```

默认测试不访问外网，也不需要真实 API Key。真实模型连通性测试必须显式开启，并且不能把密钥写入仓库。

## 12. 关键设计决策

1. **工作流负责副作用，Agent 负责判断。** 读写边界清晰，风险门禁之后才允许工单写入。
2. **端口隔离外部系统。** Mock、数据库和第三方平台可以替换，不把协议细节带入业务流程。
3. **证据不足默认保守。** 高风险、低置信度、无知识证据或不支持的客服问题不会静默生成结果。
4. **分析 SQL 默认不可信。** 目录、AST、计划、参数、成本和只读执行构成多道安全门。
5. **统一事件服务于可解释性。** 不同业务链路共享 runId、事件格式和前端执行轨迹，同时保留旧告警接口兼容层。
6. **对外输出默认脱敏。** 外部可看到流程跟踪字段和受控的结构化风险、决策、位置标识及状态投影；敏感业务正文不进入公开 DTO、MCP 或事件。
7. **演示能力与生产能力分开。** 内存存储、Mock 适配器、演示角色和默认关闭的可选链路降低本地体验成本，但不被当作生产保证。
