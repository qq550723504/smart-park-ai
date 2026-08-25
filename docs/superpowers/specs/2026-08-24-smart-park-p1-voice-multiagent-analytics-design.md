# 智慧园区 P1 实时语音、专家协作与运营分析设计

## 1. 文档状态

- 日期：2026-08-24
- 状态：已完成需求评审，等待用户评审本文档
- 目标版本：Spring AI Alibaba `2.0.0-M1.1`
- 适用项目：`springaialibaba` 智慧园区演示应用

本文档定义三个统一入口下的 P1 场景：实时语音园区助手、专家 Agent 协作和自然语言运营分析。三个场景共用园区业务端口、安全事件模型和统一 Vue 工作台，但各自保留适合其数据流的传输与执行方式。

## 2. 已确认的产品决策

1. 三个 P1 场景组成同一个“智慧园区 AI 运营中心”，使用顶部场景切换和右侧统一轨迹栏。
2. 运行时只走在线真实链路，不提供 Mock、预录音或伪结果降级。
3. 在线服务失败时必须展示真实失败阶段，并保留已产生的安全轨迹。
4. 语音采用“点击开始、点击结束”的流式录音；播放回答期间再次点击麦克风可以中断并开始下一轮。
5. 专家由 Supervisor 动态调度；单领域问题不调用无关专家，跨域展台样例会真实调用能耗、设备和安防三个专家。
6. 自然语言运营分析必须真实执行只读 SQL，并展示查询计划、SQL、安全门禁、结果、图表和结论。
7. 完整展台演示目标约一分钟：语音回答 5–10 秒、专家协作 20–40 秒、运营分析 10–20 秒。
8. 框架采用最新发布的 Spring AI Alibaba `2.0.0-M1.1`，接受其里程碑预发布属性。

## 3. 当前项目基线

当前代码已经具备以下可复用能力：

- `AlertWorkflow`、`StateGraph`、人工审批、内存执行状态和 SSE 事件；
- `AlertPort`、`EnergyPort`、`DevicePort`、`SecurityPort`、`KnowledgePort` 和 `WorkOrderPort`；
- 只读告警、能耗、设备、安防和知识工具；
- 告警、能耗、安防、客服和 RAG 演示数据；
- 工具调用审计、运营指标、角色演示和安全脱敏；
- Vue 3、Element Plus、Vue Flow 工作台。

现有 `WorkflowEvent` 只服务告警工作流，事件类型和安全摘要为固定白名单。新场景不能直接把它扩成任意字符串事件，也不能破坏现有 REST/SSE 契约。应新增通用执行事件，并通过兼容适配器逐步接入现有告警流程。

## 4. 版本与开源复用基线

### 4.1 Spring AI Alibaba 2.0

使用以下相互匹配的版本：

- Spring Boot `4.0.0`
- Spring AI BOM `2.0.0-M1`
- Spring AI Alibaba BOM `2.0.0-M1.1`
- Spring AI Alibaba Extensions BOM `2.0.0-M1.1`
- Java 17

`spring-ai-alibaba-bom` 管理 Agent Framework 和 Graph；DashScope 模型与语音模块由 `spring-ai-alibaba-extensions-bom` 管理。不得混用 1.x 与 2.x 的 Spring AI 或 Spring AI Alibaba 组件。

本地解析已确认 `2.0.0-M1.1` 包含：

- `ReactAgent`
- `SupervisorAgent`
- `ParallelAgent`
- `AgentTool`
- `StreamingTranscriptionModel`
- `DashScopeWebSocketAsrApi`
- `DashScopeAudioSpeechModel`
- `StreamingInputTextToSpeechModel`

`SupervisorAgent` 在 2.0 中已经可用，但其发布版示例采用 Supervisor 与子 Agent 循环路由，更适合顺序交接。本项目已经确认“动态选择后并行分析”，因此不直接套用该顺序路由模式。Supervisor 角色由受约束的 `ReactAgent` 承担计划与汇总，动态并行分支使用 2.0 Graph 原生 `addParallelConditionalEdges`。这复用官方 Graph 并行能力，同时保证未选中的专家不会执行。

### 4.2 NL2SQL 复用边界

