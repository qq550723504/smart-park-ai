# 智慧园区只读 MCP Server 设计

## 1. 背景与目标

当前项目已经通过 Spring AI 工具向内部告警诊断 Agent 提供告警、能耗、知识、设备和工单等能力，也通过 REST 与 SSE 暴露工作流和客服场景。现有内部工具契约面向模型调用：部分 Bean 受 DashScope 开关控制，告警结果包含内部摘要和证据，知识检索结果包含完整正文。因此不能直接把现有 `@Tool` Bean 注册成公开 MCP 工具。

本次建设一个与模型无关、默认关闭的只读 MCP Server，使标准 MCP 客户端可以发现并调用三个园区查询工具，展示标准协议接入能力。实现复用现有只读 Port，不复制 Mock 数据或检索逻辑，也不改变现有 Agent、工作流、REST、SSE 或前端行为。

成功标准：

- MCP Inspector 可以通过无状态 Streamable HTTP 连接 `/mcp`。
- 客户端只能发现告警、能耗和知识三个只读工具。
- DashScope 关闭且没有模型 API Key 时，Mock 模式下三个工具仍可调用。
- MCP 返回结果不包含知识正文、告警摘要、告警证据、诊断、审批、人员信息或设备控制能力。
- README 提供经过实际验证的 MCP Inspector 演示步骤和 Codex 连接配置。

## 2. 范围

### 2.1 本次包含

- 在现有 Spring Boot 应用中加入 Spring AI WebMVC MCP Server Starter。
- 使用无状态 Streamable HTTP，在现有应用地址的 `/mcp` 提供服务。
- 新增独立 `adapter.mcp` 入站适配器及 MCP 专用安全返回类型。
- 通过 `AlertPort`、`EnergyPort` 和 `KnowledgePort` 读取当前适配器数据。
- 提供默认关闭的功能开关。
- 增加工具契约、数据泄漏、Spring 装配、架构和协议集成测试。
- 补充本地演示与客户端配置文档。

### 2.2 本次不包含

- MCP Client，不让园区 Agent 连接外部 MCP Server。
- STDIO、旧式 SSE 或有状态 Streamable HTTP 传输。
- 告警列表、告警历史、设备、安防事件、工作流状态或客服查询工具。
- 审批、工单创建、知识修改、设备控制等写工具。
- MCP Resources、Prompts、Sampling、Elicitation 或服务端到客户端消息。
- 生产认证、租户隔离、速率限制和公网部署。
- 独立 MCP 微服务或 Maven 多模块拆分。

## 3. 方案选择

### 3.1 采用：独立 MCP 适配器复用现有 Port

```text
MCP Client
  -> Spring AI Stateless MCP Server
  -> MethodToolCallbackProvider
  -> SmartParkMcpTools
  -> AlertPort / EnergyPort / KnowledgePort
  -> Mock 或 RAG Adapter
```

MCP 适配器拥有独立的公开 DTO 和输入校验。它只依赖只读 Port 与必要的公开领域枚举，不依赖内部 Agent 工具或 Web DTO。

优点：

- 数据与检索逻辑仍只有一个来源。
- Agent、REST 和 MCP 可以分别演进自己的兼容契约。
- MCP 不受 DashScope Bean 条件控制。
- 替换 Mock 或 RAG Adapter 时不需要重写 MCP 工具。

### 3.2 不采用：直接暴露现有 Agent 工具

现有 Agent 工具会把完整 `Alert`、`KnowledgeDocument` 等领域对象放入结果，可能公开内部摘要、证据和知识正文；这些工具还受 DashScope 配置影响。直接复用会把内部 Prompt 工具契约变成外部兼容性承诺，并绕过现有公开数据边界。

### 3.3 不采用：独立 MCP 微服务

独立服务隔离更强，但第一期会重复端口装配、进程管理和演示配置，并引入两个进程之间的 Mock 数据一致性问题。当前只读本地演示不需要该复杂度。

## 4. 组件与依赖边界

新增包：

```text
com.example.smartpark.adapter.mcp
├─ SmartParkMcpTools
├─ SmartParkMcpConfiguration
└─ McpToolResults
```

职责：

- `SmartParkMcpTools`：输入规范化、长度限制、调用只读 Port、错误映射和安全 DTO 转换。
- `SmartParkMcpConfiguration`：按功能开关创建工具对象和 `MethodToolCallbackProvider`。
- `McpToolResults`：定义 MCP 专用请求结果、错误码、告警视图、能耗视图和知识元数据视图。

依赖规则：

