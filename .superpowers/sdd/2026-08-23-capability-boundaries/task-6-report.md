# Task 6 Report

## 实现

- 提交 `89f15e0`：`refactor: reserve security capability boundary`
- 新增不可变 `SecurityEvent` record，校验 `eventId`、`parkId`、`buildingId`、`eventType`、`evidenceSummary` 不得为空白，`occurredAt` 不得为空；文本按现有模型风格 trim。
- 新增只暴露 `SecurityEvent getEvent(String eventId)` 的 `SecurityPort`。
- 新增安防包边界测试和模型校验测试，确认模型/端口不依赖 `web`、`tool`、`adapter`。
- README 已补充智慧园区场景、alert/energy 当前能力、security 预留边界、Mock 数据与 DashScope API Key/base URL 使用方式及下一步安防接入点。

## Verification

- `.\mvnw.cmd -q '-Dtest=SecurityBoundaryTest,SecurityEventTest' test`：通过。
- `.\mvnw.cmd -q test-compile`：通过。
- `.\mvnw.cmd test`：107 tests run，0 failures，0 errors，1 skipped（既有 `DashScopeSmokeTest`，未显式提供 Key 与 smoke 参数）。
- `.\mvnw.cmd package -DskipTests`：通过，生成 `target/smart-park-alert-workflow-0.0.1-SNAPSHOT.jar`。
- `git diff --check`：通过。

## Scope

- 未新增 security Spring Bean、Mock adapter、HTTP endpoint 或 workflow 分支。
- 未修改 Maven、DashScope 配置、已有端口/API 或既有校验语义。
- approved design 无需修改。
- 提交只包含 Task 6 路径；工作区中原有的其他修改未暂存。

## Concerns

- `evidenceSummary` 是边界模型中的脱敏摘要字段；本任务通过字段命名、文档和接口边界约束其用途，实际脱敏/权限校验应由后续安防适配器负责。
