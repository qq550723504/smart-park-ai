# GPLv3 许可证例外声明（隔离目录）

本目录 `tools/jionlp-eval/` 是 **smart-park-ai** 的**离线评测 / 资格验证工具**，**不是生产代码**，也**不会被打包进任何发布镜像**。

## 为何需要隔离

- 本目录依赖 `jionlp==1.5.29`，而 JioNLP 传递依赖 `jiojio`，后者以 **GPLv3** 发布。
- GPLv3 是强 copyleft 许可证。若将其纳入主仓库的生产构建路径，会对主仓库的 Apache-2.0 许可证产生传染性风险。
- PR #27 的资格审查表已明确记录：JioNLP 因 GPLv3 依赖链**未通过许可证门槛**，生产实现改用纯 Java 确定性白名单 `WhitelistTimeIntentProvider`，不含任何 Python / JioNLP 依赖。

## 隔离边界（硬性约束）

1. 本目录**仅用于本地离线复跑 120 行黄金语料**（`corpus/time_intent_golden.jsonl`），验证时间意图解析的正确性、安全性与许可证。
2. 生产后端 **不引用、不依赖** 此目录：Java 侧的时间区间换算完全由 `WhitelistTimeIntentProvider` 自包含实现，无网络/子进程调用 Python sidecar。
3. `compose.yaml` 与 `compose.analytics.yaml` **均未**将本目录构建为服务，也不发布任何主机端口。
4. CI 的构建/测试矩阵**不得**安装 `requirements.txt` 或将本目录加入生产镜像层。
5. 如需重新资格验证，请在隔离环境中手动执行：
   ```powershell
   cd tools/jionlp-eval
   python -m venv .venv && .venv\Scripts\Activate.ps1
   pip install -r requirements.txt
   python -m pytest tests/test_contract.py tests/test_golden_corpus.py -q
   ```

## 合规结论

本目录以「离线评测工具」身份保留在主仓库中，并受上述隔离边界约束。它**不**构成对 Apache-2.0 主代码的许可证传染，前提是上述边界不被破坏。任何将其接入生产构建的改动都必须先移除 GPLv3 依赖（例如改用 Duckling 或自研内核）并重新通过资格审查。
