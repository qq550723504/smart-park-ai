# Issue #73 浏览器与在线 AI 验证

验证日期：2026-09-10（Asia/Singapore）

## 环境与能力

- 基线：`main@291dbd3be0ddfdc417ff4c801feaff2b15a95611`
- 独立分支：`codex/issue-73-customer-demo`
- 独立本地 Compose 项目：`smartpark-issue73`
- 客户前端：`http://127.0.0.1:5273/`
- 后端：`http://127.0.0.1:8083/`
- 浏览器：Codex 应用内 Chromium
- 能力快照：`analyticsEnabled=true`、`knowledgeMode=rag`、`customerAnswerMode=dashscope`、`voiceEnabled=false`

凭据仅由仓库外现有 `.env` 注入，没有写入代码、文档、Issue、截图或命令输出。此环境连接在线模型，但园区业务事实仍是 Compose 确定性演示数据，不是生产遥测；本次没有部署。

## 在线 AI 成功验收

客户页对 `B2 · 研发大厦` 显式点击“运行 AI 分析”，问题与页面使用同一精确窗口：

`building_id=B2 从 2026-09-09T13:00:00.000Z 到 2026-09-10T13:00:00.000Z 的能耗基线偏差率`

脱敏运行 `a9a7be3c-b692-4ffd-a688-768fa93a896b` 最终为 `COMPLETED`：

- 时间来源 `EXPLICIT_USER_RANGE`，实际查询窗口与页面一致；
- 安全 SQL 计划包含 `building_id = :filter_building_id`，参数名含 `filter_building_id`；
- 返回 1 行 `energy_deviation_pct=23.68`；
- 页面显示非空结论“building_id=B2 的能耗基线偏差率为23.68%。”。

修复前脱敏运行 `b6a24991-3935-40c9-826c-56ae5fd94cab` 在 `understandQuestion` 失败。复现后确认根因是模型返回两个精确 ISO 端点，而确定性解析器返回一个原子区间；不是凭据失败。修复只接受完整的精确端点对，并保留其他不一致的失败关闭行为。

## 同一浏览器会话连续路径

1. 总览选择 B2，进入运营分析并取得上述在线 AI 结论。
2. 返回总览，选择 B1；等待事件依据完成后进入分析和工单页。
3. 对 `ALT-ORCH-ENERGY-B1-001` 人工确认，现有告警工作流返回 `WO-0001`、状态“已创建，待处理”；再次执行取得同一幂等回执。
4. 从工单页点击“继续查看运营报告”，打开现有不可变快照；页面没有声称工单已被报告自动收录。
5. 生成的报告 `4a639c1c-bf1e-4576-bb0f-577bc7660572` / run `bbe624db-70a6-4f2b-9ff1-b82353077b48` 为 `COMPLETED`，三个章节均完成，Markdown 产物大小 1887 字节，下载可用。

四次切页均只有一个 `CustomerShell` 和一个顶部导航。没有进入内部技术工作台；导航切换本身没有创建新的分析或报告任务。

## AI 助手

- 顶部入口可打开客户抽屉，不嵌入内部客服控制台。
- 推荐问题和页面上下文只写入草稿；浏览器核对草稿后消息数仍为 0。
- 停车问题通过现有客服会话 API 返回在线回答与“访客停车指南”来源。
- 另一真实回答触发知识不足边界时，页面显示客服工单 `CS-0001`、等待客服接入，并明确“不表示问题已经解决”。
- 关闭抽屉后焦点回到“AI 助手”；跨页重开保留会话与草稿。
- 显式“重开导览”只清理前端选择、草稿和会话展示，页面明确后台工单、报告和运行没有删除。

## 视口与截图

| 视口 | 检查结果 | 证据 |
| --- | --- | --- |
| 1440×900 | 四页主路径、在线结论、真实工单和报告均可见；无横向溢出 | `01`—`05` 截图 |
| 1920×1080 | 四页保持统一密度、卡片层级与客户导航；无横向溢出 | `06`—`10` 截图 |
| 1366×768 | 四页逐页检查 `scrollWidth <= innerWidth`，均只有一个 Shell/顶部栏；助手草稿与发送区无遮挡 | `11-assistant-draft-1366x768.png` |

截图目录：`docs/evidence/issue-73/`。原始四张设计稿尚未进入仓库或 Issue；像素级原图对照仍需持有交接包的评审者在本 PR 验收时完成，详见 `docs/issue-73-design-qa.md`。

## 自动化验证

- 前端定向回归：3 个测试文件、20 个测试通过。
- 前端全量：50 个测试文件、516 个测试通过。
- `npm run typecheck`：通过。
- `npm run build`：通过；仅保留既有 Vite 大 chunk 警告。
- 后端定向：`TimeEvidenceReconcilerTest`、`LlmAnalyticsModelClientTest`、`AnalysisSummaryValidatorTest` 通过。
- 后端全量：1331 个测试，0 失败、0 错误、3 跳过，`BUILD SUCCESS`。

## 未扩大范围

- 没有新增报告、客服、AI 平台或生产基础设施。
- 没有邮件、通知、定时任务、PDF、数据删除或复杂回放。
- 没有合并或部署；#68 继续保持 OPEN。
