# B2 夜间能耗预设场景（SCN-B2-NIGHT-ENERGY-001 v1.0.0）

本文件记录基于交接包 `smart-park-unified-demo-v1.1`（issue brief 06、repository alignment 07、actions/acceptance 03）实现的客户演示场景：统一数据 → 派生计算 → 共享运行状态 → Mock 读写 provider → 客户壳层页面接入。

- 场景：研发大厦 B2 夜间能耗异常（发现 → 选择 → 处理 → 复盘）
- 数据源：`SCENARIO_FIXTURE`（模拟数据，非真实业务数据）
- 与既有 `OPERATIONS_ANALYTICS` 在线数据、旧 B2 在线案例、安防支线（#62/#82）完全隔离

## 1. 分层与文件

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 统一数据 | `ui/src/scenario/b2-night-energy/scenario-data.json` | 交接包 `04-scenario-data.json` 字节一致副本（sha256 `2c6ca619…`） |
| 类型 | `ui/src/types/scenarioEnergy.ts` | 全部场景契约类型 |
| 派生计算 | `ui/src/scenario/b2-night-energy/calculations.ts` | 账本、分项、楼宇/园区合计、方案估算、随访模拟等纯函数 |
| 固定事实 | `ui/src/scenario/b2-night-energy/fixture.ts` | 固定虚拟时钟、run 序列、初始状态 |
| 共享运行状态 | `ui/src/scenario/b2-night-energy/store.ts` | 单例共享 run + sessionStorage 持久化 + generation 守卫 |
| Mock 读写 | `ui/src/scenario/b2-night-energy/provider.ts` | 状态机 + 幂等键 + 故障注入 |
| 上下文映射 | `ui/src/scenario/b2-night-energy/context.ts` | 快照 → `CustomerAnalysisContext`（`source: 'SCENARIO_FIXTURE'`） |
| 页面 | `ui/src/components/customer/scenario/` | `ScenarioWorkspace` + 总览/分析/工单/报告四面板 + 样式 |
| 壳层接入 | `ui/src/components/showcase/ShowcaseHome.vue` | 场景模式开关、URL 参数、刷新恢复 |

## 2. 事实与数字（必须由账本求值，页面不另编数字）

- 96 条小时读数 = 4 分项 × 24 桶；总电表 `SCN-B2-METER-TOTAL` 只汇总，不是独立行
- B2 基线 1000 / 观测 1300 / 偏差 30%（excess 300）
  - 分项：HVAC-PUBLIC 240→440、LIGHT-PUBLIC 120→160、HVAC-RD 280→340、ESSENTIAL 360→360
- B1/B3 按 B2 基线 × 0.90 / × 0.70 生成（背景楼宇，不参与设备检查）
- 园区 2600 → 2900（11.54%）
- 固定虚拟时钟 2026-09-11 09:00 Asia/Shanghai；观察窗口 [2026-09-10T08:00+08, 2026-09-11T08:00+08)；偏差阈值 20%
- 所有派生数值按 fixture 的十进制 **HALF_UP** 舍入（用最短十进制字符串移位后再取整，避免二进制表示使 4.725 被当成 4.72）
- `excess`/`偏差` 只在**观测完整**时发布：`LedgerTotals` 带 `observedMissingCount`/`observedComplete`，只要有一条观测缺失，`excessKwh` 与 `deviationPct` 就为 `null`。PARTIAL 下已取得 1280 与完整基线 1000 覆盖不同周期，不再给出“28%”这种伪造偏差；总览 KPI 显示“观测不完整，暂不计算偏差”，研判摘要也写明观测不完整并把它记入 unknowns
- 计划：
  - `SCN-PLAN-NONE` 0 / 1300
  - `SCN-PLAN-PUBLIC-HVAC` 20kW × 4h = 80 kWh → 1220，1760 元/月（推荐）
  - `SCN-PLAN-HVAC-LIGHT` 22.5kW × 4h = 90 kWh → 1210，1980 元/月
  - 参数示例 3h → 60 / 1240 / 1320
- 随访（仅在显式验证后可见）：观测平移 +24h，逐桶扣减 `power × overlapHours × 0.90`，上限为该桶正向增量
  - 推荐方案：节省 72 → 1228（剩余偏差 22.8%）
  - 联合方案：81 → 1219（21.9%）
  - 园区随访 2828
  - `0.90` 是固定剧情参数，**不是**模型准确率

