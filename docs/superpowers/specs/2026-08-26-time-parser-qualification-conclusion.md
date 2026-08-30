# 时间解析器资格验证结论（JioNLP 与 Duckling 双候选）

> 状态：**两个候选均未通过资格门槛**，依据设计 §13/§16 停止运行时接入，
> 返回设计评审。本文档为可复查结论；黄金语料与契约测试已入库，可直接复用于
> 后续任何候选评估。

## 1. 验证范围与方法

- 分支：`codex/jionlp-time-intent-design`
- 语料：`time-parser/corpus/time_intent_golden.jsonl`，共 **120 行**
  （25 绝对日期 / 25 滚动量词 / 20 组合区间 / 15 多范围歧义 /
  15 无时间与实体 identifier / 20 边界非法反向），含全部 **15 个 PR20 审查种子**。
- 判定：期望值来自 JioNLP 参考实现的实际输出，逐行人工复核语义后固化；
  每行断言 status、mention 文本、精确 span、半开区间、reasonCode。
- 安全门槛（设计 §13）：
  1. 显式时间被判为 `NONE` 的数量 = 0；
  2. 截断/非法表达式被误收为 `PARSED` 的数量 = 0；
  3. 无时间问题与实体 identifier 产生时间范围的数量 = 0；
  4. PR20 种子 100% 通过；
  5. 相同输入重复运行结果一致；
  6. 全部 span 与原文逐字符一致。

## 2. 候选一：JioNLP 1.5.29 —— 许可证门槛失败

| 关卡 | 结果 |
|---|---|
| 正确性（120 行语料 + 契约测试，18 个 pytest） | ✅ 全部通过 |
| 安全漏洞（pip-audit --strict） | ✅ 无已知漏洞 |
| 许可证白名单（Apache-2.0 兼容） | ❌ **失败** |

正确性关卡仍由原有 18 个契约/语料测试构成；当前复现命令还会执行 13 个依赖边界与
许可证策略回归测试，因此完整 `pytest` 套件共 31 项。

失败证据：

```text
DISALLOWED LICENSE: certifi -> Mozilla Public License 2.0 (MPL 2.0)
DISALLOWED LICENSE: jiojio -> GNU General Public License v3 (GPLv3)
```

`jionlp==1.5.29` 的必需依赖链为 `numpy / jiojio / requests / zipfile36`，
其中分词内核 `jiojio` 仅以 GPLv3 分类器发布（PyPI 元数据核实）。GPLv3 不在
Apache-2.0 兼容白名单内；本项目以 Apache-2.0 生态分发容器镜像，构成许可证污染。
无更新版本可规避（1.5.29 即最新版，仍依赖 jiojio）。

参考实现产出（保留于分支，供后续复用）：
`app.py`（含 code-point 偏移、半开区间换算、组合端点限定词继承、截断防护、
当前周期封顶与 EMPTY 判定）、锁定依赖文件、契约与语料测试、验证脚本。

## 3. 候选二：Duckling（上游 master，zh_CN）—— 正确性门槛失败

构建方式：官方仓库源码镜像本地编译（rasa/duckling 官方镜像未编译 ZH 模块，
对中文请求直接重置连接，无法参与评估）。上游确认包含
`Duckling/Time/ZH/{CN,HK,MO,TW}` 维度。

用同一份 120 行语料 + 相同 reference time 对齐评测：

| 指标 | 结果 |
|---|---|
| mention span 与语料完全一致的行 | 32 / 120 |
| 显式时间行被完全漏检（等价于判 NONE） | **37 行** → 门槛 1 失败 |
| 区间值错误或实体泄漏的行 | **71 行** → 门槛 1/3 失败 |
| PR20 种子通过率 | **1 / 15** → 门槛 4 失败 |

典型语义错误：

| 问题 | Duckling 输出 | 正确期望 |
|---|---|---|
| 过去24个小时能耗 | 无任何识别 | [now-24h, now) |
| 本周能耗 | 无任何识别 | [周一00:00, now) |
| 本月15号能耗 | 解析为 **9月15日** | 本月（8月）15日 |
| 2026年上半年能耗 | 解析为 **2026-02-26** | [2026-01-01, 2026-07-01) |
| 今天上午能耗 | 只识别“今天”，丢失“上午” | 当日上午半开区间 |

## 4. 结论

依设计 §13：“若两个候选都失败，回到设计评审，不自行建设第三套私有完整语法。”
因此：

- **不接入 JioNLP**（GPLv3 依赖不可豁免时）；
- **不接入 Duckling**（中文正确性远低于门槛）;
- **不将有限正则语法恢复为运行时实现**；
- Tasks 2–6（Java 接入、协调器、EMPTY 语义、Compose 打包、旧语法退役）
  在候选确定前不得开工。

## 5. 提交设计评审的可选项

1. **许可证例外评审**：由项目法务/治理层裁决“独立 sidecar 容器内含未修改
   GPLv3 库、且仅进程间 HTTP 调用”是否可接受。接受则 JioNLP 路径的全部
   工程产物已就绪，可直接续做 Task 2。
2. **替换解析内核**：保持本设计的 sidecar HTTP 契约与 Java 边界不变，
   仅更换内核（如自研受控规则库 + 黄金语料准入，或商用授权解析器）。
   语料与测试即为准入工具。
3. **收缩支持面**：若产品侧可接受仅支持绝对日期与少量固定表达，可重新
   定义更小的语料集合后再评 Duckling 或其他轻量方案。

无论选择哪条路径，本分支上的 120 行语料、契约测试与验证脚本均为最终方案的
强制组成部分。

## 6. 复现步骤

```powershell
cd time-parser
py -3.12 -m venv .venv
./.venv/Scripts/python -m pip install pip==24.3.1 pip-tools==7.6.1
powershell -ExecutionPolicy Bypass -File scripts/compile-requirements.ps1 -Python ./.venv/Scripts/python
./.venv/Scripts/python -m pip install --require-hashes -r requirements-dev.txt
./.venv/Scripts/python -m pytest tests -q          # JioNLP 正确性 ✅
powershell -ExecutionPolicy Bypass -File scripts/verify-time-intent-corpus.ps1
# → 许可证关卡失败（jiojio GPLv3）

docker build -t duckling-upstream:zh <duckling 源码目录>   # 见附录补丁说明
docker run -d -p 127.0.0.1:8000:8000 duckling-upstream:zh
# 用上节脚本对 120 行语料逐一 POST /parse?locale=zh_CN 对比
```