`spring-ai-alibaba-starter-nl2sql` 在 Maven Central 中没有可解析的 `1.1.2.3` 或 `2.0.0-M1.1` 构件。项目不得声明不存在的依赖。

本项目采用以下开源复用方式：

- 以 Spring AI Alibaba 官方 SQL Agent Workflow 示例的 `list_tables → get_schema → run_query` Graph 模式为实现基线；
- 参考 Spring AI Alibaba DataAgent 的 Schema 召回、分析计划、SQL 结果和图表报告设计；
- 不部署 DataAgent 的管理后台、模型管理、Python 沙箱和未完成的外部 Access API；
- 园区指标目录、只读分析视图、SQL AST 安全门禁和权限控制属于本项目的业务安全边界，必须独立实现；
- 使用成熟 SQL AST 解析库，例如 JSqlParser，不编写基于正则表达式的 SQL 安全解析器；
- 图表使用 Apache ECharts，不自行实现图表引擎。

### 4.3 升级必须是独立前置切片

`2.0.0-M1.1` 是里程碑版本，并带来 Spring Boot 4 与 Spring AI 2.0 的兼容迁移。第一实现切片只升级框架并恢复现有全部测试，不加入语音、多 Agent 或 NL2SQL 行为。只有升级切片通过后，三个 P1 才能继续。

升级切片必须验证：

1. Maven 依赖树中不存在 Spring AI 1.x/2.x 混用；
2. 当前 MCP 依赖排除是否仍有必要，不能机械复制旧排除项；
3. 现有 Graph 编译、结构化输出、Tool Calling、RAG、SSE 和客服测试全部通过；
4. DashScope Chat、Embedding、ASR 和 TTS Bean 可以按配置独立装配；
5. Supervisor 计划输出、`addParallelConditionalEdges` 动态分支和并行流事件在真实 2.0 API 上通过最小集成测试。

若第 5 项不能满足已确认的动态并行设计，应停止业务实现并重新评审，不能退回前端动画或顺序执行冒充并行。

## 5. 范围

### 5.1 包含

- 统一三场景工作台和通用执行轨迹栏；
- 在线流式 ASR、园区工具调用、流式文本和流式 TTS；
- 三个职责隔离的专家 Agent、Supervisor 计划/汇总 Agent 和 Graph 原生动态并行分支；
- PostgreSQL 演示分析库、指标目录、只读视图和 SQL 安全门禁；
- 自然语言分析计划、真实 SQL、图表、结果表和数据结论；
- 失败、超时、中断、证据不足和部分专家失败展示；
- 自动化离线测试、选择性在线 Smoke 测试和展台验收脚本。

### 5.2 不包含

- 唤醒词、持续监听、声纹或说话人识别；
- 离线 ASR/LLM/TTS 降级；
- 设备控制、门禁控制、自动工单批准或其他高风险写操作；
- 原始视频、图片、人脸、身份记录或未脱敏安防材料；
- 任意数据库写入、任意表访问或面向生产库的自由 SQL；
- DataAgent 完整平台、Python 沙箱、A2A 分布式部署；
- 生产级身份认证、租户隔离、持久化会话和高可用部署；
- 与三个 P1 无关的目录重构或现有功能重写。

## 6. 总体架构

```text
┌─────────────────────────────────────────────────────────────┐
│ Vue 统一工作台                                               │
│ 实时语音 | 专家协作 | 运营分析 | ExecutionTraceRail          │
└──────────────┬────────────────┬────────────────┬────────────┘
               │ WebSocket      │ REST + SSE     │ REST + SSE
               ▼                ▼                ▼
┌──────────────────┐ ┌────────────────────┐ ┌──────────────────────┐
│ VoiceSession      │ │ ExpertCollaboration│ │ OperationsAnalysis   │
│ Orchestrator      │ │ Service            │ │ Workflow             │
└────────┬─────────┘ └─────────┬──────────┘ └──────────┬───────────┘
         │                     │                       │
         │          ┌──────────▼──────────┐            │
         │          │ Supervisor ReactAgent│            │
         │          │ + 3 Expert ReactAgent│            │
         │          └──────────┬──────────┘            │
         ▼                     ▼                       ▼
┌─────────────────────────────────────┐  ┌─────────────────────────┐
│ 现有园区 Port 与只读领域工具          │  │ PostgreSQL 分析只读视图  │
│ Alert / Energy / Device / Security  │  │ MetricCatalog / SqlGuard │
│ Knowledge / WorkOrder(read-only)    │  └─────────────────────────┘
└──────────────────┬──────────────────┘
                   ▼
┌─────────────────────────────────────────────────────────────┐
│ DashScope：Streaming ASR / Chat / Embedding / Streaming TTS  │
└─────────────────────────────────────────────────────────────┘
```

