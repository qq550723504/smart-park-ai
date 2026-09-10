# Issue #71 连续操作验证

- 日期：2026-09-10（Asia/Shanghai）
- 分支：`codex/issue-71-events-workorders`
- 基线：`origin/main` 的实际 #75 merge commit `851eb858e81c87f95a1051b5e97ba4812a48a5a8`
- 环境：独立本地 Compose 项目 `smartpark-issue71-final`，analytics、DashScope、backend、frontend 均健康；未部署
- 浏览器：Codex 内置浏览器

## 连续链路

1. 园区总览选择 `B1 · 创新中心` 的高风险告警并进入运营分析。
2. 分析页展示既有规则化运营观察和四条人工核查建议；点击“查看同一事件并人工确认”。
3. 事件与工单页核验 `ALT-ORCH-ENERGY-B1-001 / B1 / DEV-ENERGY-B1-001 / ENERGY` 完全一致后开放主操作。
4. 点击“人工确认并创建工单”后出现确认弹窗；弹窗再次显示绑定告警，说明后台选择变化不会改为其他对象。
5. 点击“确认并执行”，现有高风险闸门进入人工审批并由 `APPROVER` 角色确认。
6. 后端实际回执：workflow `COMPLETED`，work order `WO-0001`，raw status `PENDING_EXECUTION`。
7. 页面显示“WO-0001 / 已创建，待处理”，并显示“创建工单不表示问题已解决”；未出现完成或已解决声明。
8. 点击页面内“刷新”，证据列表重新加载，已知 workflow 同时通过 `GET /api/workflows/{workflowId}` 读取最新状态；页面仍显示 `WO-0001 / 已创建，待处理`，没有再次启动工作流或再次提交人工决定。
9. 离开“事件与工单”进入“运营分析”，再由客户导航返回；同一事件的 `WO-0001` 回执和后端实际状态仍然存在，主操作保持“工单已创建”。

新增的“建单 → 页面内刷新 → 离开 → 返回”在同一 1440×900 浏览器会话中连续完成，浏览器控制台没有 warning 或 error。原 1366×768 检查得到 `innerWidth=1366`、`scrollWidth=1351`，无横向文档溢出，主操作可见且可用。

## 截图

| 状态 | 文件 |
| --- | --- |
| 同一事件核验完成、人工确认前 | `docs/evidence/issue-71/01-same-event-before-confirm-1440x900.png` |
| 人工确认弹窗 | `docs/evidence/issue-71/02-manual-confirmation-dialog-1440x900.png` |
| 实际工单回执，1440×900 | `docs/evidence/issue-71/03-work-order-receipt-WO-0001-1440x900.png` |
| 实际工单回执，1920×1080 | `docs/evidence/issue-71/04-work-order-receipt-WO-0001-1920x1080.png` |
| 与第三张设计稿同视口的 QA 状态 | `docs/evidence/issue-71/05-design-qa-1672x941.png` |
| 页面内刷新后仍保留实际回执 | `docs/evidence/issue-71/06-after-page-refresh-WO-0001-1440x900.png` |
| 离开并返回后仍保留实际回执 | `docs/evidence/issue-71/07-after-leave-return-WO-0001-1440x900.png` |

## 自动化回归

- 后端：`mvnw.cmd test` → 1328 tests，0 failures，0 errors，3 条既有条件跳过。
- 前端：`npm run typecheck` → 通过。
- 前端：`npm run test:unit` → 48 files / 481 tests 通过。
- 前端：`npm run build` → 通过；仅保留既有 Vite 大 chunk 提示。

自动化覆盖同一告警成功链、身份不一致时阻断且不替换告警、取消确认不建单、无审批权限、审批响应不明确时回查、确定性 403 不重复提交，以及本次新增的：已知回执在页面内刷新和离开/返回后保留、状态 GET 失败时保留回执并标明最新状态未知、start/approval pending 时刷新禁用、中断的初始证据加载返回后重试且旧响应不污染当前页面。

## 已知边界

页面内“刷新”会刷新证据并通过已知 workflow 的 GET 状态读取保留实际回执；同一已挂载客户页面内离开再返回也会保留该已知结果。浏览器硬刷新或后端进程重启后的跨会话恢复仍不在本轮承诺内：当前演示工单与执行存储不是客户可恢复的持久化工单系统，也没有可从分析上下文反查 workflow 的契约。本轮没有把前端持久化缓存或虚构记录冒充恢复能力。
