# 能耗与安防场景的能力边界调整设计

## 目标

在加入安防场景前，降低当前按技术层分组带来的耦合，尤其是 `MockParkSystem` 同时实现多个园区端口、运行时配置直接依赖聚合 Mock 类的问题。调整应保持现有 REST、SSE、工作流状态和测试行为不变。

## 范围

本次只做包边界和 Mock 适配器拆分，不引入 Maven 多模块，也不改变 DashScope 配置、HTTP API、Graph 节点语义或真实园区接入。

### 保留的共享层

- `agent/`：保留通用的告警分诊和诊断 Agent；场景特有数据通过只读工具提供。
- `workflow/`：保留通用的告警工作流、风险门禁、人工审批和事件发布。
- `web/`：保留 REST、SSE 和运行时装配入口，但把 Mock Bean 装配移到独立配置类。
- `model/common/`：放置跨场景对象，例如 `RiskLevel`、`WorkflowStatus`、`ApprovalDecision`、`Diagnosis`、`WorkOrder`、`KnowledgeDocument`、`ParkContext`。

### 按能力拆分的领域层

```text
model/alert/       Alert、AlertClassification
model/energy/      EnergyReading
model/security/    安防事件与证据模型（本次只预留边界）
port/alert/        AlertPort
port/energy/       EnergyPort
port/security/     安防数据端口（本次只预留边界）
port/device/       DevicePort
port/knowledge/    KnowledgePort
port/workorder/    WorkOrderPort
tool/alert/        告警查询工具
tool/energy/       EnergyQueryTool
tool/security/     安防查询工具（后续加入）
```

`agent/` 和 `workflow/` 暂不按场景复制，避免出现 `EnergyWorkflow`、`SecurityWorkflow` 两套重复的审批与幂等逻辑。

## Mock 适配器设计

将 `park/mock/MockParkSystem` 拆为共享的 `MockParkDataStore` 和按端口实现的适配器：

- `MockAlertAdapter implements AlertPort`
- `MockDeviceAdapter implements DevicePort`
- `MockEnergyAdapter implements EnergyPort`
- `MockKnowledgeAdapter implements KnowledgePort`
- `MockWorkOrderAdapter implements WorkOrderPort`

所有适配器共享同一个数据存储，以保留告警、设备、能耗、知识和工单之间的一致性。`MockParkConfiguration` 负责在 Spring 容器中注册这些适配器，并将 `AlertWorkflow` 改为依赖端口接口，而不是聚合 Mock 类型。测试通过一个 `MockParkFixture` 组装同一组适配器，保留 reset、幂等工单和固定种子数据的行为。

## 数据流

```text
HTTP alertId
  -> AlertPort
  -> 通用告警工作流
  -> EnergyQueryTool / 后续 SecurityQueryTool
  -> 结构化诊断
  -> 风险门禁与人工审批
  -> WorkOrderPort
```

安防场景的摄像头、门禁和人员信息不直接加入通用 `Alert` 的自由文本证据；后续通过独立的安防模型和只读工具提供，并在工具边界处理脱敏和权限要求。

`SecurityEvent.evidenceSummary` 是边界层的脱敏摘要契约：值在 `trim()` 后必须以稳定前缀 `REDACTED:` 开头，前缀后必须有非空摘要，且总长度不得超过 512 个字符。模型拒绝明确表示原始载荷的标记，例如 `data:`、`base64`、原始视频/图片、face embedding 或身份证原始数据；但“人脸”“身份证”等业务词本身不是原始载荷，合法的脱敏摘要可以描述相关业务结果，只要没有携带原始数据。

## 兼容性与安全边界

- 现有四个 HTTP 接口路径不变。
- `ALT-TEMP-001`、`ALT-POWER-001`、`ALT-ENERGY-001` 的行为不变。
- Mock 仍不控制真实设备；拆分只改变内部装配方式。
- 真实安防适配器、认证授权、摄像头图像处理和生产持久化不在本次范围内。

## 验证策略

- 先为端口到适配器的装配增加测试，确保每个能力使用独立适配器。
- 保留现有全量工作流、Web、敏感信息和幂等测试。
- 新增安防边界的预留测试不调用真实摄像头或门禁系统。
- 执行 `./mvnw test`、`./mvnw package -DskipTests` 和 `git diff --check`。