三个场景共享业务 Port、模型配置、安全事件、审计和 UI 轨迹，但不强制使用同一种传输：语音需要双向 WebSocket 和二进制音频，专家协作与运营分析继续使用可重放 SSE。

## 7. 通用执行事件

### 7.1 公共契约

新增 `ExecutionEvent`，字段固定为：

```text
eventId
runId
sequence
timestamp
scenario
actor
stage
eventType
status
safeSummary
displayPayload
```

- `scenario`：`VOICE`、`EXPERT_COLLABORATION`、`OPERATIONS_ANALYSIS`、`ALERT_WORKFLOW`；
- `actor`：系统、Supervisor、领域专家、工具或数据库等公开身份；
- `eventType` 和 `status` 使用枚举；
- `displayPayload` 是按事件类型区分的封闭 DTO 联合，不接受任意 `Map<String,Object>`；
- 每个 `runId` 的 `sequence` 严格递增；
- `safeSummary` 和 payload 在服务端完成白名单与脱敏，前端不负责过滤敏感内容。

### 7.2 兼容策略

- 保留现有 `WorkflowEvent`、告警 SSE 和公开 DTO；
- 新增适配器将告警 `WorkflowEvent` 映射成 `ExecutionEvent` 供统一轨迹栏使用；
- 不在第一阶段删除旧类型或修改旧 API；
- 通用发布器与存储接口独立于 Web 层，避免适配器或 Agent 依赖 Controller DTO。

### 7.3 失败事件

失败 payload 只包含：

```text
stage
errorCode
retryable
safeMessage
```

不得包含供应商响应正文、密钥、SQL 连接串、堆栈、Prompt、思维链或未脱敏工具结果。

## 8. 统一工作台

采用已确认的“场景页 + 统一轨迹栏”布局：

- 顶部：产品标识、在线状态、模型/数据能力状态；
- 主导航：实时语音、专家协作、运营分析；
- 中间主舞台：当前场景的输入、执行状态和结果；
- 右侧轨迹栏：按真实后端事件显示角色、阶段、时间和安全摘要；
- 失败发生时在原位置标红，并提供明确的人工重试按钮；
- 不显示模型思维链；展示的是任务、工具、证据、结构化结果和状态。

现有告警工作流和客服能力继续保留：专家协作复用告警/审批资产，语音助手复用客服知识和园区只读工具。旧页面可以暂时保留入口，待三个 P1 稳定后再单独评审是否合并。

## 9. 实时语音园区助手

### 9.1 交互流程

```text
IDLE
  → LISTENING
  → ASR_FINALIZED
  → REASONING / TOOL_CALLING
  → ANSWER_STREAMING
  → SPEAKING
  → IDLE
```

用户点击开始后才获取麦克风并发送音频；再次点击结束当前输入。播放回答时点击麦克风会取消当前 TTS 流，发布 `INTERRUPTED` 事件，并在同一会话开始下一轮。

### 9.2 传输

1. `POST /api/voice/sessions` 创建会话，返回 `sessionId` 和 WebSocket 地址；
2. 单个 WebSocket 承载控制 JSON、服务端事件和双向二进制音频帧；
3. 控制消息包括 `START_INPUT`、`COMMIT_INPUT`、`INTERRUPT_OUTPUT` 和 `CLOSE_SESSION`；
4. 音频帧与 JSON 事件不混入通用 SSE；
5. 浏览器使用 `AudioWorklet` 采集，后端适配为 DashScope Streaming ASR 所需格式；
6. TTS 音频按块返回并顺序播放，不先生成完整音频文件。

### 9.3 服务端编排

`VoiceSessionOrchestrator` 只负责会话与媒体编排，不复制业务判断：

