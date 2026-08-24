# Spring AI Alibaba 2.0 基线升级实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 将项目从 Spring Boot 3.5 / Spring AI 1.1 / Spring AI Alibaba 1.1 升级到用户确认的 Spring AI Alibaba `2.0.0-M1.1` 基线，并用自动化门禁证明现有功能和后续 P1 所需 API 均可用。

**Architecture:** 本计划只处理依赖、编译兼容和能力探针，不增加语音、专家协作或 NL2SQL 行为。升级后的框架基线是所有后续计划的唯一前置条件。

**Tech Stack:** Java 17, Spring Boot 4.0.0, Spring AI 2.0.0-M1, Spring AI Alibaba 2.0.0-M1.1, Maven Wrapper, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Global constraints:** 在线真实链路且不做运行时降级；保留现有 API 和测试；只读工具边界不放宽；敏感内容不得进入公开事件；每个任务独立验证并只提交列出的文件；不得顺手清理无关代码。

## 文件结构与职责

- `pom.xml`：统一管理 Boot、Spring AI、Spring AI Alibaba 主 BOM 与 Extensions BOM；添加依赖收敛门禁。
- `src/test/java/com/example/smartpark/architecture/DependencyBaselineTest.java`：从运行时包和 Maven 属性验证 2.0 基线。
- `src/test/java/com/example/smartpark/architecture/SpringAiAlibaba2CapabilityTest.java`：编译级证明 Graph 并行分支、Agent 和音频接口存在。
- `src/test/java/com/example/smartpark/architecture/McpDependencyCompatibilityTest.java`：继续证明 MCP 依赖只有一个兼容版本。
- `README.md`：记录 milestone 风险、Java/Node 要求及真实链路启动前提。

## Task 1：锁定升级前回归基线

- [ ] 运行现有后端测试并保存终端摘要：

```powershell
.\mvnw.cmd -B test
```

预期：退出码为 0；若当前主分支已有失败，停止升级并先记录具体测试名，不把既有失败归因于 2.0。

- [ ] 运行现有前端构建：

```powershell
Push-Location ui
npm.cmd ci
npm.cmd run build
Pop-Location
```

预期：`vue-tsc` 和 Vite 均成功。

## Task 2：先写版本基线失败测试

- [ ] 新建 `DependencyBaselineTest.java`，先断言目标版本与 Boot 4 API：

```java
class DependencyBaselineTest {
    @Test
    void runsOnSpringBoot4AndSpringAi2() {
        assertThat(SpringBootVersion.getVersion()).startsWith("4.0.");
        assertThat(org.springframework.ai.chat.model.ChatModel.class.getPackage()
                .getImplementationVersion()).startsWith("2.0.");
    }
}
```

- [ ] 运行单测确认在旧基线上失败：

```powershell
.\mvnw.cmd -B -Dtest=DependencyBaselineTest test
```

预期：Boot 版本断言报告实际为 `3.5.10`。

## Task 3：对齐 2.0 BOM 与依赖收敛

- [ ] 修改 `pom.xml`：

  - parent 改为 `org.springframework.boot:spring-boot-starter-parent:4.0.0`；
  - `spring-ai.version=2.0.0-M1`；
  - `spring-ai-alibaba.version=2.0.0-M1.1`；
  - 导入 `spring-ai-alibaba-extensions-bom` 同版本；
  - 删除 DashScope starter 上的显式 `<version>`；
  - 保留 Java 17；
  - 配置 Maven Enforcer 的 `dependencyConvergence` 和 `requireUpperBoundDeps`；
  - 仅在依赖树证明仍有冲突时保留现有 Graph MCP exclusion，并在注释中写明冲突坐标。

- [ ] 检查解析结果：

```powershell
.\mvnw.cmd -B help:effective-pom -Doutput=target/effective-pom.xml
.\mvnw.cmd -B dependency:tree -Dincludes=org.springframework.ai,com.alibaba.cloud.ai,io.modelcontextprotocol.sdk
```

预期：无 Spring AI 1.x、Spring AI Alibaba 1.x；核心 Alibaba 构件均为 `2.0.0-M1.1`。

