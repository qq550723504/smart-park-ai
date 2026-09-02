# 客服执行轨迹接入设计

**状态：** 已确认方向，待用户评审本文档

## 1. 目标

将现有园区客服的同步问答接入统一执行轨迹，让每次新建会话或继续提问都能看到真实的输入接收、意图识别、知识检索、回答/转人工和完成状态。轨迹只提供可重放的安全观测，不改变客服会话、幂等、知识检索或工单状态边界。

## 2. 范围与非目标

### 范围

- 每次客服请求（新会话和已有会话回复）创建独立 `runId`。
- 扩展统一执行场景枚举，新增 `CUSTOMER_SERVICE`，保持已有四类场景兼容。
- 在客服应用层编排服务中发布统一 `ExecutionEvent`。
- 客服响应通过 `X-Execution-Run-Id` 返回本次轨迹 ID；响应 JSON 字段保持兼容。
- 前端客服页接受响应后订阅该轨迹，复用现有 `ExecutionTraceRail`。
- 轨迹历史在请求返回后可完整重放；旧的同步 HTTP API 不改为异步任务。

### 非目标

- 不把一个客服会话的多条消息追加到同一个已终态 run。
- 不新增客服专用 SSE 协议、事件 DTO 或第二套轨迹存储。
- 不把用户问题全文、客服回答原文、Prompt、供应商响应、个人信息或知识正文写入事件。
- 不把客服执行改造成新的 Agent 状态机，不改变现有自动转人工和工单创建规则。
- 不让轨迹发布失败阻断已经成功的客服结果；轨迹只读失败也不回滚客服状态。

## 3. 架构

### 3.1 应用层执行包装器

新增 `CustomerServiceExecutionService`，依赖现有 `CustomerServiceWorkflow` 和 `ExecutionEventPublisher`。Controller 只调用包装器，不直接负责事件编排；工作流继续是客服业务状态的唯一来源。

包装器提供：

- `handle(question, idempotencyKey)`：创建 run，发布事件，调用 `workflow.handle`。
- `reply(sessionId, question, idempotencyKey)`：创建 run，发布事件，调用 `workflow.reply`。
- 返回不可变的 `CustomerServiceExecutionResult(runId, CustomerServiceResult)`，仅供 Web 层添加响应头。

为保持现有 standalone Controller 测试和非 Spring 使用方兼容，Controller 保留旧构造器；无事件发布器时仍返回客服结果但不启用轨迹。Spring 正式配置始终注入真实 `ExecutionEventPublisher`。

### 3.2 运行生命周期

每个请求按以下顺序发布事件，所有事件使用新 run 的 UUID 和 `ExecutionScenario.CUSTOMER_SERVICE`：

| 顺序 | 事件 | 阶段 | 状态 | 安全摘要 |
| --- | --- | --- | --- | --- |
| 1 | `RUN_STARTED` | `INPUT_CAPTURE` | `RUNNING` | 客服请求已接收 |
| 2 | `NODE_STARTED` | `UNDERSTANDING` | `RUNNING` | 开始识别服务意图 |
| 3 | `NODE_COMPLETED` | `UNDERSTANDING` | `SUCCEEDED` | 服务意图识别完成 |
| 4 | `NODE_STARTED` | `TOOL_EXECUTION` | `RUNNING` | 开始检索园区知识 |
| 5 | `NODE_COMPLETED` | `TOOL_EXECUTION` | `SUCCEEDED` | 知识检索完成，命中 N 条依据 |
| 6 | `NODE_STARTED` | `RESPONSE_DELIVERY` | `RUNNING` | 开始生成安全答复 |
| 7 | `NODE_COMPLETED` | `RESPONSE_DELIVERY` | `SUCCEEDED` | 已生成客服答复或已转人工 |
| 8 | `COMPLETED` | `COMPLETION` | `SUCCEEDED` | 客服请求处理完成 |

其中 `N` 只使用结果中的知识引用数量。意图仅允许使用固定枚举名称；不把原始问题拼入摘要。报修、知识不足或检索不可用仍沿用现有工作流结果，并在回答节点摘要中使用固定“已转人工”文案。

