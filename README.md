# 智慧园区 AI 工作流示例

这是一个基于 Spring Boot 4、Spring AI Alibaba 2.0 和 Vue 3 的智慧园区示例项目。它把告警处置、能耗分析、安防事件复核、园区客服、知识管理和运营审计串成可运行的垂直切片。

> **版本与真实链路要求：** 当前基线为 Spring Boot `4.0.0` + Spring AI `2.0.0-M1` + Spring AI Alibaba `2.0.0-M1.1`，属于 milestone 预发布版本。默认 Compose 栈以 Mock/离线模式运行；体验告警工作流等在线真实模型能力时，必须显式启用 DashScope 并配置有效的 API Key。默认自动化测试不访问外网。

项目默认使用内存 Mock 数据，适合本地体验和架构学习。告警工作流可以接入 DashScope `qwen-plus`；客服检索与回答则可以分别在 Mock 和 DashScope/RAG 实现之间切换。

> **请先了解安全边界：** 本项目不是生产控制系统。Mock 适配器不会检查、切换、重启、隔离或控制真实设备；安防数据只有脱敏摘要，不包含原始视频、图片、人脸特征或身份证等人员原始记录。项目当前也没有生产级认证、租户隔离或持久化能力。

## 你可以体验什么

| 场景 | 当前实现 |
| --- | --- |
| 告警处置 | Spring AI Alibaba `StateGraph`、结构化诊断、风险门禁、人工审批、Mock 工单和 SSE 事件 |
| 能耗分析 | 只读能耗工具、基线偏差和峰值功率分析 |
| 安防复核 | 只读安防工具、`REDACTED:` 脱敏摘要和强制人工审批 |
| 园区客服 | 停车、访客、能耗问答，设施报修与知识不足时转人工 |
| 知识管理 | 按客服与告警领域隔离的 Mock 检索或进程内向量 RAG |
| 运营演示 | 角色边界、指标、审计、反馈和一次性故障注入 |
| 专家协作 | Supervisor 动态分派领域专家，并行分析、展示证据和汇总结论；需满足在线能力开关 |
| 运营分析 | 自然语言转真实只读 PostgreSQL 分析，展示查询结果、图表和结论；需显式启用分析链路 |
| 停车与能耗运营看板 | 以受控问题入口组织停车、能耗和空间指标，点击后复用运营分析只读查询；需显式启用分析链路 |
| 实时语音 | 选择性启用的全场景演示模式；需完成在线预检后再进行浏览器端人工语音验收 |
| AI 治理概览 | 场景就绪度、能力模式、运营计数和安全边界；管理员可查看审计明细 |
| AI 智能协同中心 | 只读聚合告警工作流与客服工单，按来源/状态筛选，并跳回原场景处理；需要 `CUSTOMER_AGENT` 或 `ADMIN` |

## 快速开始

### 1. 准备环境

- JDK 17 或更高版本
- Node.js 22（只有运行前端时需要）
- DashScope API Key（只有体验真实模型能力时需要）

后端使用仓库内置的 Maven Wrapper，不需要单独安装 Maven。

```powershell
java -version
.\mvnw.cmd --version
node --version
npm --version
```

macOS/Linux 请将 `.\mvnw.cmd` 替换为 `./mvnw`。

### 2. 启动后端

#### 方式 A：先离线启动（推荐首次运行）

离线模式不需要 API Key，可以体验园区客服、知识管理、运营指标和审计。由于告警工作流依赖聊天模型，离线模式不会注册告警工作流、审批和 SSE 接口。

Windows PowerShell：

```powershell
$env:SPRING_AI_DASHSCOPE_ENABLED = 'false'
.\mvnw.cmd spring-boot:run
```

macOS/Linux：

```bash
SPRING_AI_DASHSCOPE_ENABLED=false ./mvnw spring-boot:run
```

后端启动后访问 <http://localhost:8080/api/operations/capabilities>，应看到当前知识检索和客服回答模式。

#### 方式 B：启动完整告警工作流

