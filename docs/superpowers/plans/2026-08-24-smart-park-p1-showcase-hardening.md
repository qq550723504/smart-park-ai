# 智慧园区 P1 展台验收与在线链路加固实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to execute this plan task-by-task, and use `superpowers:test-driven-development` for every behavior change.

**Goal:** 将三个已完成能力组合成可重复验收的在线展台，证明真实供应商、真实数据库、真实工具、性能、隐私和失败行为均符合设计目标。

**Architecture:** 不新增第二套演示后端。通过显式 opt-in 在线 smoke、Playwright 端到端、Micrometer 指标、run TTL 和运维文档验收同一生产代码路径。

**Tech Stack:** JUnit 5 tagged integration tests, Testcontainers/PostgreSQL, Playwright, Vitest, Micrometer Actuator, Maven/Node CI.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Depends on:** 升级、统一事件、运营分析、专家协作、实时语音五个计划全部完成。

**Global constraints:** 在线 smoke 只能使用真实 DashScope 和真实 PostgreSQL；缺少凭据时必须报告“未执行”而不是通过；默认 CI 不消耗外部配额；不得录制原始音频或敏感安防数据；验收分别报告本地单测、在线链路、性能和业务演示结果。

## 文件结构与职责

- `src/test/java/com/example/smartpark/integration/*OnlineSmokeTest.java`：显式 opt-in 真实链路。
- `ui/e2e/*.spec.ts`、`ui/playwright.config.ts`：浏览器端到端和可访问性。
- `src/main/java/com/example/smartpark/observability/*`：场景级延迟、错误、工具和中断指标。
- `src/main/java/com/example/smartpark/execution/ExecutionRunJanitor.java`：终态 run TTL 清理。
- `.github/workflows/ci.yml`：默认单测/构建/Playwright 门禁，不放在线密钥。
- `docs/demo/smart-park-p1-runbook.md`：环境、启动、三条演示脚本、故障恢复。
- `docs/demo/smart-park-p1-acceptance.md`：十轮验收记录模板与判定。

## Task 1：建立统一观测指标

- [ ] 先写 `ScenarioMetricsTest.java`，覆盖每场景 run 计数、成功/失败、端到端耗时、工具耗时、ASR final、TTS first chunk、专家分支、SQL 执行、语音中断；标签禁止 question、SQL、设备 ID、session ID。

- [ ] 增加 Actuator/Micrometer，创建低基数 `ScenarioMetrics` 并在三个 service 的真正状态转换处计时，不在 controller 猜测完成时间。

- [ ] 验证 `/actuator/health` 不泄露凭据或连接串，metrics 端点由配置控制。

- [ ] 提交：

```powershell
.\mvnw.cmd -B -Dtest=ScenarioMetricsTest,OperationsMetricsTest test
git add -- pom.xml src/main/java/com/example/smartpark/observability src/main/resources/application.yml src/test/java/com/example/smartpark/observability
git commit -m "feat: observe p1 scenario latency and failures"
```

## Task 2：终态 run 和资源清理

- [ ] 先写 `ExecutionRunJanitorTest.java`，用可控 Clock 覆盖：运行中不删、终态达到 TTL 才删、清理 execution/voice/expert/analytics store 一致、订阅中的流先完成、重复清理幂等。

- [ ] 实现终态默认 TTL 30 分钟、每 5 分钟扫描；参数可配。Voice session 额外断言 Media/ASR/TTS 资源已关闭后才能删除。

- [ ] 验证并提交：

```powershell
.\mvnw.cmd -B -Dtest=ExecutionRunJanitorTest,VoiceSessionServiceTest,ExpertCollaborationServiceTest,OperationsAnalysisServiceTest test
git add -- src/main/java/com/example/smartpark/execution/ExecutionRunJanitor.java src/main/java/com/example/smartpark/execution/ExecutionRetentionProperties.java src/main/resources/application.yml src/test/java/com/example/smartpark/execution/ExecutionRunJanitorTest.java
git commit -m "feat: expire completed execution runs"
```

## Task 3：真实在线 smoke 测试

- [ ] 创建 `OnlineTestConditions`：仅当 `-Dsmartpark.online=true` 且所需环境变量存在才运行；否则 JUnit 明确显示 disabled 原因。

