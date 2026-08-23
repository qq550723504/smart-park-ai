# Task 5 Report

- 先添加 `MockParkConfigurationTest`，在独立配置类不存在时确认测试编译失败。
- 新增 `MockParkConfiguration`，在原 DashScope 条件下注册一个共享 `MockParkDataStore` 和五个 capability adapter bean。
- 从 `AlertWorkflowRuntimeConfiguration` 移除 Mock store/adapter bean；workflow 继续通过 `AlertPort`、`DevicePort`、`WorkOrderPort`、`KnowledgePort` 组装。
- 保持 DashScope 关闭时 Mock 配置不加载，并验证上下文无 `mockParkSystem` bean 名称。

## Verification

- `mvnw -q -Dtest=MockParkConfigurationTest test`（红灯：配置类不存在）
- `mvnw -q -Dtest=MockParkConfigurationTest test`（绿灯）
- `mvnw -q -Dtest=MockParkConfigurationTest,SmartParkApplicationTest,AlertWorkflowControllerTest,WorkflowEventControllerTest,WorkflowRuntimeControllerTest test`（通过）
- `mvnw -q test-compile`（通过）
- `mvnw -q test`（通过）
- `git diff --check`（通过；仅有 Git 的 LF/CRLF 提示）