- 告警询问调用现有告警只读工具；
- 能耗询问调用 `EnergyQueryTool`；
- 设备询问调用 `DeviceQueryTool`；
- 安防询问只能调用 `SecurityQueryTool` 的脱敏摘要；
- 停车政策调用客服知识检索，不调用 SQL；
- 设施报修仍转人工，不允许语音 Agent 自动创建或批准处置。

模型答案必须通过引用和工具结果校验后才能送入 TTS。可以按完整安全句子分段启动 TTS，不能把未经校验的 token 直接播出。

### 9.4 可见事件

- 会话创建、麦克风授权、输入开始；
- `ASR_PARTIAL`、`ASR_FINAL`；
- 意图识别；
- 工具开始、完成、失败；
- 文本回答增量；
- TTS 开始、音频流、完成；
- 中断、超时和失败。

原始音频默认不落盘。P1 只在内存保存当前会话的最终文本、安全工具引用和公开事件，应用重启后丢失。

## 10. 专家 Agent 协作

### 10.1 Agent 职责

| Agent | 允许工具 | 必须输出 | 禁止事项 |
| --- | --- | --- | --- |
| 能耗专家 | `EnergyQueryTool`、能耗知识查询 | 基线偏差、时间窗口、异常证据、置信度 | 设备控制、安防推断 |
| 设备专家 | `DeviceQueryTool`、`AlertQueryTool`、只读工单查询、设备知识查询 | 设备状态、历史相关性、待核查项 | 创建工单、修改设备 |
| 安防专家 | `SecurityQueryTool`、安防知识查询 | 脱敏事件模式、风险提示、人工复核建议 | 人员身份推断、原始媒体访问 |
| Supervisor | 无领域原始工具；只接收专家契约 | 任务分解、选中专家、冲突/缺口、汇总结论 | 绕过专家直接查询领域数据 |

知识查询仍复用 `KnowledgePort`，但通过领域包装工具限制检索标签和文档域，不为每个专家复制知识库实现。

### 10.2 编排

使用 Spring AI Alibaba 2.0 Graph 的动态并行分支：

1. `supervisorPlan` 节点接收用户问题和可选告警 ID；
2. Supervisor `ReactAgent` 生成结构化任务计划、每个领域的 assignment 和 `selectedDomains`；
3. 确定性校验器拒绝未知领域、空 assignment、重复领域和超过三个专家的计划；
4. `addParallelConditionalEdges` 只进入选中的 expert 节点；
5. 多个专家通过 `RunnableConfig` 中的受控 executor 并行执行；
6. Graph 流事件和 Agent/Tool Hooks 将任务交接、Agent 开始、工具调用和 Agent 完成发布为真实事件；
7. 每个结果先通过 `ExpertFinding` 校验，再由汇合节点聚合；
8. `supervisorSynthesis` 使用同一 Supervisor 角色，只基于合法 Finding 汇总并保留冲突和证据不足。

不直接使用内置 `SupervisorAgent` 的原因不是缺少该 API，而是 2.0 发布版的 Supervisor 示例采用循环路由，不能直接证明本项目要求的“选中专家在同一轮并行执行”。这里选择更底层但官方提供的 `StateGraph.addParallelConditionalEdges`，并用契约测试锁定并行和未选中不执行两个行为。

单领域问题应显示未调度专家，而不是让所有专家返回空话。跨域展台样例应同时选中三个专家并真实并行运行。

### 10.3 交接契约

```text
ExpertFinding
  domain
  status: SUPPORTED | INSUFFICIENT_EVIDENCE | FAILED
  conclusion
  evidenceRefs[]
  confidence
  nextChecks[]
```

- `evidenceRefs` 只能引用工具返回的已知 ID；
- `confidence` 范围为 0–1；
- `conclusion` 和 `nextChecks` 有长度上限；
- Finding 不包含思维链；
- 未通过校验的 Agent 输出按该专家失败处理，不送入 Supervisor 上下文。

### 10.4 部分失败

- 一个专家失败：汇总其余合法结果并明确标注缺失领域；
- 两个专家失败：只在剩余证据足以回答局部问题时给出局部结论；
- 全部专家失败：运行失败，不生成结论；
- Supervisor 超时或输出不合法：保留各专家卡片，汇总区显示失败；
- 单个专家目标超时 15 秒，完整协作目标超时 40 秒。