- `adapter.mcp` 可以依赖 `port.alert.AlertPort`、`port.energy.EnergyPort`、`port.knowledge.KnowledgePort` 以及转换所需的领域只读类型。
- `adapter.mcp` 不得依赖 `web`、`agent`、`workflow`、`audit`、`feedback`、`KnowledgeAdminPort`、`WorkOrderPort` 或任何 Mock/RAG 具体实现。
- MCP 返回类型不得直接嵌入 `Alert`、`KnowledgeDocument`、`SecurityEvent`、`Diagnosis`、`ApprovalDecision` 或 `WorkOrder`。
- 工具调用不写业务状态，也不创建具有虚假身份的审计记录。日志只记录工具名、成功或失败及异常类型，不记录参数或结果正文。

## 5. 公开工具契约

第一期固定提供三个工具。工具名是外部兼容性契约；后续修改参数或结果时必须进行兼容性评估。

### 5.1 `smartpark_lookup_alert`

输入：

```json
{
  "alertId": "ALT-ENERGY-001"
}
```

`alertId` 必填，去除首尾空白后必须匹配 `ALT-[A-Z0-9-]{1,120}`。

成功数据只包含：

- `alertId`
- `parkId`
- `buildingId`
- `deviceId`
- `classification`
- `riskHint`
- `occurredAt`

不返回 `summary` 或 `evidence`。安防类型告警也不会通过本工具返回安防事件摘要、原始媒体、身份或门禁记录。

### 5.2 `smartpark_lookup_energy`

输入：

```json
{
  "meterId": "DEV-ENERGY-001"
}
```

当前 `EnergyPort` 使用能耗设备 ID 作为表计 ID。`meterId` 必填，去除首尾空白后必须匹配 `DEV-[A-Z0-9-]{1,120}`。

成功数据包含：

- `meterId`
- `parkId`
- `buildingId`
- `measuredAt`
- `currentKwh`
- `baselineKwh`
- `peakDemandKw`
- `varianceKwh`
- `varianceRatio`

偏差值和比例由领域读数确定性计算；本工具不提供设备控制、节能策略下发或写入能力。

### 5.3 `smartpark_search_knowledge`

输入：

```json
{
  "query": "energy",
  "domain": "ALERT_OPERATIONS"
}
```

`query` 必填、去除首尾空白，长度不得超过 500 个字符。`domain` 必填且只允许：

- `CUSTOMER_SERVICE`
- `ALERT_OPERATIONS`

工具调用 `KnowledgePort.rankedSearch`，适配器层无论底层返回多少结果，都只保留排序后的前 5 条。每条结果只包含：

- `documentId`
- `title`
- `domain`
- `tags`
- `score`
- `updatedAt`

不返回正文、正文片段、Embedding 输入或向量数据。

### 5.4 统一结果结构

每个工具返回自己的强类型记录，但顶层语义统一：

```json
{
  "ok": true,
  "data": {},
  "error": null,
  "notice": "Mock park data only. Read-only; no device control."
}
```

失败时：

```json
{
  "ok": false,
  "data": null,
  "error": {
    "code": "NOT_FOUND",
    "message": "Requested park record was not found."
  },
  "notice": "Mock park data only. Read-only; no device control."
}
```

允许的稳定错误码：

- `INVALID_ARGUMENT`
- `NOT_FOUND`
- `INTERNAL_ERROR`

错误消息是固定安全文本，不包含输入值、Port 异常消息、堆栈或内部实现名称。知识无匹配是成功的空结果，不是 `NOT_FOUND`。

## 6. 运行配置与传输

应用继续使用 Spring MVC。新增 `spring-ai-starter-mcp-server-webmvc`，不引入 WebFlux。配置使用一个业务开关作为来源：

```yaml
smartpark:
  mcp:
    enabled: ${SMARTPARK_MCP_ENABLED:false}

spring:
  ai:
    mcp:
      server:
        enabled: ${smartpark.mcp.enabled}
        protocol: STATELESS
        type: SYNC
        name: smart-park-readonly
        version: 0.1.0
        streamable-http:
          mcp-endpoint: /mcp
```

Spring AI 1.1.2 的 `STATELESS` 协议仍复用 `spring.ai.mcp.server.streamable-http` 传输属性；不要使用后续文档版本中可能出现的 `stateless.mcp-endpoint` 前缀。

`SmartParkMcpConfiguration` 使用同一个 `smartpark.mcp.enabled` 条件创建工具 Provider。默认值为 `false`；关闭时既不注册三个工具，也不暴露 MCP 端点。启用 MCP 不要求启用 DashScope，也不要求模型 API Key。

无状态模式不保存 MCP 会话，不支持 Sampling、Elicitation 或服务端主动消息。本次所有工具均为短时同步只读调用，符合该模式。

## 7. 数据流

以知识检索为例：

```text
MCP tools/call
  -> Spring AI 校验 JSON Schema 并绑定 query/domain
  -> SmartParkMcpTools 规范化输入
  -> KnowledgeDomain 枚举校验
  -> KnowledgePort.rankedSearch
  -> 限制前 5 条
  -> 只映射公开元数据
  -> Spring AI 序列化 MCP 工具结果
```