- [ ] 运行版本测试：

```powershell
.\mvnw.cmd -B -Dtest=DependencyBaselineTest test
```

预期：通过。

- [ ] 提交本任务：

```powershell
git add -- pom.xml src/test/java/com/example/smartpark/architecture/DependencyBaselineTest.java
git commit -m "build: align spring ai alibaba 2 baseline"
```

## Task 4：逐项迁移编译不兼容，不改变业务契约

- [ ] 运行完整编译收集所有错误：

```powershell
.\mvnw.cmd -B -DskipTests compile
```

- [ ] 只修改编译错误直接指向的现有文件。每修复一组 API 迁移，运行其原有聚焦测试；重点覆盖：

```powershell
.\mvnw.cmd -B -Dtest=AlertWorkflowTest,CustomerServiceWorkflowTest,DashScopeCustomerAnswerAdapterTest,McpProtocolIntegrationTest test
```

预期：所有原有契约保持通过；不通过时先比较 1.x/2.0 API 语义，禁止以删除断言或关闭功能解决。

- [ ] 运行架构和安全边界测试：

```powershell
.\mvnw.cmd -B -Dtest='*BoundaryTest,*CompatibilityTest,SecurityBoundaryTest,SensitiveDataTest' test
```

- [ ] 仅暂存实际迁移文件并提交：

```powershell
git diff --name-only
git add -- pom.xml src/main src/test
git commit -m "refactor: migrate smart park runtime to spring ai 2"
```

提交前必须用 `git diff --cached --name-only` 排除文档、UI 和无关改动。

## Task 5：增加后续 P1 能力探针

- [ ] 新建 `SpringAiAlibaba2CapabilityTest.java`，以编译级引用验证以下类型与方法：

```java
@Test
void exposesRequiredP1Primitives() throws Exception {
    assertThat(Class.forName("com.alibaba.cloud.ai.graph.StateGraph")).isNotNull();
    assertThat(Class.forName("com.alibaba.cloud.ai.agent.ReactAgent")).isNotNull();
    assertThat(Class.forName("com.alibaba.cloud.ai.agent.ParallelAgent")).isNotNull();
    assertThat(Class.forName("org.springframework.ai.audio.transcription.StreamingTranscriptionModel")).isNotNull();
    assertThat(Class.forName("org.springframework.ai.audio.tts.StreamingInputTextToSpeechModel")).isNotNull();
}
```

- [ ] 使用反射或直接编译调用确认 `StateGraph.addParallelConditionalEdges(...)` 存在；测试只验证 API 能力，不启动外网调用。

- [ ] 运行：

```powershell
.\mvnw.cmd -B -Dtest=SpringAiAlibaba2CapabilityTest,McpDependencyCompatibilityTest test
```

预期：能力探针和 MCP 单版本断言同时通过。

- [ ] 提交：

```powershell
git add -- src/test/java/com/example/smartpark/architecture/SpringAiAlibaba2CapabilityTest.java src/test/java/com/example/smartpark/architecture/McpDependencyCompatibilityTest.java
git commit -m "test: gate spring ai alibaba 2 capabilities"
```

## Task 6：更新运行说明并完成升级验证

- [ ] 更新 `README.md`，明确：版本是 milestone、需 Java 17/Node 22、需真实 DashScope Key、无 mock/fallback 运行路径、默认测试不访问外网。

- [ ] 执行最终验证：

```powershell
.\mvnw.cmd -B clean test
Push-Location ui
npm.cmd ci
npm.cmd run build
Pop-Location
git diff --check
```

预期：全部退出码为 0。

- [ ] 提交：

```powershell
git add -- README.md
git commit -m "docs: document spring ai alibaba 2 runtime"
```

## 完成闸门

- Maven 依赖树中不存在 1.x Spring AI/Alibaba 构件。
- 原有后端测试、前端构建、MCP 边界全部通过。
- P1 所需 Graph/Agent/ASR/TTS API 探针通过。
- 未引入任何业务功能或运行时降级。