## 11. 自然语言运营分析

### 11.1 数据库与指标目录

使用独立 PostgreSQL 演示数据库和专用只读运行账号。初始化账号负责建表、种子数据和视图；应用运行账号只有分析 Schema 的 `SELECT` 权限。

首期白名单视图：

| 视图 | 主要字段 | 用途 |
| --- | --- | --- |
| `analytics.v_energy_hourly` | 楼宇、仪表、时间、kWh、基线、峰值 kW | 趋势、楼宇比较、夜间异常 |
| `analytics.v_alert_fact` | 告警、楼宇、设备、类别、风险、发生时间、状态 | 告警数量与风险分析 |
| `analytics.v_device_snapshot` | 设备、楼宇、类型、状态、未关闭告警数、快照时间 | 设备健康概览 |
| `analytics.v_parking_daily` | 日期、停车区、进出量、峰值占用、容量、利用率 | 停车运营分析 |

首期指标：

- `energy_kwh`
- `night_energy_kwh`，夜间固定定义为 22:00–06:00
- `energy_deviation_pct`
- `alert_count`
- `high_risk_alert_count`
- `device_offline_count`
- `parking_entries`
- `parking_utilization_pct`

指标目录是口径唯一来源。模型不能临时发明“夜间”“异常率”或“利用率”的计算方式。

### 11.2 工作流

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

问题缺少时间、范围或指标口径时，工作流进入 `NEEDS_CLARIFICATION`，不生成 SQL。多轮追问只保存结构化口径选择和安全文本。

### 11.3 SQL 安全门禁

安全不能依赖 Prompt。SQL 必须同时通过以下门禁：

1. AST 只能包含一条 `SELECT` 或只读 CTE；
2. 禁止 DDL、DML、事务、过程调用、`SELECT INTO`、系统目录和跨 Schema 访问；
3. 所有表必须属于白名单分析视图；
4. 函数、聚合和日期操作采用允许列表；
5. 时间边界等确定性参数使用绑定参数；
6. 强制最大 500 行和最大 1 MiB 结果；
7. 数据库连接设置只读事务和 3 秒 statement timeout；
8. PostgreSQL `EXPLAIN (FORMAT JSON)` 估算成本超过阈值时拒绝执行；
9. SQL 门禁失败最多允许模型修复一次，第二次失败即终止；
10. 数据库账号本身没有写权限，作为最终硬边界。

### 11.4 图表与结论

`ChartSpec` 只允许 `LINE`、`BAR` 和 `TABLE`，字段必须来自实际结果列：

```text
type
title
xField
yFields[]
seriesField
unit
```

前端使用 Apache ECharts 渲染。结论模型只接收查询问题、指标口径、列名、行数、聚合后的有界结果和数据时间戳，不接收无限结果或数据库连接信息。

- 查询为空：显示“无数据”，不生成趋势结论；
- SQL 被拦截：显示具体安全阶段和可重试提示；
- 图表不适用：退回表格，不伪造图表；
- 结论生成失败：保留 SQL、结果表和查询元数据；
- 所有数字必须能回到结果列。

## 12. API 草案

### 12.1 语音

- `POST /api/voice/sessions`
- `GET /api/voice/sessions/{sessionId}`
- `WS /ws/voice/sessions/{sessionId}`

### 12.2 专家协作

- `POST /api/expert-collaboration/runs`
- `GET /api/expert-collaboration/runs/{runId}`
- `GET /api/expert-collaboration/runs/{runId}/events`

### 12.3 运营分析

- `POST /api/operations-analysis/runs`
- `POST /api/operations-analysis/runs/{runId}/clarifications`
- `GET /api/operations-analysis/runs/{runId}`
- `GET /api/operations-analysis/runs/{runId}/events`

创建接口返回 `202 Accepted`、`runId` 和状态地址。状态 DTO 和 SSE payload 共享公开模型，但 SSE 仍按具名事件发送。所有输入设置长度上限；幂等键沿用当前项目的请求重试策略。

## 13. 包与所有权边界

建议新增以下有明确所有权的包，不移动无关现有代码：