告警和能耗采用同样路径。MCP 工具不通过现有 Web Controller，也不调用内部 Agent 工具；它们与这些入口并列依赖 Port。

## 8. 安全边界

- 当前项目没有生产认证，因此 README 必须明确 MCP 仅限本地可信环境，不能直接暴露到公网。
- 默认关闭 MCP；启用必须是显式运行时选择。
- 第一阶段不自制 API Key 认证。生产认证应作为独立切片，使用标准 OAuth2 Resource Server、权限和租户策略。
- MCP 不读取或返回 API Key、Prompt、完整模型响应、诊断、审批、工单、知识正文、告警摘要、告警证据、安防事件或人员数据。
- MCP 工具只依赖只读 Port；工具名称、描述和服务说明均明确“Mock、只读、无设备控制”。
- DTO 白名单映射是字段边界的唯一来源，禁止使用领域对象的通用 JSON 序列化作为 MCP 返回值。
- 输入和输出均有界：参数长度受限、知识结果最多 5 条、列表字段沿用领域公开元数据限制。

## 9. 错误处理

- 空白、过长、格式非法的输入映射为 `INVALID_ARGUMENT`。
- Port 表示未知告警或表计的已知业务异常映射为 `NOT_FOUND`。
- 知识检索没有匹配时返回 `ok=true` 和空列表。
- 未预期运行时异常映射为 `INTERNAL_ERROR`；客户端不接收原始异常消息。
- 服务端日志只记录工具名、结果类型和异常类，不记录查询字符串、标识符、知识内容或返回结果。
- MCP 协议初始化或无效 JSON-RPC 由官方 Starter/SDK 处理；业务代码不自行实现协议解析。

## 10. 测试策略

### 10.1 工具单元测试

- 三个工具的成功调用和安全 DTO 映射。
- 空参数、过长参数、格式非法参数、非法领域和未知 ID。
- 知识结果排序后最多保留 5 条。
- 能耗偏差值和偏差比例计算正确。
- 业务异常和未知异常使用固定错误码与固定安全消息。

### 10.2 数据泄漏契约测试

序列化 MCP 返回结果，并断言不存在知识 `content`、告警 `summary`/`evidence`、安防摘要、人员字段、诊断、审批、工单、Prompt 或密钥字段。使用反射锁定 MCP DTO 的字段白名单，防止领域模型未来新增字段后意外外发。

### 10.3 Spring 装配与架构测试

- 默认配置不注册 MCP `ToolCallbackProvider`，且 `/mcp` 不可用。
- `SMARTPARK_MCP_ENABLED=true` 且 DashScope 关闭时，应用上下文和 MCP Server 正常启动。
- MCP Provider 精确注册三个工具，名称稳定且无写工具。
- 架构扫描验证 `adapter.mcp` 的允许依赖与禁止依赖。
- 现有 REST、SSE、Agent 和工作流测试继续通过。

### 10.4 协议集成测试

使用官方 MCP Java SDK 连接随机端口，不手写 JSON-RPC 客户端。测试执行：

```text
initialize
  -> tools/list
  -> 精确发现三个工具
  -> tools/call
  -> 校验结构化成功与失败结果
```

集成测试必须完全离线，不调用 DashScope 或外部 MCP 服务。

## 11. 演示与文档验收

本地启动：

```powershell
$env:SPRING_AI_DASHSCOPE_ENABLED = 'false'
$env:SMARTPARK_MCP_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

README 补充：

- MCP 功能、默认关闭原因和本地可信环境限制。
- 使用官方 MCP Inspector 连接 `/mcp` 的经过验证步骤。
- 与当前 Codex 配置格式一致的经过验证连接示例。
- 三个工具的输入示例、返回字段和明确不公开字段。
- 关闭或清理当前 PowerShell 环境变量的命令。

现场演示顺序：

1. Inspector 连接服务并显示三个工具。
2. 调用 `smartpark_lookup_alert` 查询 `ALT-ENERGY-001`。
3. 使用返回的业务场景调用 `smartpark_lookup_energy` 查询演示表计。
4. 调用 `smartpark_search_knowledge` 搜索 `ALERT_OPERATIONS` 知识。
5. 展示返回中没有正文、告警证据和任何写工具。
6. 使用 README 配置从 Codex 发现并调用同一组工具。

最终验证：

- Maven 全量测试通过。
- Maven 打包通过。
- 前端类型检查与构建通过，证明后端依赖变化没有破坏现有 UI。
- `git diff --check` 通过。

## 12. 后续独立切片

以下能力需要重新设计和授权，不属于本次实现：

- 标准 OAuth2 认证、工具级权限和租户隔离。
- 受控知识正文读取 MCP Resource。
- 告警历史、工作流状态和其他只读工具。
- MCP 调用指标、速率限制和完整可观测性。
- 园区 Agent 作为 MCP Client 接入外部工具。
- 独立 MCP 服务部署和生产网络边界。