## 3. 状态机

`READY → PATROL_DONE → ASSESSED → PLAN_SELECTED → ORDER_CREATED → PROCESSING → APPLIED_AWAITING_VERIFICATION → VERIFIED`，分支 `PLAN_SELECTED → CLOSED_NO_ACTION`。

正交动作（不改主阶段）：`CANCEL_CONFIRM`、`NAVIGATE`、`REFRESH_READS`、`GENERATE_REPORT`、`OPEN_REPORT`、`DOWNLOAD_REPORT`、`RESET_SCENARIO`。

- 报告生成**不**增加 `stateRevision`（否则破坏幂等）
- 生成后按 `stateRevision` 冻结；旧 R1 不因新 R2 变化；阅读/下载从不重新生成
- 重置递增 run 序号（RUN-001 → RUN-002），使旧回调失效；存在未确认命令时阻止重置

## 4. 幂等与故障

- 建单键：`scenarioRunId + anomalyId + planRevision`
- 报告键：`scenarioRunId + stateRevision + reportKind`
- `LOST_CREATE_RESPONSE`：建单已在场景内提交但响应丢失；UI 提示并允许**同一身份**重试，不重复建单
- 幂等身份**由共享 run 状态本身推导**（建单看 `confirmedPlan`/`workOrder`，报告看 `state.reports` 的 `stateRevision+kind`），内存缓存只作加速：页面刷新后同一身份仍解析到同一结果，不会因进程内缓存丢失而卡死在 pending
- 恢复 run 时，若该 run 尚未消费失联故障则重新武装，已消费则不重复触发
- 未确认的 pending 身份**只能被“同一身份重试”消除**：生成/查看报告等正交读操作可继续，但不会清空 pending；其他阶段变更（接单、应用、验证、改参、切换变体、重置）在 pending 未消除前一律被 provider 拒绝，避免绕过重试就解锁“重开本场景”
- 重试解析**直接从当前 run 状态重建**（无陈旧快照缓存）：pending 期间生成的报告在重试确认后依然保留，不会被旧快照回滚
- **重置需显式确认**（`resetPolicy`）：`重开本场景`先弹出确认对话框，确认前不清除任何 run 数据，避免误点丢失当前事件、演示工单与简报

## 5. 变体

变体是**整段 run 的解释参数**（账本、数据质量、故障）。派生状态在产生它的阶段被冻结，因此变体只能在一段 run 开始前（`READY`）选择；run 推进后锁定，需先“重开本场景”再切换。`constructor`、`setVariant`、`reset` 三处必须一致地按变体武装/解除失联故障。

| 变体 | 行为 |
| --- | --- |
| `NORMAL` | 完整主故事 |
| `NO_ACTION` | delta 固定 `selectedPlanId=SCN-PLAN-NONE`：run 全程钉住“保持现状”，选择可执行方案会被 provider 拒绝，只能“保持观察”，不建单，事件置 MONITORING；简报只在**决定已定稿（`CLOSED_NO_ACTION`）**后写“客户明确选择保持现状/保持观察，本次不创建任务”，在 `PLAN_SELECTED` 阶段仍写“当前选择…尚未确认”，与“尚未确认方案”区分 |
| `PARTIAL_DATA` | 省略 `SCN-B2-HVAC-PUBLIC:15` 观测；PARTIAL 不补零，excess/偏差按契约不可计算（显示不完整），方案卡不展示完整周期估算，提交被阻断 |
| `LOST_CREATE_RESPONSE` | 模拟建单响应丢失，同键重试 |

- 变体 delta 是事实契约（`variantPinnedPlanId`）：`NO_ACTION` 的 `selectedPlanId` 由 **provider 层强制**，页面同步禁用非钉住方案并提示；不把变体当成纯展示标签
- “保持现状”动作仅对 `SCN-PLAN-NONE` 可用；选择可执行方案时按钮禁用并给出说明
- 报表下载读取已冻结的 markdown 原样导出，不重新生成、不改快照

## 6. 页面接入