```text
com.example.smartpark.execution
  ExecutionEvent / ExecutionEventPublisher / public payloads

com.example.smartpark.voice
  VoiceSessionOrchestrator / WebSocket protocol / ASR-TTS adapters

com.example.smartpark.collaboration
  Supervisor configuration / expert agents / ExpertFinding / run store

com.example.smartpark.analytics
  MetricCatalog / SchemaCatalog / QueryPlan / SqlGuard / QueryExecutor / ChartSpec

com.example.smartpark.web
  new REST, SSE and WebSocket adapters only
```

领域 Port 仍是园区业务数据唯一来源。Analytics 只能查询专用分析视图，不能绕过 Port 修改现有业务数据。Web 层不能被 Agent、Port 或 adapter 反向依赖。

## 14. 配置与密钥

新增配置只接受环境变量覆盖，不提交凭证：

```yaml
smartpark:
  voice:
    enabled: true
    input-timeout: 10s
    agent-timeout: 15s
    tts-first-chunk-timeout: 5s
  collaboration:
    enabled: true
    expert-timeout: 15s
    run-timeout: 40s
    max-parallel-experts: 3
  analytics:
    enabled: true
    statement-timeout: 3s
    max-rows: 500
    max-result-bytes: 1048576
```

数据库 URL、用户名和密码从环境变量读取。应用运行账号必须只读；Schema 初始化使用独立的初始化流程，不复用应用凭证。

## 15. 错误处理

| 阶段 | 处理 |
| --- | --- |
| 麦克风权限拒绝 | 前端停止创建输入流，显示浏览器权限说明 |
| ASR 失败或超时 | 保留 partial 文本，发布失败事件，允许人工重试 |
| 模型失败 | 标注 Agent 阶段；不调用 TTS，不生成默认回答 |
| 工具失败 | 显示工具名和安全错误码；由业务契约决定证据不足或整轮失败 |
| TTS 失败 | 保留完整文字答案，语音区显示失败，不播放本地预录音 |
| 专家部分失败 | 按 10.4 节处理，并保留每个专家状态 |
| SQL 需要澄清 | 返回结构化澄清问题，不执行数据库 |
| SQL 被拦截 | 最多修复一次，之后终止并保留被拒阶段，不公开危险 SQL 细节 |
| SQL 查询超时 | 取消查询，连接回收，显示超时 |
| 图表/结论失败 | 保留已验证 SQL 和结果，失败阶段独立展示 |

## 16. 测试策略

### 16.1 2.0 升级门禁

- Maven Enforcer 或等价依赖测试禁止 Spring AI 1.x；
- 当前全部 Java 测试与 UI 构建通过；
- Graph、Tool、RAG、MCP 与 SSE 兼容测试通过；
- Supervisor 计划、动态并行分支、汇合和未选中专家不执行的最小集成测试通过；
- DashScope 语音 Bean 装配测试通过。

### 16.2 单元与契约测试

- `ExecutionEvent` 序列、公开 payload 和脱敏；
- Voice 控制协议、状态机、中断和乱序音频拒绝；
- 每个专家的工具集合快照测试，防止职责漂移；
- `ExpertFinding` 解析、引用、置信度和长度校验；
- Supervisor 无领域工具的架构测试；
- MetricCatalog 口径测试；
- SQL AST 门禁的允许与拒绝语料；
- ChartSpec 只能引用真实列；
- 空结果和失败不得生成数字结论。

### 16.3 数据库集成测试

使用 PostgreSQL Testcontainers：

- 初始化四个分析视图和确定性种子数据；
- 以真实只读账号执行；
- 验证 DDL/DML、系统表、跨 Schema、超大结果和超时被拒绝；
- 验证常用能耗、告警、设备和停车问题产生正确结果；
- 验证连接在取消和超时后正常回收。

### 16.4 Web 与 UI 测试

- WebSocket 二进制帧、JSON 控制事件和断线清理；
- SSE 重放、具名事件、终态关闭和断线恢复；
- Vue 组件测试覆盖三页主要状态；
- Playwright 覆盖语音权限拒绝、专家部分失败、SQL 澄清和正常结果展示；
- UI 测试使用测试替身，不冒充在线验收。

### 16.5 在线 Smoke 与展台验收

在线测试必须显式提供 DashScope Key 并单独执行：

