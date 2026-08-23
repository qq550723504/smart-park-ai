# Task 6 Report

## 实现

- 提交 `89f15e0`：`refactor: reserve security capability boundary`；提交 `b201de2`：`revert: isolate unrelated f579d8d changes`。
- `f579d8d` 已通过独立 revert/fix 提交撤销，恢复以下五个无关文件到 `3fb165f` 内容：`MockParkDataStore`、`PromptCatalog`、`AlertWorkflowNodes` 及 `AlertDiagnosisAgentTest`、`EnergyWorkflowTest`。Task 6 的中文 `README.md` 和 security 源码/测试文件未被撤销。
- 新增不可变 `SecurityEvent` record，校验 `eventId`、`parkId`、`buildingId`、`eventType`、`evidenceSummary` 不得为空白，`occurredAt` 不得为空；文本按现有模型风格 trim。
- `evidenceSummary` 现在要求 trim 后以稳定前缀 `REDACTED:` 开头，包含非空摘要，长度不超过 512 个字符，并拒绝明确表示原始载荷的标记：`data:`、`base64`、raw video/image bytes、原始视频/图片、face embedding、身份证原始数据等。`人脸`、`身份证`等业务词本身可以出现在 `REDACTED:` 摘要中，只要没有携带原始数据。该校验是边界层轻量输入约束，不替代后续适配器的认证、授权和业务脱敏。
- 新增只暴露 `SecurityEvent getEvent(String eventId)` 的 `SecurityPort`。
- 新增安防包边界测试和模型校验测试；除现有 API/package 反射断言外，`SecurityBoundaryTest` 还读取 `SecurityEvent.class`、`SecurityPort.class` 的 class bytes 并解析常量池，确认不存在 `com/example/smartpark/web`、`com/example/smartpark/tool`、`com/example/smartpark/adapter` 引用。
- README 已补充智慧园区场景、alert/energy 当前能力、security 预留边界、`evidenceSummary` 轻量格式约束、Mock 数据与 DashScope API Key/base URL 使用方式及下一步安防接入点。

## Verification

- `.\mvnw.cmd clean`：通过。
- `.\mvnw.cmd '-Dtest=SecurityBoundaryTest,SecurityEventTest' test`：9 tests run，0 failures，0 errors，0 skipped。
- `.\mvnw.cmd test-compile`：通过。
- `.\mvnw.cmd test`：112 tests run，0 failures，0 errors，1 skipped（既有 `DashScopeSmokeTest`，未显式提供 Key 与 smoke 参数）。
- `.\mvnw.cmd package -DskipTests`：通过，生成 `target/smart-park-alert-workflow-0.0.1-SNAPSHOT.jar`。
- 后续提交 `858eb52` 收紧了 `MockParkFixture`/`MockParkDataStore` 的 Fixture/Store 暴露面，并补充边界测试；随后全量验证为 115 tests clean。
- `git diff --check`：通过。

## Scope

- 未新增 security Spring Bean、Mock adapter、HTTP endpoint 或 workflow 分支。
- 未修改 Maven、DashScope 配置、已有端口/API 或既有非 security 校验语义。
- approved design 无需修改。
- 提交只包含 Task 6 路径；工作区中原有的其他修改未暂存。

## Concerns

- `evidenceSummary` 的 prefix/长度/标记检查只是一层轻量输入约束，不能证明内容已经完成业务脱敏，也不能替代认证、授权、租户隔离或适配器侧的原始数据控制。
