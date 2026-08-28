# JioNLP 时间意图解析设计

## 1. 背景

PR #20 将分析查询的时间范围收回服务端管理，避免大模型直接决定 SQL 的时间边界。
这个方向正确，但首个实现 `FiniteGrammarTimeIntentProvider` 仍通过两套有限正则工作：

1. 候选正则识别已支持的时间表达式；
2. 时间提示正则检查候选之外是否残留时间限定词。

只要两套正则同时遗漏一种中文表达，系统就会把显式时间错误分类为 `NONE`，随后使用指标默认
lookback。PR #20 的 15 个审查线程反复暴露了相同结构性缺陷，包括量词变体、组合区间、
未消费端点、日期形状实体以及当前周期边界。

本设计用成熟开源中文时间解析器替换私有有限语法，同时保留服务端时间所有权、SQL 前
fail-closed 和可审计时间来源。

## 2. 已批准决策

- analytics 运行链可以增加一个仅内部访问的 Python/JioNLP sidecar。
- JioNLP 是首选候选，固定评估版本为 `1.5.29`，许可证为 Apache-2.0。
- JioNLP 必须先通过项目黄金语料准入，不能因通用项目声称的准确率直接上线。
- 大模型只提供原文时间 span 证据，不提供或覆盖最终时间戳。
- JioNLP 不可用、输出非法或与模型时间证据冲突时必须 fail-closed。
- 旧 `FiniteGrammarTimeIntentProvider` 不得成为运行时 fallback。
- 当前周期在边界时刻为空时返回显式 `EMPTY`，不伪造非空范围。

## 3. 目标

1. 显式时间表达式不能因词形或组合方式未知而静默进入默认 lookback。
2. 最终查询时间范围只来自服务端控制的解析链和时钟。
3. 解析器可以替换，图谱、查询计划和 SQL 边界不依赖 JioNLP 私有返回格式。
4. 所有不确定、多范围和不完整表达式都在 SQL 生成前终止。
5. 用户可以看到实际使用的时间范围及其来源。
6. 通过项目语料和契约测试控制第三方解析器升级风险。

## 4. 非目标

- 不承诺支持所有中文自然语言时间表达式。
- 不在本设计中支持周期性计划，例如“每周一上午九点”。
- 不让大模型生成最终 `Instant` 或绕过服务端解析。
- 不为 JioNLP 未覆盖的表达式继续建设另一套完整私有时间语法。
- 不在本设计阶段部署到生产、回复 GitHub 评论或解决远程线程。
- 不重构时间解析之外的指标、维度、SQL 生成或执行链。

## 5. 方案比较

### 5.1 采用 JioNLP sidecar（推荐）

JioNLP（https://github.com/dongrixinyu/JioNLP）同时提供中文时间实体抽取和完整时间表达式解析，
采用 Apache-2.0 许可证，适合作为
独立解析内核。代价是增加 Python 容器、内部 HTTP 契约和运行健康管理。

### 5.2 采用 Duckling sidecar

Duckling（https://github.com/facebook/duckling）提供组合规则、原文 span 和结构化时间值，
采用 BSD 许可证。它需要 Haskell 运行时，
中文语料质量必须用相同黄金语料验证。仅当 JioNLP 未通过安全准入时评估该方案。

### 5.3 继续扩展 Java 正则

改动最小，但不能证明 `NONE` 代表原文没有时间语义，已经被连续审查反例否定。本方案不再采用。

## 6. 总体架构

```text
原始问题
  |-- QuestionTokenScanner --------> 排除实体 identifier span
  |-- JioNLP sidecar --------------> ParserEvidence
  |-- LLM question understanding --> ModelTimeEvidence（只含原文 span）
  `-- TimeEvidenceReconciler -------> TimeIntentResult
                                          |
                                          v
                                TimeConstraintResolver
                                          |
                    +---------------------+---------------------+
                    |                     |                     |
              PARSED / NONE            EMPTY       UNSUPPORTED / MULTIPLE /
                    |                     |          AMBIGUOUS / provider error
                    v                     v                     v
                QueryPlan          空分析结果，无 SQL          SQL 前终止
