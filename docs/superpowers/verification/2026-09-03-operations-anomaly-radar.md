# 运营异常雷达验证记录

## 后端

- `./mvnw.cmd '-Dtest=OperationsAnomalyQueryTest,JdbcAnalyticsReaderTest,AnalyticsAnomalyConfigurationTest,OperationsAnomalyServiceTest,OperationsControllerAnomalyTest' test`
  - 10 tests passed, 0 failures, 0 errors。
- `./mvnw.cmd test`
  - 1120 tests run, 0 failures, 0 errors, 3 skipped；构建成功。

## 前端

- `npm run test:unit`
  - 35 个测试文件通过，339 个测试通过。
- `npm run typecheck`
  - 通过。
- `npm run build`
  - 生产构建通过。
  - Vite 保留项目既有的单 chunk 大于 500 kB 提示，本功能未引入构建错误。

## 验收范围

- 运营看板显示服务器返回的告警、高风险告警、离线设备和受影响楼宇事实卡片。
- 雷达数据窗口与设备快照口径明确展示，避免把设备快照伪装为历史趋势。
- 告警、设备、能耗按域独立读取；局部失败通过 `domainStatus` 展示，不转成零值。
- 点击楼宇打开告警、设备、能耗安全摘要抽屉；原始 payload 和未脱敏自由文本不进入 UI。
- 执行轨迹只订阅服务端返回的 `runId`；不存在 runId 时不生成虚假轨迹。
- 分析能力关闭或响应格式异常时，前端显示不可用态，不渲染不完整的静态业务数字。
