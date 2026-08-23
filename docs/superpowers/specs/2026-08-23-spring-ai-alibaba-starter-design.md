# Spring AI Alibaba 最小练习项目设计（已废弃）

> 本文档是早期 Hello World 方案，已被 [智慧园区设备告警智能处置设计](2026-08-23-smart-park-alert-workflow-design.md) 取代，不作为当前实现依据。

## 目标

在当前空目录创建一个可以直接连接阿里云百炼 DashScope 的最小 Spring AI Alibaba 应用，用于理解 Spring Boot 自动配置、`ChatClient` 调用链和 HTTP 接口边界。第一阶段不引入 Agent、RAG、数据库、会话记忆或前端页面，避免学习目标被基础设施分散。

## 成功标准

1. 设置 `AI_DASHSCOPE_API_KEY` 后，可以启动 Spring Boot 应用。
2. 请求 `GET /ai/chat?input=你好` 能返回 DashScope 模型的文本回答。
3. API Key 不出现在源代码、配置提交或 README 示例中。
4. 空输入返回明确的客户端错误，不触发模型调用。
5. 在没有真实 API Key 的情况下，Web 层测试仍可运行并验证接口边界。
6. README 能让 Windows PowerShell 和 macOS/Linux 用户分别完成配置与启动。

## 技术方案

### 项目基础

- Java 17+
- Spring Boot 3.x
- Maven Wrapper，减少对本机 Maven 安装的依赖
- Maven 依赖 `com.alibaba.cloud.ai:spring-ai-alibaba-starter-dashscope`
- 默认模型使用 `qwen-plus`，允许通过配置覆盖

版本号应在实现计划阶段根据官方仓库和 Maven Central 的当前可用版本再次核对，不把过时版本写死在设计阶段。

### 运行时配置

`application.yml` 只保存非敏感默认值和环境变量映射：

- `spring.ai.dashscope.api-key` 从 `AI_DASHSCOPE_API_KEY` 读取
- `spring.ai.dashscope.chat.options.model` 默认 `qwen-plus`

缺少 API Key 时允许应用启动，但真实调用应失败并由 README 说明配置方式；测试不依赖真实 Key。

### 代码边界

- `SpringAiAlibabaApplication`：唯一启动入口。
- `ChatController`：负责 HTTP 参数校验和调用 `ChatClient`，不承载模型配置或业务编排。
- `application.yml`：负责外部化配置，不写入凭据。
- `ChatControllerTest`：验证成功请求和空输入边界；模型调用使用测试替身。

第一阶段只提供同步文本调用。流式响应、工具调用、结构化输出、Chat Memory 和 RAG 作为后续独立练习切片，不预埋无用抽象。

## 请求流程

```text
HTTP GET /ai/chat?input=...
        |
        v
ChatController -- 空输入校验 --> 400
        |
        v
ChatClient.Builder / DashScope ChatModel
        |
        v
DashScope API --> 文本响应 --> HTTP 200
```

## 错误处理

- `input` 缺失或仅包含空白：返回 HTTP 400。
- DashScope 调用失败：保留 Spring Boot 的错误响应能力，不伪造成功结果；在 README 中说明 Key、额度、网络和模型配置是外部依赖。
- 不记录 API Key；日志中不打印完整请求头或敏感配置。

## 验证策略

1. 静态检查：确认文件结构、依赖坐标、配置键和 README 命令一致。
2. 单元/Web 层测试：不调用外部网络，覆盖有效输入和无效输入。
3. 若本机补齐 JDK 17+：执行 Maven Wrapper 测试和应用编译。
4. 若配置了用户自己的 DashScope Key：由用户手动执行一次真实请求；本项目不代替用户保存或输出 Key。

## 后续练习路线

按独立切片逐步增加：

1. Prompt Template 与系统提示词。
2. `ChatResponse`、Token Usage 和模型选项。
3. SSE 流式输出。
4. Tool Calling。
5. Chat Memory。
6. Embedding、Vector Store 与 RAG。
7. Spring AI Alibaba Agent/Graph。

每个切片都应有自己的测试和 README 说明，避免把多个概念混成一个不可诊断的示例。