- `ShowcaseHome` 新增场景模式：入口按钮，或 URL `?scenario=b2-night-energy`（可加 `scenarioPage=overview|analysis|work-orders|reports`）
- 场景模式在 **setup 阶段（首帧渲染前）** 由 URL/持久化会话解析：刷新恢复或深链进入时不会先挂载在线页面（及其实时请求）再被替换，场景会话始终隔离且确定性
- 刷新后按 sessionStorage 恢复同一 run 与模式；翻页不重跑命令
- 场景模式下工单页大标题明确标注“模拟任务 / 非真实回执”，在线模式维持原“同一事件 / 真实回执”文案，不混淆夹具与真实回执
- 推进虚拟时钟时保留 fixture 的 `+08:00` 偏移（不转 UTC）：处理记录/报告时间戳按字符串截取展示，09:01 不会显示成 01:01
- 工单页与冻结状态一致：pending 未消除时“接单”禁用，并在本页提供“按同一身份重试建单”，用户无需返回分析页
- 存储受限嵌入（`sessionStorage` 方法抛错）下读写均降级为空缓存，不影响 run 创建与推进
- 壳层“重开导览”只重置展示状态（页面、焦点、助手会话），**不重置共享场景 run**；清空场景由场景内独立的“重开本场景”负责，与弹窗“不删除共享数据”的承诺一致
- 参数表单跟随 run 身份：`重开本场景` 后表单回到 fixture 默认值，不会拿上一段 run 的已改参数渲染方案估算
- 参数与方案卡在**人工确认后冻结**：`stage != PLAN_SELECTED` 时输入框与应用按钮禁用，方案卡改用已冻结的回执参数（而非本地表单）求值，因此不会再出现“页面估算”与“已建单回执”互相矛盾
- 确认与参数应用**原子化**：表单有未应用的改动时，`确认并创建演示任务`会先按当前表单校验并保存，再建单，回执就是用户确认页看到的参数；表单非法时确认按钮禁用，杜绝“界面显示 A 参数、回执冻结 B 参数”的静默矛盾
- 场景四个面板分别携带壳层的 `customer-{overview,analysis,work-orders,reports}-main` id（`tabindex=-1`），所以场景模式下的 `CustomerShell` 跳转链接与外壳导航的焦点定位都能生效，不再指向不存在的目标
- `重开本场景` 是带确认对话框的破坏性操作：弹窗说明只清理本场景 run（run 序号 +1、时钟回 09:00），不影响线上工作台/历史日报；支持取消、Esc 关闭与焦点圈定
- 不进入场景模式时，四个在线页面行为完全不变
- 复用壳层、`CustomerAnalysisHero` 与 AI 助手入口，不修改其内部实现

## 7. 验收与测试

```bash
cd ui
npx vue-tsc -b
npx vitest run        # 56 文件 / 640 测试
npm run build
```

- `calculations.spec.ts`：派生数值与参数边界；数据不完整时估算返回 `null` 且**不发布 excess/偏差**（总览与研判均为不完整）；变体 plan delta 解析；十进制 HALF_UP 平局与联合方案月度估算
- `provider.spec.ts`：状态机、幂等、变体锁定与 plan delta 强制、失联恢复、报告幂等；pending 身份跨无关提交保留且阻塞其他变更；虚拟时钟保留 `+08:00` 偏移；NO_ACTION 简报显式记录“保持观察”决定；未定稿前不写“客户明确选择”
- `store.spec.ts`：持久化、generation 守卫、重置递增；存储受限时的空缓存降级
- `download.spec.ts`：冻结快照原样下载、无浏览器环境静默降级
- `ScenarioFlow.spec.ts`：端到端 巡检→研判→选方案→确认→接单→应用→验证→报告；PARTIAL 阻断且隐藏估算，且总览 KPI 与研判不发完整周期偏差（显示不完整）；失联重试（含刷新恢复，及 pending 期间生成报告后仍保留）；变体锁定与 NO_ACTION 方案钉住；保持观察门控；表单随 run 重置；重开导览保留 run；下载不重生成；恢复会话首帧即场景模式（不挂载在线总览）；工单页“非真实回执”标注；pending 时工单页接单禁用且可就地重试；重置需二次确认（取消保留、确认清 run）；确认后参数输入冻结且方案卡沿用回执参数；未应用的表单改动会在确认时原子保存；非法表单禁用确认；四个场景面板提供壳层 main 焦点目标

## 8. 边界

- 不修改既有在线/模拟演示数据，不改动既有四个客户页面内部实现
- 不实现安防支线（#62/#82 已合并能力直接复用，不重复实现）
- 不接真实设备
- 本 PR 只做前端演示接入，不含生产数据库、不自动合并部署