### 3.3 异常和轨迹隔离

工作流返回 `CustomerServiceResult` 时，包装器根据结果发布成功终态；若工作流抛出未处理异常，包装器发布 `FAILED` 和稳定失败阶段（`CUSTOMER_SERVICE_EXECUTION`），然后继续抛出异常让原有 HTTP 错误处理生效。发布单条观测事件失败时记录安全日志并继续业务调用，不向客户端暴露发布器异常。

轨迹流使用现有 `InMemoryExecutionEventPublisher` 的有序序列、历史重放、终态关闭和 30 分钟保留策略。客服请求结束后，客户端才拿到响应并订阅；订阅会先收到完整历史，因此不需要引入异步轮询或新的传输协议。

## 4. HTTP 契约

现有客服接口路径、请求体、状态码和响应 JSON 字段保持不变：

- `POST /api/customer-service/sessions`
- `POST /api/customer-service/sessions/{sessionId}/messages`

成功响应增加可选响应头：

```text
X-Execution-Run-Id: <uuid>
```

旧客户端忽略该响应头即可继续工作。幂等重放保持业务响应体完全一致，但每次 HTTP 处理仍可有自己的观测 run；幂等语义只约束业务结果，不把观测 ID 写入客服存储。

统一事件查询继续使用：

- `GET /api/executions/{runId}`
- `GET /api/executions/{runId}/events`

这两个接口只返回既有安全事件 DTO，新增场景枚举为兼容性扩展。

## 5. 前端交互

`CustomerServiceConsole` 增加可选 `trace` 属性，`OperationsWorkbench` 传入现有共享 trace：

1. 客服请求成功后从 `X-Execution-Run-Id` 得到 run ID。
2. 在请求代际仍然有效时调用 `trace.subscribe(runId)`。
3. 页面隐藏再返回时，重新订阅最近一次客服请求的 run；页面重置会清理该引用。
4. 轨迹显示由现有 `ExecutionTraceRail` 统一处理，客服页面不复制事件卡或自行制造进度。
5. 响应头缺失、SSE 不可用或轨迹读取失败时，客服消息和工单结果仍正常展示。

客服原有的加载态继续表示 HTTP 请求状态，不将前端定时器伪装成后端事件。

## 6. 安全与兼容性

- `ExecutionScenario` Java/TypeScript 双端同步新增 `CUSTOMER_SERVICE`；已有事件消费者对未知场景应按字符串安全展示，不改变旧场景含义。
- 事件摘要使用固定文案、枚举和引用数量；不得透传用户问题、回答、异常 message 或供应商字段。
- 统一事件接口不增加客服专属敏感字段；客服会话接口继续按现有 DTO 脱敏。
- 客服执行轨迹不授予新的业务权限，客服问答仍按原接口权限执行；响应头中的 UUID 只用于读取公开执行轨迹。
- 事件发布器的内存保留与客服会话保留相互独立，服务重启后二者均清空。

## 7. 测试策略

### 后端

- 编排器在新会话和回复请求中发布完整有序事件。
- 正常回答、知识不足转人工、报修创建工单均只发布安全摘要。
- 工作流异常发布稳定 `FAILED` 后重新抛出；事件发布异常不阻断业务结果。
- 每次请求使用独立 run；终态后不追加后续消息。
- Controller 响应包含 `X-Execution-Run-Id`，旧响应 JSON 保持兼容；统一事件接口可查询新场景。
- Java/TypeScript 场景枚举和既有执行事件测试回归。

### 前端

- 客服新会话和回复都订阅响应头中的 run ID。
- 代际竞争时旧响应不能覆盖新会话或订阅旧轨迹。
- 响应头缺失、轨迹订阅失败和页面切换不阻断客服结果。
- 现有客服消息、幂等、工单队列、工作台和执行轨迹测试继续通过。

## 8. 生产化边界

本切片仍使用进程内事件发布器，仅证明客服流程可观测和可重放。若后续需要跨实例事件一致性、长期审计、消息队列、用户级轨迹权限或客服流式输出，应另行设计持久化事件存储、身份授权、分布式序列和断线恢复，不在本次通过增加字段临时解决。