- [ ] 新增四个 `@Tag("online")` 测试：ASR 固定非敏感音频得到 final transcript；TTS 固定文本得到首块和完成；三领域问题真实调度三专家并有 evidence；真实模型生成 SQL 并在只读 PostgreSQL 得到行和图表。

- [ ] 在线测试不记录完整音频/回答/SQL 值，只输出 runId、阶段耗时、工具名和判定。

- [ ] 运行：

```powershell
.\mvnw.cmd -B -Dsmartpark.online=true -Dgroups=online test
```

预期：凭据齐全时四项通过；缺少凭据时明确 disabled，不能报告为在线验收通过。

- [ ] 提交：

```powershell
git add -- src/test/java/com/example/smartpark/integration
git commit -m "test: add opt-in online p1 smoke coverage"
```

## Task 4：浏览器端到端测试

- [ ] 在 `ui/package.json` 增加 `test:e2e` 和 Playwright；创建 config，测试使用受控后端 test profile，但过程事件仍由真实应用服务产生。

- [ ] 写四条 e2e：原告警工作流；运营分析真实 SQL/表格/图表/轨迹；三专家动态 handoff；语音通过虚拟音频验证 partial/final/tool/answer/audio 和中断。

- [ ] 断言 sequence 连续、无关专家不运行、错误不出现 mock/fallback 文案、键盘可操作和关键 aria-label。

- [ ] 验证并提交：

```powershell
Push-Location ui
npm.cmd install
npx.cmd playwright install chromium
npm.cmd run test:e2e
Pop-Location
git add -- ui/package.json ui/package-lock.json ui/playwright.config.ts ui/e2e
git commit -m "test: cover p1 showcase journeys"
```

## Task 5：CI 门禁

- [ ] 更新 `.github/workflows/ci.yml`：后端 `test`；前端 `test:unit + build`；Playwright 使用本地 PostgreSQL/service profile。在线 smoke 不加入无密钥 CI，只提供手动命令。

- [ ] 增加测试确保应用 test profile 不能注册 mock ASR/TTS 为生产 bean；浏览器 stub 只能位于 test source/profile。

- [ ] 本地执行与 CI 相同命令后提交：

```powershell
.\mvnw.cmd -B test
Push-Location ui
npm.cmd ci
npm.cmd run test:unit
npm.cmd run build
npm.cmd run test:e2e
Pop-Location
git add -- .github/workflows/ci.yml src/test ui
git commit -m "ci: gate smart park p1 experiences"
```

## Task 6：演示手册与十轮验收

- [ ] 编写 runbook，列出环境变量名但不含值、PostgreSQL migration/账号检查、后端/UI 启动、麦克风授权、健康检查、三条演示问题与预期专家集合。

- [ ] 固定问题：语音“现在有哪些高风险告警？A2 昨夜能耗如何？访客停车政策是什么？”；专家“A2 昨夜能耗升高，同时冷机离线且北门出现连续拒绝访问，请综合判断。”；分析“比较 A1、A2 最近 7 天夜间能耗偏差和高风险告警数，并画趋势图。”

- [ ] 连续执行每场景 10 轮，记录成功率、P50/P95、错误阶段；目标：语音 5–10 秒、专家 20–40 秒、分析 10–20 秒。任一失败保留 runId 和安全错误码，不以重跑覆盖。

- [ ] acceptance 文档分别记录自动化、在线 smoke、十轮性能、业务展台确认。

- [ ] 提交：

```powershell
git add -- docs/demo/smart-park-p1-runbook.md docs/demo/smart-park-p1-acceptance.md README.md
git commit -m "docs: add smart park p1 showcase runbook"
```

## Task 7：最终安全与发布前检查

- [ ] 运行全套测试、在线 smoke、十轮验收、`git diff --check`。
- [ ] 搜索构建产物、日志样例、事件 JSON，确认不含密钥、连接串、原始音频、完整安防明细。
- [ ] 报告四种状态，不合并表述：本地测试、CI、在线链路、业务展台验收。

## 完成闸门

- 三个目标链路均在同一真实运行代码上完成，无降级分支。
- 自动化、在线 smoke、十轮性能和业务验收分别有证据。
- 事件、日志、指标均低敏低基数；终态资源可清理。
- 原告警/客服功能回归通过。
