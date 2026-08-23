# Task 4 报告：按端口拆分 Mock 适配器和测试 Fixture

## 结果

Task 4 已完成。`MockParkSystem` 已拆分为五个只实现单一能力端口的 mock adapter；共享的 `MockParkDataStore` 由 `MockParkFixture` 持有并提供给各 adapter。生产运行时配置也已切换为共享 store 加五个端口 adapter。

## TDD 证据

1. 先新增 `MockAdapterBoundaryTest`，覆盖每个 adapter 的目标端口可赋值性、无关端口不可赋值性，以及 `DEV-ENERGY-001` 的 energy reading。
2. 执行 `./mvnw.cmd -q -Dtest=MockAdapterBoundaryTest test`，按要求得到编译失败，原因是五个 adapter 类尚不存在。
3. 实现五个 adapter、fixture，并迁移 aggregate/workflow/agent/tool 测试。
4. 定向测试和完整测试通过。

## 变更

- 新增 `MockAlertAdapter`、`MockDeviceAdapter`、`MockEnergyAdapter`、`MockKnowledgeAdapter`、`MockWorkOrderAdapter`。
- 新增 `MockParkFixture` 和 `MockAdapterBoundaryTest`。
- 将 `MockParkSystemTest` 移动并改名为 `MockParkFixtureTest`，保留原有断言语义并验证 reset、幂等创建和并发创建行为。
- 更新所有原先构造 `MockParkSystem` 的测试，确保需要共享状态的端口仍来自同一个 fixture/store。
- 更新 `AlertWorkflowRuntimeConfiguration`，注册共享 `MockParkDataStore` 和五个端口 adapter。
- 删除 `MockParkSystem` 生产聚合类。

## 验证

- `./mvnw.cmd -q -Dtest=MockAdapterBoundaryTest,MockParkDataStoreTest,MockParkFixtureTest,EnergyQueryToolTest test`：通过。
- `./mvnw.cmd -q -DskipTests test-compile`：通过。
- `./mvnw.cmd -q test`：通过，102 tests run，0 failures，0 errors，1 skipped。

## 关注事项

- `MockParkFixture` 是测试 fixture，为兼容现有共享状态测试额外实现了五个端口的委托；生产 adapter 本身仍严格各实现一个端口，边界测试覆盖该约束。
- 工作区原有的 Maven/Surefire 输出包含 Spring Boot 启动日志和 JVM sharing warning，但不影响测试结果。
