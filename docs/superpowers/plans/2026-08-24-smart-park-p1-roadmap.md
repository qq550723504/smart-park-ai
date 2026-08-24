# 智慧园区 P1 总实施路线图

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` for each linked plan, use `superpowers:test-driven-development` for implementation, and use `superpowers:verification-before-completion` before claiming any phase complete.

**Goal:** 按最小可验证依赖顺序交付实时语音、专家协作和自然语言运营分析，并最终完成在线展台验收。

**Architecture:** 先升级框架，再建立统一事件契约；三个业务能力共享事件但保持独立职责边界；最后只在同一真实代码路径上做集成、性能和展台验收。

**Tech Stack:** Spring Boot 4, Spring AI/Spring AI Alibaba 2.0, Java 17, PostgreSQL, Vue 3, TypeScript, DashScope, Maven, Vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-24-smart-park-p1-voice-multiagent-analytics-design.md`

**Global constraints:** 在线真实链路、不做运行时降级；保留原告警工作流和客服能力；所有园区工具只读；安防和执行轨迹默认脱敏；原始音频不持久化；SQL 有 AST 与数据库双重以上硬边界；每个提交只包含当前任务文件。

## 计划集

1. [Spring AI Alibaba 2.0 基线升级](./2026-08-24-spring-ai-alibaba-2-upgrade.md)
2. [统一执行事件与轨迹栏](./2026-08-24-smart-park-execution-events.md)
3. [自然语言运营分析](./2026-08-24-smart-park-operations-analysis.md)
4. [多专家 Agent 协作](./2026-08-24-smart-park-expert-collaboration.md)
5. [实时语音助手](./2026-08-24-smart-park-realtime-voice.md)
6. [展台验收与在线链路加固](./2026-08-24-smart-park-p1-showcase-hardening.md)

## 依赖顺序

```text
2.0 基线升级
    |
统一执行事件与轨迹栏
    |----------------------|----------------------|
运营分析                 专家协作                 实时语音
    |----------------------|----------------------|
                 展台验收与在线加固
```

- [ ] Phase 0：执行计划 1。只有依赖树无 1.x、原回归全绿、P1 API 探针通过才能继续。
- [ ] Phase 1：执行计划 2。只有 Java/TypeScript 事件契约、旧 API 兼容和轨迹栏全绿才能继续。
- [ ] Phase 2A：执行计划 3，交付真实只读 SQL 分析。
- [ ] Phase 2B：执行计划 4，交付动态并行专家协作。
- [ ] Phase 2C：执行计划 5，交付可中断实时语音。若语音需调用运营 SQL，必须等待 2A；仅告警/能耗/政策只读工具时可独立执行。
- [ ] Phase 3：三个 Phase 2 均完成后执行计划 6。

Phase 2A/2B/2C 在代码层可并行，但共享 `App.vue`、`ui/src/styles.css`、`pom.xml`、`application.yml`。若多人或多 Agent 同时实施，必须预先分配这些文件的唯一 owner，其他分支只提交领域文件，最后由 owner 做显式集成，禁止让三个分支各自覆盖公共文件。

## 每阶段统一交付证据

- [ ] 记录聚焦测试命令与结果。
- [ ] 记录完整后端测试、前端单测/构建结果。
- [ ] 使用 `git diff --cached --name-only` 确认暂存范围。
- [ ] 使用 `git diff --check` 检查格式。
- [ ] 给出 commit hash；不得把“本地通过”写成“CI/在线/业务验收通过”。

## 跨计划契约冻结

- `ExecutionEvent` 字段：`eventId, runId, sequence, timestamp, scenario, actor, stage, eventType, status, safeSummary, displayPayload`。
- 场景：`VOICE, EXPERT_COLLABORATION, OPERATIONS_ANALYSIS, ALERT_WORKFLOW`。
- `ExpertFinding`：`domain, status, conclusion, evidenceRefs, confidence, nextChecks`。
- `ChartSpec`：`type, title, xField, yFields, seriesField, unit`。
- 统一轨迹：`GET /api/executions/{runId}/events`。
- 语音：`POST /api/voice/sessions`、`GET /api/voice/sessions/{sessionId}`、`/ws/voice/sessions/{sessionId}`。
- 专家：`POST /api/expert-collaboration/runs`、`GET /api/expert-collaboration/runs/{runId}`。
- 分析：`POST /api/operations-analysis/runs`、`POST /api/operations-analysis/runs/{runId}/clarifications`、`GET /api/operations-analysis/runs/{runId}`。

修改以上契约前，必须先更新设计规格、所有受影响计划和 Java/TypeScript 契约测试，不能只改单侧实现。

## 总体验收

- [ ] 语音：点击开始/停止，实时 ASR、真实工具、流式文本、可中断 TTS；5–10 秒。
- [ ] 专家：单领域不误派，跨领域三专家并行，Supervisor 只基于证据综合；20–40 秒。
- [ ] 分析：自然语言到真实只读 SQL、表格、ECharts 和结果约束总结；10–20 秒。
- [ ] 三场景均显示同协议的真实执行轨迹，无 UI 伪事件。
- [ ] 在线失败明确终止，不出现 mock、静态答案或静默降级。
- [ ] 原告警工作流、客服、MCP、安全边界全量回归通过。
