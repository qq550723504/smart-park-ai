# 智慧园区结构重构设计

## 目标

在不改变当前 Mock 业务行为和 HTTP 接口语义的前提下，修复三类结构问题：适配器层反向依赖 Web 层、客服工作流同时承担编排与存储职责、README 与架构文档落后于当前代码。

本次重构只调整边界和职责，不引入 PostgreSQL、Spring Security、真实园区适配器或新的业务场景。

## 当前问题

### 1. 适配器层依赖 Web 层

`MockKnowledgeAdapter` 和 `MockParkConfiguration` 直接引用 `com.example.smartpark.web.DemoFaultInjector`。故障注入是本地演示基础设施，不属于 HTTP 层；当前依赖方向使 Mock 适配器不能脱离 Web 运行，也会阻碍后续替换真实知识库适配器。

### 2. 客服工作流职责过重

`CustomerServiceWorkflow` 当前同时负责：

- 意图分类和回答策略；
- 知识检索编排；
- 会话消息和检索轨迹存储；
- 会话 TTL 与容量淘汰；
- 幂等请求缓存；
- 客服工单创建、查询和状态推进。

这些职责目前都使用进程内集合。若以后接 PostgreSQL 或真实坐席系统，必须修改工作流本身，而不是替换一个明确的端口实现。

### 3. 文档与代码不一致

README 仍有“API 一共四个端点”等旧表述，架构文档对模型包、客服存储和当前能力的描述也不完整。文档需要以当前源码和测试实际验证的行为为准。

## 设计

### A. 独立演示故障注入边界

将 `DemoFaultInjector` 移到 `com.example.smartpark.demo` 包，保持它为无 Web 依赖的普通 Java 组件。以下关系成立：

```text
web.DemoFaultController ──> demo.DemoFaultInjector
adapter.mock.MockKnowledgeAdapter ──> demo.DemoFaultInjector
adapter.mock.MockParkConfiguration ──> demo.DemoFaultInjector
```

`DemoFaultController` 仍负责角色校验、请求 DTO 和 HTTP 响应；`DemoFaultInjector` 只负责记录一次性故障并提供消费方法。故障点枚举和故障消费语义保持不变。

生产代码中不得再出现 `adapter... -> web...` 的导入。架构测试应针对源码依赖方向，而不是只测试某个类当前能否实例化。

### B. 为客服存储建立端口

新增客服会话存储端口 `com.example.smartpark.port.customer.CustomerSessionStore`，至少表达以下操作：

- 创建或保存一个会话结果、消息和检索轨迹；
- 按会话 ID 查询当前会话；
- 更新会话结果和追加消息；
- 列出包含人工工单的会话；
- 返回当前会话数量。

新增客服工单端口 `com.example.smartpark.port.customer.CustomerTicketPort`，至少表达以下操作：

- 创建客服工单；
- 查询人工工单；
- 按工单 ID 推进状态。

端口的参数和返回值使用现有 `CustomerServiceResult`、`CustomerConversation`、`CustomerTicket` 等领域模型，不能依赖 Spring Web、Mock 类或数据库类型。

新增内存实现放在 `com.example.smartpark.adapter.mock`，用于保持当前学习环境的零外部依赖：

- `InMemoryCustomerSessionStore` 负责会话、TTL、容量淘汰和会话更新；
- `InMemoryCustomerTicketAdapter` 负责工单序列、状态转换和工单查询。

`CustomerServiceWorkflow` 改为依赖两个端口。它保留意图分类、知识检索、答复生成和转人工决策，但不再直接持有 `ConcurrentHashMap`、`AtomicInteger` 或集合型会话/工单状态。幂等请求应由会话存储端口在进程内实现中继续支持，保证现有 `Idempotency-Key` 语义；后续持久化实现可以把它映射为数据库唯一约束。

客服 HTTP Controller 的路径、请求体、响应体、状态机和权限要求保持不变。当前 Mock 场景的行为保持不变：报修和知识不足仍创建 `WAITING_AGENT` 工单，进入人工处理后自动客服仍拒绝继续回复。