直接以非 Compose 方式运行后端时，完整模式会调用 DashScope `qwen-plus`。密钥只应通过当前进程的 `AI_DASHSCOPE_API_KEY` 环境变量传入，不要持久化到源码、命令行参数或 Git 历史。选择性全场景 Docker Compose 演示是刻意的例外：它从 gitignored 的本地 `.env` 读取密钥，见[选择性启用全场景演示](#选择性启用全场景演示)。

Windows PowerShell：

```powershell
$secureDashScopeKey = Read-Host 'DashScope API key' -AsSecureString
$env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureDashScopeKey).Password
$env:SPRING_AI_DASHSCOPE_ENABLED = 'true'
.\mvnw.cmd spring-boot:run
```

macOS/Linux：

```bash
read -rsp 'DashScope API key: ' AI_DASHSCOPE_API_KEY && echo
export AI_DASHSCOPE_API_KEY
export SPRING_AI_DASHSCOPE_ENABLED=true
./mvnw spring-boot:run
```

### Agent 客户演示目录

后端启动后，可读取当前进程的客户演示场景目录：

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/api/showcase/scenarios | Select-Object -ExpandProperty Content
```

`READY` 只表示候选场景已在**同一进程**内完成最近一次显式在线验证，并留下仍在有效期内的成功收据；它不表示仅凭配置项、能力开关或 Bean 存在就已在线可用。新启动的进程没有历史收据，因此已启用但尚未验证的场景会正确返回 `NOT_READY`。面向客户演示前，操作人员必须先运行后续提供的显式在线预检，并仅在每个候选场景实际完成后由预检调用 `ScenarioVerificationRegistry` 记录结果。

这个只读目录接口本身不会调用模型或供应商，也不会探测或改变场景就绪状态。

### 3. 启动前端

保持后端运行，打开第二个终端：

```bash
cd ui
npm ci
npm run dev
```

访问 <http://localhost:5173>。Vite 会把 `/api` 请求代理到 <http://localhost:8080>。

前端支持切换查看者、操作员、审批人、客服坐席和管理员角色。`X-Demo-Role` 只是本地演示授权边界，不是生产认证方案。

### 4. 完成第一次调用

离线模式和完整模式都可以调用 Mock 客服：

```bash
curl -X POST "http://localhost:8080/api/customer-service/sessions" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: first-customer-question" \
  --data '{"question":"访客停车怎么收费？"}'
```

Windows PowerShell 中请使用 `curl.exe`。设施报修问题（例如“`A1 洗手间漏水，需要报修`”）会返回 `needsHuman: true` 并创建内存客服工单。

如果后端使用完整模式，再启动一个告警工作流：

```bash
curl -X POST "http://localhost:8080/api/alerts/ALT-TEMP-001/workflows"
```

保存响应中的 `workflowId`，然后查询状态：

```bash
curl "http://localhost:8080/api/workflows/replace-with-workflow-id"
```

## Docker Compose 本地演示

以下 Compose 配置仅用于本地演示，默认使用 Mock/离线能力，不需要 API Key。它不提供生产级认证、租户隔离或密钥管理；`X-Demo-Role` 仅是演示授权边界，不能作为生产认证方案。

直接启动默认栈：

```powershell
docker compose --env-file .env.example up --build
```

默认栈启动 backend、frontend 和 analytics PostgreSQL 容器。前端入口为 <http://localhost:5173>；容器内 Vite 会把 `/api` 代理到 backend，因此可用 <http://localhost:5173/api/operations/capabilities> 查看当前能力。默认模式下 backend 暴露的同一 capabilities endpoint 也可直接通过 <http://localhost:8080/api/operations/capabilities> 访问。

默认 Compose 前端端口只绑定本机回环：`127.0.0.1:5173:5173`；backend 的 `8080` 端口也只绑定本机回环。默认访问 URL 是 <http://localhost:5173> 与 <http://localhost:8080>，不会监听局域网或公网地址。前端会在容器内部代理 `/api`，因此默认不需要把 backend API 暴露给局域网。

如需让局域网其他电脑访问本地演示，必须显式设置前端绑定地址和语音 WebSocket 来源。下面示例假定宿主机局域网 IP 为 `192.168.6.246`；请替换为实际 IP：

```powershell
$env:SMARTPARK_FRONTEND_BIND_HOST="0.0.0.0"
$env:SMARTPARK_VOICE_ALLOWED_ORIGINS="http://192.168.6.246:5173,http://localhost:5173,http://127.0.0.1:5173"

docker compose --env-file .env `
  -f compose.yaml `
  -f compose.analytics.yaml `
  -f compose.showcase.yaml `
  --profile analytics up --build -d
```

其他电脑访问 <http://192.168.6.246:5173>。该 HTTP 局域网模式仅支持非语音场景：浏览器不会向非安全上下文授予麦克风权限，语音演示必须使用本机 `localhost` 或由受信任证书提供的 HTTPS 地址。该局域网模式仅适用于受信任的本地演示网络；项目本身不提供生产级认证、租户隔离或 API 访问控制。

常用生命周期命令：

```powershell
docker compose ps
docker compose logs -f
docker compose down
```

默认栈使用命名卷 `analytics-postgres-data` 保存离线演示库。`docker compose down` 不会删除它，因此普通停止/清理后其中的数据仍会保留；只有在明确需要重置默认离线演示数据时，才使用 `docker compose down -v`。

### 显式启用 analytics

analytics 使用独立 PostgreSQL 数据库；运行时查询角色固定为只读的 `smartpark_analytics_ro`，管理员角色只用于迁移和演示数据刷新。不要把真实值写进 README、源码或 Git 历史。

在本地未跟踪且已被 `.gitignore` 排除的 `.env` 中填写以下三个必需变量，仅供本地演示，绝不提交；不要在本文档、源码或 Git 历史中填写示例密钥值：

| 变量 | 用途 |
| --- | --- |
| `AI_DASHSCOPE_API_KEY` | 启用 DashScope 在线模型能力所需的 API Key |
| `SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD` | analytics 独立 PostgreSQL 的管理员密码，仅用于迁移和演示数据刷新 |
| `SMARTPARK_ANALYTICS_DB_RO_PASSWORD` | `smartpark_analytics_ro` 的只读运行时密码 |

```powershell
docker compose --env-file .env -f compose.yaml -f compose.analytics.yaml --profile analytics up --build
```

analytics overlay 会把 PostgreSQL 数据目录切换到独立命名卷 `analytics-postgres-analytics-data`，因此从默认栈切换到 analytics 栈时不需要手动修复旧卷上的认证方式。`docker compose down` 同样只会停止容器而保留该卷；如需重置 analytics 本地演示数据库，请先用不加载 analytics 凭据的基础 Compose 文件停止并删除容器，再显式删除 analytics 卷：

```powershell
docker compose -f compose.yaml down
$projectName = if ($env:COMPOSE_PROJECT_NAME) {
    $env:COMPOSE_PROJECT_NAME
} else {
    (Get-Item -LiteralPath .\compose.yaml).Directory.Name
}
docker volume rm "${projectName}_analytics-postgres-analytics-data"
```

上面的命令只删除 `analytics-postgres-analytics-data`，不会删除默认栈的 `analytics-postgres-data`，也不会读取 `compose.analytics.yaml` 中的必需凭据变量。如果启动时使用了 `docker compose -p <project-name>`，请把 `$projectName` 替换为同一个项目名。

analytics 覆盖配置会将 PostgreSQL 改为密码认证并显式开启 `SMARTPARK_ANALYTICS_DEMO_DATA_REFRESH_ENABLED=true`，让持久化本地演示库中的 V1 时间窗口夹具按小时重新锚定到当前时间。这个自动刷新只适用于本地演示，不适用于真实数据或生产环境；默认栈中的 `SMARTPARK_ANALYTICS_DEMO_DATA_REFRESH_ENABLED=false` 保持不变。默认栈中为便于无凭据离线演示而使用的 `trust` 认证仅限本地演示，不能作为生产部署建议。

### 选择性启用全场景演示

在上述同一个、已忽略的 `.env` 已包含三个必需变量后，叠加完整演示覆盖层：

```powershell
docker compose --env-file .env `
  -f compose.yaml `
  -f compose.analytics.yaml `
  -f compose.showcase.yaml `
  --profile analytics up --build
```

容器就绪后，以管理员演示角色执行预检：

```powershell
.\scripts\verify-showcase.ps1
```

验证器会向 `POST /api/showcase/preflight` 请求五个且仅五个演示场景（包括园区客服），并且只在全部返回 `READY` 和有效 `verifiedAt` 时成功；成功输出只包含 `scenarioId`、`status` 和 `verifiedAt`。`READY` 收据仅在当前进程内有效 15 分钟，应用重启或收据过期后必须重新运行预检。

告警预检从不批准或创建工单；它只验证流程是否安全地停在人工审批边界。服务端语音预检也不能替代浏览器麦克风权限确认和一次人工真实说话的完整往返验收。

## 运行模式与配置

项目把模型、知识检索和客服回答拆成三个独立开关：

| 配置项 | 默认值 | 作用 |
| --- | --- | --- |
| `SPRING_AI_DASHSCOPE_ENABLED` | `true` | 是否注册依赖 DashScope 的告警工作流和模型组件 |
| `SMARTPARK_KNOWLEDGE_MODE` | `mock` | `mock` 使用确定性内存检索；`rag` 使用 DashScope Embedding 和进程内 `SimpleVectorStore` |
| `SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE` | `mock` | `mock` 使用确定性回答；`dashscope` 使用结构化模型回答 |
| `SMARTPARK_KNOWLEDGE_MIN_SIMILARITY_SCORE` | `0.65` | RAG 结果最低相似度 |
| `SMARTPARK_CUSTOMER_MINIMUM_KNOWLEDGE_SCORE` | `0.70` | 客服接受知识结果的最低分数 |
| `SMARTPARK_ANALYTICS_ENABLED` | `false` | 是否启用真实只读 PostgreSQL 分析链路 |
| `SMARTPARK_ANALYTICS_DEMO_DATA_REFRESH_ENABLED` | `false` | 默认关闭；analytics overlay 会把它显式覆盖为 `true`，仅用于持久化本地演示库按小时重锚定 V1 的时间窗口夹具（能耗、告警、设备快照、停车）。真实数据必须保持关闭 |
| `SMARTPARK_ANALYTICS_DB_URL` | 空 | 专用分析数据库 JDBC URL；不能复用业务数据库 |
| `SMARTPARK_ANALYTICS_DB_ADMIN_USER` | 空 | 仅供 Flyway 和演示快照刷新使用的对象所有者账号 |
| `SMARTPARK_ANALYTICS_DB_ADMIN_PASSWORD` | 空 | 分析数据库对象所有者密码 |
| `SMARTPARK_ANALYTICS_DB_USER` | 空 | 运行时只读账号，固定使用 `smartpark_analytics_ro` |
| `SMARTPARK_ANALYTICS_DB_RO_PASSWORD` | 空 | 只读账号密码；Flyway 创建账号与运行时连接必须一致。含单引号的密码会被安全转义后再嵌入迁移 SQL |
| `SPRING_AI_DASHSCOPE_BASE_URL` | DashScope 官方地址 | 覆盖模型客户端地址，用于兼容网关 |
| `AI_DASHSCOPE_API_KEY` | 空 | DashScope 密钥，仅从进程环境变量读取 |

例如，同时启用 RAG 检索和 DashScope 客服回答：

```powershell
$env:SPRING_AI_DASHSCOPE_ENABLED = 'true'
$env:SMARTPARK_KNOWLEDGE_MODE = 'rag'
$env:SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE = 'dashscope'
.\mvnw.cmd spring-boot:run
```

这两个模式都需要有效的 `AI_DASHSCOPE_API_KEY` 和网络连接。RAG 索引只存在于当前进程中，应用重启后会从种子文档重新建立。

真实运营分析必须使用独立 PostgreSQL 数据库，不能与业务表或其他应用共库。数据库迁移会撤销该数据库中 `PUBLIC` 的数据库、`public`/`analytics` schema 及对象权限，再仅向 `smartpark_analytics_ro` 授予四个分析视图的 `SELECT` 权限；这是阻断 PostgreSQL 隐式公共权限旁路所必需的安全边界。管理员账号只用于迁移和演示数据刷新，应用查询始终使用只读账号。

如需使用兼容网关，只覆盖当前进程的 URL：

```powershell
$env:SPRING_AI_DASHSCOPE_BASE_URL = 'https://your-compatible-gateway.example.com'
```

## 只读 MCP 工具生态演示

MCP Server **默认关闭**，且本切片没有认证、租户隔离或生产网络边界，仅适用于可信的本地演示，禁止直接暴露到公网。关闭 DashScope 后无需模型 API Key：三个工具直接通过只读应用 Port 查询 Mock 园区适配器。服务不公开 Resources、Prompts、Completion、工作流变更或设备控制能力。

Spring AI 1.1.2 将 Java 结果信封编码为 MCP `TextContent` 中的 JSON；客户端应解析该文本 JSON，不应依赖 `structuredContent`。

启动服务：

```powershell
$env:SPRING_AI_DASHSCOPE_ENABLED='false'
$env:SMARTPARK_MCP_ENABLED='true'
$env:SERVER_ADDRESS='127.0.0.1'
.\mvnw.cmd spring-boot:run
```

| Tool | Inputs | Safe output |
|---|---|---|
| `smartpark_lookup_alert` | `alertId` | IDs, classification, risk hint, occurrence time |
| `smartpark_lookup_energy` | `meterId` | reading, baseline, peak demand, variance |
| `smartpark_search_knowledge` | `query`, `domain` | up to five metadata-only matches |

知识领域仅允许 `CUSTOMER_SERVICE` 和 `ALERT_OPERATIONS`。可使用 MCP Inspector 验证发现和调用：

```powershell
npx.cmd -y @modelcontextprotocol/inspector@2.3.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/list
npx.cmd -y @modelcontextprotocol/inspector@2.3.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/call --tool-name smartpark_lookup_alert --tool-arg alertId=ALT-ENERGY-001
npx.cmd -y @modelcontextprotocol/inspector@2.3.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/call --tool-name smartpark_lookup_energy --tool-arg meterId=DEV-ENERGY-001
npx.cmd -y @modelcontextprotocol/inspector@2.3.0 --cli http://127.0.0.1:8080/mcp --transport http --method tools/call --tool-name smartpark_search_knowledge --tool-arg query=energy --tool-arg domain=ALERT_OPERATIONS
npx.cmd -y @modelcontextprotocol/inspector@2.3.0 --web --server-url http://127.0.0.1:8080/mcp --transport http
```

Codex 可按需连接；实现过程不会修改用户全局配置：

```powershell
codex mcp add smart-park --url http://127.0.0.1:8080/mcp
codex mcp get smart-park
codex mcp remove smart-park
```

等价配置：

```toml
[mcp_servers.smart-park]
url = "http://127.0.0.1:8080/mcp"
```

停止服务后清理当前 PowerShell 环境变量：

```powershell
Remove-Item Env:SPRING_AI_DASHSCOPE_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:SMARTPARK_MCP_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:SERVER_ADDRESS -ErrorAction SilentlyContinue
```

## 内置演示数据

应用每次启动都会重置共享 Mock 内存数据。以下告警可用于完整模式：

| 告警 ID | 类型 | 风险 | 预期路径 |
| --- | --- | --- | --- |
| `ALT-TEMP-001` | HVAC 温度 | `LOW` | 知识检索与诊断；证据不足时仍可能进入审批 |
| `ALT-POWER-001` | 电力 | `HIGH` | 必须暂停并等待人工审批 |
| `ALT-ENERGY-001` | 能耗 | `HIGH` | 查询当前值、基线和峰值功率后等待审批 |
| `ALT-ACCESS-001` | 门禁安防 | `HIGH` | 只读取脱敏摘要并强制人工审批 |

工作流、事件、审批、会话、工单、反馈和审计记录都保存在内存中，应用重启后会丢失。

## 常用 API

| 方法与路径 | 用途 | 备注 |
| --- | --- | --- |
| `GET /api/operations/capabilities` | 查看当前运行模式 | 无需演示角色 |
| `GET /api/showcase/scenarios` | 返回最近在线验证驱动的客户演示场景；未验证或失效场景不可启动。 | 只读，无需演示角色 |
| `POST /api/customer-service/sessions` | 创建客服会话 | 可传 `Idempotency-Key` |
| `POST /api/customer-service/sessions/{sessionId}/messages` | 继续提问 | 已转人工的会话停止自动回答 |
| `GET /api/customer-service/sessions/{sessionId}/conversation` | 查看对话与安全检索轨迹 | 不返回知识正文 |
| `GET /api/customer-service/tickets` | 查看人工工单 | 需要 `CUSTOMER_AGENT` 或 `ADMIN` |
| `GET /api/collaboration/work-items` | 查看安全协同队列 | 只读；需要 `CUSTOMER_AGENT` 或 `ADMIN`，支持 `source`、`status`、`limit`（最多 50） |
| `POST /api/alerts/{alertId}/workflows` | 启动告警工作流 | 只在 DashScope 启用时存在 |
| `GET /api/workflows/{workflowId}` | 查询工作流状态 | 只返回脱敏公开 DTO |
| `POST /api/workflows/{workflowId}/approval` | 审批或拒绝 | 需要稳定的 `idempotencyKey` |
| `GET /api/workflows/{workflowId}/events` | 订阅 SSE 事件 | 流程到达终态后关闭 |
| `GET /api/workflows/{workflowId}/observability` | 查看安全观测摘要 | 不暴露内部 Graph 状态 |
| `GET /api/knowledge` | 查看知识元数据 | 需要 `ADMIN`，不返回知识正文 |
| `GET /api/operations/metrics` | 查看运营计数 | 内存数据 |
| `GET /api/governance/overview` | 查看安全聚合治理概览 | 不返回原始业务正文 |
| `GET /api/audit` | 查看安全审计记录 | 需要 `ADMIN` |

高风险工作流进入 `WAITING_APPROVAL` 后，可以提交审批：

```bash
curl -X POST "http://localhost:8080/api/workflows/replace-with-workflow-id/approval" \
  -H "Content-Type: application/json" \
  -H "X-Demo-Role: APPROVER" \
  --data '{"decision":"APPROVE","reviewer":"operator-1","comment":"safe to dispatch Mock work order","idempotencyKey":"approval-request-001"}'
```

`decision` 只能是 `APPROVE` 或 `REJECT`。Mock 审批可能创建内存工单，但不会授权或控制真实设备。

订阅工作流事件：

```bash
curl -N -H "Accept: text/event-stream" \
  "http://localhost:8080/api/workflows/replace-with-workflow-id/events"
```

## 构建与测试

Windows PowerShell：

```powershell
.\mvnw.cmd test
.\mvnw.cmd package -DskipTests

Set-Location ui
npm ci
npm run build
```

macOS/Linux：

```bash
./mvnw test
./mvnw package -DskipTests

cd ui
npm ci
npm run build
```

默认测试使用测试替身或禁用 DashScope，不需要真实密钥，也不会调用真实模型。

只有显式传入开关时，才执行一次真实 DashScope 连通性测试：

```powershell
$secureDashScopeKey = Read-Host 'DashScope API key' -AsSecureString
$env:AI_DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', $secureDashScopeKey).Password
.\mvnw.cmd -Drun.dashscope.smoke=true -Dtest=DashScopeSmokeTest test
```

该测试只检查模型返回非空，不输出 API Key 或完整模型响应；没有 Key 时会安全跳过。

## 项目结构

```text
.
├─ src/main/java/com/example/smartpark
│  ├─ model/          领域模型
│  ├─ port/           外部能力边界
│  ├─ adapter/mock/   内存 Mock 适配器
│  ├─ adapter/rag/    DashScope 与向量检索适配器
│  ├─ agent/          模型调用、Prompt 与结构化输出
│  ├─ tool/           只读 Agent 工具
│  ├─ workflow/       告警与客服工作流
│  └─ web/            REST、SSE 与演示角色边界
├─ src/test/          单元、集成和架构边界测试
├─ ui/                Vue 3 工作流控制台
└─ docs/              架构说明与设计记录
```

- [详细架构说明](docs/architecture.md)
- [客户版能力展示](docs/customer-capabilities.md)
- [前端开发说明](ui/README.md)
- [设计与实施记录](docs/superpowers/)

## 当前边界与生产化方向

- **持久化：** Graph 状态、事件、审批、幂等记录、会话和工单当前都在进程内；生产环境需要持久化 checkpoint 和多实例一致性方案。
- **认证授权：** `X-Demo-Role` 仅用于本地演示；真实系统必须补充身份认证、细粒度授权和租户隔离。
- **知识检索：** `SimpleVectorStore` 是进程内实现；生产环境需要持久化向量库、文档切片、批量导入和索引版本管理。
- **真实系统接入：** 当前 `AlertPort`、`DevicePort`、`EnergyPort`、`SecurityPort`、`KnowledgePort` 和 `WorkOrderPort` 都只连接 Mock 或演示适配器。
- **安防数据：** 真实安防适配器必须在端口前增加专用脱敏、审计和访问控制，不能把原始媒体或人员身份数据送入通用告警模型。

## 停止与清理

使用 `Ctrl+C` 停止后端和前端。若在 PowerShell 中设置过环境变量，可按需清理：

```powershell
Remove-Item Env:AI_DASHSCOPE_API_KEY -ErrorAction SilentlyContinue
Remove-Item Env:SPRING_AI_DASHSCOPE_ENABLED -ErrorAction SilentlyContinue
Remove-Item Env:SMARTPARK_KNOWLEDGE_MODE -ErrorAction SilentlyContinue
Remove-Item Env:SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE -ErrorAction SilentlyContinue
Remove-Variable secureDashScopeKey -ErrorAction SilentlyContinue
```

macOS/Linux 可执行：

```bash
unset AI_DASHSCOPE_API_KEY SPRING_AI_DASHSCOPE_ENABLED
unset SMARTPARK_KNOWLEDGE_MODE SMARTPARK_CUSTOMER_SERVICE_ANSWER_MODE
```