```

现有 `TimeIntentProvider`、`TimeIntentResult`、`TimeConstraintResolver` 和
`QueryPlan.TimeRangeSource` 继续作为 Java 侧兼容边界。JioNLP 的具体字段只能出现在 sidecar
adapter 内，不进入图谱或查询模型。

## 7. 组件职责

### 7.1 JioNLP time-parser sidecar

sidecar 使用 `jionlp==1.5.29` 的时间实体抽取与解析能力，职责仅包括：

- 根据原始问题提取时间 span；
- 使用请求提供的参考时刻和时区解析时间语义；
- 返回原始 span、解析类型、准确度和规范化半开区间；
- 报告未解析、模糊、多结果或空当前周期；
- 过滤调用方传入的 excluded spans。

sidecar 不连接数据库、不调用大模型、不访问公网，也不决定指标默认 lookback。

### 7.2 `JioNlpTimeIntentProvider`

Java adapter 负责：

- 从 `QuestionTokenScanner` 获取实体 identifier spans；
- 将 Java UTF-16 下标转换为 sidecar 契约规定的 Unicode code point 下标；
- 调用 sidecar 并设置确定的超时；
- 将响应下标转换回 Java UTF-16 下标，并验证 span 与原问题逐字符一致；
- 再次拒绝与实体 span 重叠的时间 mention；
- 验证提供器版本、时区、范围顺序和 `EMPTY` 标记；
- 将 sidecar DTO 映射为项目的 `TimeIntentResult`；
- 将网络错误、超时和非法响应映射为提供器失败，而不是 `NONE`。

### 7.3 模型时间证据

`QuestionUnderstanding` 增加 `requestedTimeMentions`：

```text
["上周一到周三", "本月"]
```

模型只返回原始问题中逐字存在的时间片段，不负责计算字符下标。Java 在原始问题中定位每个片段的
全部精确出现位置，得到 `ModelTimeEvidence`；片段不存在时视为模型理解响应非法。这样避免让模型
计算偏移，也避免 Java UTF-16 与 Python Unicode code point 下标不一致。

模型不得继续为查询计划提供权威
`requestedTimeRange`；兼容迁移期间旧字段可以被读取并记录，但不能进入最终范围计算，随后删除。

### 7.4 `TimeEvidenceReconciler`

协调器只处理 JioNLP 证据与模型 span 的一致性，不解析自然语言：

- JioNLP 精确解析且模型相符或未标注：接受 JioNLP 结果；
- JioNLP 未发现时间且模型也未标注：返回 `NONE`；
- 模型标注了时间但 JioNLP 未发现或无法解析：返回 `UNSUPPORTED`；
- 模型标注了 JioNLP 未覆盖的额外 span：返回 `AMBIGUOUS` 或 `MULTIPLE`；
- 多个 mention 解析到相同绝对区间：去重为一个共享范围；
- 多个 mention 解析到不同绝对区间：返回 `MULTIPLE`。

模型 span 是独立的遗漏检测证据，不是时间值的权威来源。

“相符”严格定义为 Java 定位后的模型 span 与某个 parser mention 的 `start`、`end` 和原文 `text`
全部相等。
模型只标注 mention 的前缀、后缀或重叠子串时不视为相符；这类情况返回 `AMBIGUOUS`，不能接受
parser 的截断结果。parser 可以比模型发现更多 mention，但多出的 mention 必须参与最终单一/多范围判定。

### 7.5 `TimeConstraintResolver`

解析完成后的状态转换保持集中：

- `NONE`：使用指标默认 lookback，来源为 `DEFAULT_METRIC_LOOKBACK`；
- `PARSED`：使用解析器确定的精确范围，来源为 `EXPLICIT_USER_RANGE`；
- `EMPTY`：直接产生空分析结果，不构造 `QueryPlan`，不调用 SQL 生成或执行；
- 其他状态：抛出安全的用户可理解错误，SQL 前终止。

## 8. Sidecar HTTP 契约

sidecar 只在内部网络提供版本化接口：

```http
POST /v1/time-intents:resolve
Content-Type: application/json
```

请求：

```json
{
  "question": "上周一到周三能耗",
  "referenceInstant": "2026-08-25T00:00:00Z",
  "timezone": "Asia/Shanghai",
  "excludedSpans": []
}
```

`excludedSpans` 可以为空；非空时每一项都必须满足 `0 <= start < end <= question.length`。
本 HTTP 契约的 `start` 和 `end` 统一使用 Unicode code point 下标，`end` 为开区间。这里的
`question.length` 指 code point 数量，不是 UTF-8 字节数或 Java `String.length()`。

成功响应：

```json
{
  "provider": "jionlp",
  "providerVersion": "1.5.29",
  "referenceInstant": "2026-08-25T00:00:00Z",
  "timezone": "Asia/Shanghai",
  "status": "PARSED",
  "mentions": [
    {
      "text": "上周一到周三",
      "start": 0,
      "end": 6,
      "type": "time_span",
      "definition": "accurate",
      "fromInclusive": "2026-08-16T16:00:00Z",
      "toExclusive": "2026-08-19T16:00:00Z",
      "empty": false
    }
  ],
  "reasonCode": null
}
```

响应状态只允许 `NONE`、`PARSED`、`UNSUPPORTED`、`MULTIPLE`、`AMBIGUOUS` 和
`EMPTY`。每个已解析 mention 都携带自己的区间，Java 才能按绝对边界去重或判定多范围；
顶层不得提供另一个可能与 mention 冲突的区间。错误原因使用稳定的 `reasonCode`，不得把堆栈
或完整内部异常返回给 Java 或用户。

## 9. 判定矩阵

| ParserEvidence | ModelTimeEvidence | 最终状态 |
|---|---|---|
| 精确单一区间 | 相符或空 | `PARSED` |
| 无时间 | 空 | `NONE` |
| 无时间或无法解析 | 存在 span | `UNSUPPORTED` |
| 精确区间 | 存在未匹配的额外 span | `AMBIGUOUS` |
| 多个相同区间 | 相符或空 | 去重后 `PARSED` |
| 多个不同区间 | 任意 | `MULTIPLE` |
| 当前周期为空 | 相符或空 | `EMPTY` |
| sidecar 不可用或响应非法 | 任意 | provider error，分析失败 |

`NONE` 只有在两个独立证据源都没有发现时间时才能产生。即使两个来源共同遗漏仍无法获得形式化
完备性，因此最终结果还必须向用户明确展示默认时间范围和来源，禁止静默使用默认值。

## 10. `EMPTY` 语义

`今天`、`本周`、`本月`、`本季度` 或 `今年` 在各自起始边界可能形成 `[t, t)`。
这不是非法时间表达式，也不应该通过人为增加一秒来伪造数据窗口。

处理规则：

- sidecar 返回 `EMPTY`，唯一 mention 满足 `fromInclusive == toExclusive` 和 `empty == true`；
- Java 校验相等边界只允许出现在 `EMPTY`；
- 图谱返回结构化空结果和“当前周期刚开始，暂无数据”的说明；
- 不构造 `QueryPlan`，不调用 SQL 生成模型、成本检查或执行器；
- 非 `EMPTY` 状态继续要求严格的 `from < to`。

## 11. 用户可见时间来源

分析结果和 SSE 完成事件增加兼容性字段：

- `resolvedTimeRange`：实际使用的半开区间；
- `timeRangeSource`：`EXPLICIT_USER_RANGE` 或 `DEFAULT_METRIC_LOOKBACK`；
- `timeRangeExplanation`：面向用户的短说明。

字段只做增量添加，不删除或重命名现有字段。没有显式时间时必须展示类似：

```text
未指定时间范围，本次按 energy_kwh 指标默认最近 7 天分析。
```

## 12. 故障与安全

- sidecar 超时、连接失败、5xx、非法 JSON、版本不符均视为 provider error；
- provider error 不得转为 `NONE`，也不得调用旧正则；
- sidecar 不健康时 analytics capability 标记为不可用；
- HTTP 服务只绑定内部容器网络，不发布宿主机端口；
- 容器使用非 root 用户、只读文件系统和最小依赖；
- Python 基础镜像、JioNLP 和传递依赖固定版本并生成 SBOM；
- 资格验证阶段必须检查许可证和已知高危漏洞；
- 日志、指标标签和健康响应不得包含完整用户问题或实体值。

## 13. 黄金语料与准入门槛

黄金语料至少包含 120 条，全部使用固定 `referenceInstant` 和 `Asia/Shanghai`：

- 25 条绝对日期、月份、季度、半年和当前周期；
- 25 条滚动时长、中文数字、量词和同义词；
- 20 条组合时间 span；
- 15 条多范围、歧义和暂不支持表达式；
- 15 条无时间问题、业务词和日期形状实体 identifier；
- 20 条零点、周/月/季/年边界、非法日期和反向区间。

PR #20 全部 15 个审查案例是强制种子，不因分类重叠而省略。

安全准入要求：

- 显式时间语料错误返回 `NONE` 的数量为 0；
- `UNSUPPORTED`、`MULTIPLE`、`AMBIGUOUS` 被截断为 `PARSED` 的数量为 0；
- 无时间问题和实体 identifier 产生时间范围的数量为 0；
- PR #20 全部回归案例 100% 通过；
- 相同输入、参考时刻和时区重复运行必须得到相同结果；
- 所有返回 span 必须与原始问题精确对应。

任一安全门槛失败即停止 JioNLP 运行时接入，使用同一语料评估 Duckling。若两个候选都失败，
回到设计评审，不自行建设第三套私有完整语法。

## 14. 测试策略

### 14.1 Sidecar 契约测试

- span 偏移、时区、参考时刻和版本字段；
- 半开区间转换；
- `EMPTY` 与严格非空范围；
- 无效 excluded span、非法请求和安全 reason code；
- JioNLP 升级前后的黄金语料差异。

### 14.2 Java provider 与协调器测试

- identifier 排除和响应二次验证；
- 模型 span 必须来自原问题；
- 相同范围去重、不同范围拒绝；
- sidecar 超时、断连、非法响应和版本不匹配；
- provider error 不得降级到旧实现或默认 lookback。

### 14.3 图谱和数据库测试

- `UNSUPPORTED`、`MULTIPLE`、`AMBIGUOUS` 和 provider error 不调用 SQL 生成；
- `EMPTY` 不调用 SQL 生成、成本检查和执行；
- `NONE` 才能使用指标默认 lookback；
- `PARSED` 的 `fromTs`、`toTs` 与真实 PostgreSQL 绑定参数一致；
- 澄清暂停与恢复复用首次解析快照，不重新计算相对时间。

### 14.4 变形测试

- 增加或替换“个、号、日、星期、礼拜”等词形后，显式时间不得退化为 `NONE`；
- 给单一时间增加未消费端点后，不得保留被截断的 `PARSED`；
- 重复等价时间不得误判为多个不同范围；
- 把日期文本放入合法实体 identifier 后不得产生时间 mention。

## 15. 可观测性

增加低基数指标：

- `time_intent_requests_total{provider,status}`；
- `time_intent_reconciliation_total{result}`；
- `time_intent_provider_latency_seconds{provider}`；
- `time_intent_provider_errors_total{reason}`；
- `time_intent_provider_up{provider}`。

不得把问题文本、mention 文本、实体 ID 或时间戳放入指标标签。日志只记录 run ID、状态、
稳定 reason code 和 provider version。

## 16. 分阶段迁移

### 阶段 A：资格验证 PR

- 加入固定版本 JioNLP 测试容器和黄金语料；
- 运行契约、准确性、许可证和安全检查；
- 不改变生产分析行为；
- 形成 JioNLP 准入或转评 Duckling 的可复查结论。

### 阶段 B：可独立部署的来源切换 PR

- 加入 sidecar 镜像、内部网络、健康检查和运行配置；
- 实现 `JioNlpTimeIntentProvider`、模型 span、协调器和 `EMPTY`；
- 增加用户可见时间来源字段；
- JioNLP 成为唯一运行时解析来源；
- sidecar 不健康时关闭 analytics capability，不回退旧正则；
- 完成后端全量、前端契约、Compose 和真实 PostgreSQL 验证。

### 阶段 C：清理 PR

- 删除 `FiniteGrammarTimeIntentProvider` 及其私有规则测试；
- 删除模型 `requestedTimeRange` 兼容字段；
- 保留 provider 契约、黄金语料和防回退测试；
- 不包含其他 analytics 重构。

每个阶段必须独立提交、独立验证，并明确区分本地测试、远程 CI、评论状态、合并、部署和业务验收。

## 17. 兼容与运行边界

- REST/SSE 只增加字段，不破坏现有消费者；
- `QueryPlan.TimeRange` 继续只表示非空严格有序范围；
- `EMPTY` 在进入 `QueryPlan` 前终止，避免改变所有 SQL 不变量；
- 澄清恢复继续保存服务器解析的绝对时间快照；
- 资格验证、合并、部署和远程评论处理分别授权；
- PR #20 已合并，后续修改通过新的 `codex/` 分支和 follow-up PR 交付。

## 18. 验收标准

设计完成后的实现只有同时满足以下条件才可称为根因修复：

1. JioNLP 或替代候选通过全部黄金语料安全门槛；
2. 运行时不存在有限正则 fallback；
3. 模型不能向查询计划注入最终时间戳；
4. 显式时间不可能在已知语料中静默变成默认 lookback；
5. provider 故障和证据冲突均在 SQL 前终止；
6. `EMPTY` 不产生非法或伪造 SQL 范围；
7. 用户可以看到最终时间范围及来源；
8. 当前 follow-up PR 的本地验证、远程 CI 和审查线程状态分别有最新证据；
9. 未经单独授权不合并、不部署、不修改 GitHub 评论状态。