### C. 文档同步

同步以下内容：

- README 的 API 清单改为按能力分组列出实际端点，不再写固定的“四个端点”；
- README 的当前能力、Mock 限制和后续生产化边界与源码一致；
- `docs/architecture.md` 增加客服端口和内存适配器的依赖关系；
- `docs/architecture.md` 修正 `ParkContext` 等模型的实际包路径；
- 文档明确：本次只完成边界重构，尚未完成数据库持久化或真实客服系统接入。

## 数据流

客服请求流程调整为：

```text
CustomerServiceController
        |
        v
CustomerServiceWorkflow
  |          |          |
  |          |          +--> KnowledgePort
  |          +-------------> CustomerTicketPort
  +------------------------> CustomerSessionStore
```

工作流先从 `CustomerSessionStore` 读取幂等请求或现有会话，再执行当前的确定性意图分类和知识检索。需要转人工时，通过 `CustomerTicketPort` 创建工单；最后通过 `CustomerSessionStore` 保存结果、消息和检索轨迹。查询会话、查询工单和推进工单也通过相应端口完成。

## 兼容性要求

- 现有公开 HTTP 路径和 JSON 字段不变。
- 现有 `CustomerServiceWorkflow` 的公开方法继续可用，构造方式可以增加新的依赖注入重载；测试工厂需要显式组装内存端口。
- `MockParkConfiguration` 继续提供现有 Mock Bean。
- `DemoFaultInjector.FaultPoint.KNOWLEDGE_SEARCH` 的名称和一次性消费行为不变。
- 当前全部 Mock 数据、告警流程、客服状态机、审计和运营统计结果不改变。
- 不把原始客服问题、安防原始媒体或身份数据引入任何新 DTO 或日志。

## 测试策略

### 适配器边界

- 先增加一个架构测试，验证 `com.example.smartpark.adapter` 生产源码不导入 `com.example.smartpark.web`。
- 增加故障注入单元测试，验证注入一次后只影响下一次知识检索，随后恢复正常。
- 保留并运行现有 Mock 配置和知识管理测试。

### 客服端口

- 先为内存会话存储写测试：保存/读取会话、追加多轮消息、TTL 淘汰、容量上限和幂等结果复用。
- 为内存工单适配器写测试：创建工单、查询队列、合法状态迁移和未知工单拒绝。
- 调整客服工作流测试，使其通过端口组装，并验证现有停车、访客、能耗、报修、知识不足、会话转人工和幂等行为仍然成立。
- 保留 Controller 测试，验证 HTTP 行为未改变。

### 文档与整体验证

- 使用 `rg` 检查旧的 `web.DemoFaultInjector` 引用和过期端点描述。
- 运行 `./mvnw -B test`。
- 运行 `npm.cmd run build`。
- 检查 Git diff，确认没有引入真实凭据、原始安防数据或未授权的外部系统调用。

## 不在本次范围内

- PostgreSQL、Flyway、Graph 持久化 checkpoint。
- Spring Security、OIDC、JWT 和租户权限。
- 向量数据库、Embedding 和 RAG。
- 真实告警、门禁、摄像头、能耗和工单系统。
- 客服大模型回答、内容安全和坐席系统。
- 新增消防、电梯、环境监测、停车管理等业务场景。

## 验收标准

当以下条件全部满足时，本次结构重构完成：

1. `adapter` 生产代码不再依赖 `web` 包。
2. `CustomerServiceWorkflow` 不再直接持有客服会话和工单状态集合。
3. 客服会话和工单都通过明确的端口访问，并有可替换的内存实现。
4. 现有客服和告警 HTTP 测试保持通过，业务响应语义不变。
5. README 和架构文档中的端点、包路径和能力边界与当前实现一致。
6. 后端完整测试和前端构建通过，工作树只包含本次重构相关文件。