- 固定短音频验证 ASR 返回 final 文本；
- 固定园区问题验证真实工具调用；
- TTS 返回非空可播放音频；
- 跨域问题验证三个专家被真实调度；
- 自然语言问题生成并执行只读 SQL。

在线测试失败必须报告真实阶段，不能自动改走测试替身。

## 17. 验收标准

### 17.1 实时语音

- 录音期间持续显示增量识别文本；
- 告警、能耗和停车问题分别调用正确真实工具；
- 显示文字流、工具轨迹和 TTS 音频流；
- 播放期间可以人工打断；
- 原始音频默认不落盘；
- 展前连续 10 次在线运行至少 9 次完成；
- 中位首段识别不超过 1.5 秒，完整回答目标 10 秒内。

### 17.2 专家协作

- 单领域问题只调度相关专家；
- 跨域样例真实并行调用三个专家；
- 专家工具权限和 `ExpertFinding` 契约通过自动测试；
- UI 显示真实交接、工具、完成、失败和汇总事件；
- 部分失败明确显示证据缺口；
- 完整跨域分析目标 40 秒内。

### 17.3 运营分析

- 能耗、告警、设备和停车四类问题均从真实分析视图返回结果；
- UI 展示口径、计划、实际 SQL、安全门禁、耗时、行数、图表和结论；
- 含糊问题先追问；
- 危险 SQL、无数据和查询失败均不生成数字结论；
- 常规分析目标 20 秒内完成。

## 18. 独立交付切片

1. **Spring AI Alibaba 2.0 兼容升级**：只更新依赖和兼容代码，恢复现有全部验证。
2. **通用事件底座**：新增 `ExecutionEvent`、发布器、兼容适配器和前端轨迹栏。
3. **分析数据底座**：PostgreSQL 视图、指标目录、只读账号和 SQL 安全门禁。
4. **运营分析后端**：复用官方 SQL Agent Graph 模式，完成计划、SQL、执行和 ChartSpec。
5. **运营分析 UI**：自然语言输入、计划、SQL、图表、表格、结论和错误状态。
6. **专家契约与权限**：三个 Agent、工具集合测试、`ExpertFinding` 和知识领域包装。
7. **Supervisor 协作**：Supervisor 计划/汇总、Graph 动态并行分支、Hooks 和部分失败。
8. **专家协作 UI**：Agent 卡片、并行状态、交接轨迹、证据和汇总。
9. **语音媒体链路**：WebSocket、AudioWorklet、Streaming ASR、文本回答和 Streaming TTS。
10. **语音 UI 与中断**：会话状态、partial 文本、工具卡片、播放和打断。
11. **展台硬化**：在线 Smoke、Playwright、连续运行、时延测量和演示脚本。

每个切片独立测试、独立提交，不同时清理无关代码。升级、自动化测试、在线 Smoke 和展台业务验收必须分别报告。

## 19. 参考实现

- [Spring AI Alibaba](https://github.com/alibaba/spring-ai-alibaba)
- [Spring AI Alibaba Maven Central BOM](https://central.sonatype.com/artifact/com.alibaba.cloud.ai/spring-ai-alibaba-bom)
- [Spring AI Alibaba Extensions releases](https://github.com/spring-ai-alibaba/spring-ai-extensions/releases)
- [Spring AI Alibaba 2.0 MultiAgent 示例](https://github.com/alibaba/spring-ai-alibaba/blob/v2.0.0-M1.1/examples/documentation/src/main/java/com/alibaba/cloud/ai/examples/documentation/framework/advanced/MultiAgentExample.java)
- [Spring AI Alibaba 2.0 并行分支示例](https://github.com/alibaba/spring-ai-alibaba/blob/v2.0.0-M1.1/examples/documentation/src/main/java/com/alibaba/cloud/ai/examples/documentation/graph/examples/ParallelBranchExample.java)
- [SQL Agent Workflow 示例（主分支模式参考，移植时使用 2.0 API）](https://github.com/alibaba/spring-ai-alibaba/tree/main/examples/multiagent-patterns/workflow)
- [Spring AI Alibaba DataAgent](https://github.com/spring-ai-alibaba/DataAgent)
- [Apache ECharts](https://echarts.apache.org/)
- [JSqlParser](https://github.com/JSQLParser/JSqlParser)
