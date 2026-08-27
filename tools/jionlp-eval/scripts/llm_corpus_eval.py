"""Empirical qualification of LLM-direct time resolution against the golden corpus.

Every convention the production system requires is stated explicitly in the
prompt, so we measure genuine parsing ability rather than guessing our rules.
Each row runs N=3 times independently; a row passes only when every run agrees.

Gates mirror design section 13:
  G1 explicit-time rows must resolve to the exact expected range, 3/3 runs
  G2 no-time/entity rows must return zero mentions, 3/3 runs
  G3 PR20 review seeds must pass 100%
"""
import json
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone, timedelta

import httpx

ENV_PATH = r"C:/Users/Henry/code/springaialibaba/.env"
CORPUS = "corpus/time_intent_golden.jsonl"
RUNS = 3
MODEL = "qwen-plus"

env = {}
for line in open(ENV_PATH, encoding="utf-8"):
    line = line.strip()
    if line and not line.startswith("#") and "=" in line:
        k, v = line.split("=", 1)
        env[k] = v.strip()
URL = env["SPRING_AI_DASHSCOPE_BASE_URL"].rstrip("/") \
    + env["SPRING_AI_DASHSCOPE_CHAT_COMPLETIONS_PATH"]
HEADERS = {"Authorization": f"Bearer {env['AI_DASHSCOPE_API_KEY']}"}

PROMPT = """你是一个严格的时间表达式解析器。根据用户问题、参考时刻和时区（Asia/Shanghai），解析出精确的 UTC 半开区间 [from, to)。

必须遵守以下约定：
1. 日历边界按 Asia/Shanghai 本地时间确定，再换算为 UTC。例如"2026年8月1日"指本地时间 8月1日00:00 至 8月2日00:00。
2. "A到B/至B"表示一个连续区间：从 A 的起点到 B 的结束点之后，不是两个独立区间。
3. "过去/最近/近N个小时/天/周/月"是从参考时刻向回推N个单位的滑动窗口 [参考时刻-N单位, 参考时刻)，不是完整日历日。
4. 进行中的周期（今天/本周/本月/本季度/今年）区间为 [周期开始, 参考时刻)；若参考时刻恰好等于周期开始的本地零点，则区间为零宽 [t,t)。
5. 无效日期（如2月30日）或起点不早于终点的区间：输出 {{"invalid": true}}，不要猜测。

只输出一个 JSON 对象，不要解释或代码块标记：
{{"mentions": ["逐字片段", ...], "ranges": [{{"from": "...Z", "to": "...Z"}}]}}
mentions 与 ranges 一一对应且每个 mention 必须逐字出现在原问题中；无时间表达时两者均为空数组。
所有时间都用 Asia/Shanghai 本地时间书写并带 +08:00 偏移，如 "2026-08-15T00:00:00+08:00"，禁止自行换算成其他时区。

参考时刻：{ref}
当前本地时间：{local}
今天是{today}，本周一是{monday}。
用户问题：{q}"""

lock = threading.Lock()
stats = {"calls": 0}


WEEKDAYS = ["周一", "周二", "周三", "周四", "周五", "周六", "周日"]


def local_context(ref: str) -> tuple[str, str, str]:
    dt = datetime.fromisoformat(ref.replace("Z", "+00:00"))
    local = dt.astimezone(timezone(timedelta(hours=8)))
    readable = local.strftime("%Y年%m月%d日 %H:%M")
    weekday = WEEKDAYS[local.weekday()]
    monday = (local - timedelta(days=local.weekday())).strftime("%Y年%m月%d日")
    return readable, weekday, monday


def call_llm(question: str, ref: str, excluded=None) -> str | None:
    readable, weekday, monday = local_context(ref)
    note = ""
    if excluded:
        spans = ", ".join(
            f"[{s['start']},{s['end']})" for s in excluded)
        note = ("\n以下原文下标区间是业务实体编号，不是时间表达，"
                f"请忽略它们：{spans}")
    content = PROMPT.format(ref=ref, q=question,
                            local=readable, today=weekday,
                            monday=monday) + note
    payload = {
        "model": MODEL,
        "input": {"messages": [{"role": "user", "content": content}]},
        "parameters": {"temperature": 0},
    }
    for attempt in range(4):
        try:
            r = httpx.post(URL, json=payload, headers=HEADERS, timeout=90)
            with lock:
                stats["calls"] += 1
            if r.status_code == 200:
                out = r.json().get("output", {})
                msg = out.get("choices")
                if msg:
                    return msg[0].get("message", {}).get("content")
                return out.get("text")
            if r.status_code in (429, 500, 502, 503):
                time.sleep(2 ** attempt)
                continue
            return None
        except httpx.HTTPError:
            time.sleep(2 ** attempt)
    return None


def parse_json(text: str | None) -> dict | None:
    if text is None:
        return None
    t = text.strip()
    if t.startswith("```"):
        t = t.strip("`")
        if t.startswith("json"):
            t = t[4:]
    start, end = t.find("{"), t.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(t[start:end + 1])
    except json.JSONDecodeError:
        return None


def norm(s) -> str | None:
    if not isinstance(s, str):
        return None
    try:
        dt = datetime.fromisoformat(s.strip().replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    except ValueError:
        return None


def evaluate_row(row: dict, results: list[dict | None]) -> dict:
    exp = row["expected"]
    explicit = row["category"] != "no_time_entity"
    exp_ranges = sorted(tuple(x) for x in exp["ranges"])
    per_run = []
    for res in results:
        if res is None:
            per_run.append({"ok": False, "why": "no-response"})
            continue
        mentions = [m for m in (res.get("mentions") or []) if isinstance(m, str)]
        ranges = sorted(
            p for p in (
                (norm(r.get("from")), norm(r.get("to")))
                for r in (res.get("ranges") or [])
                if isinstance(r, dict))
            if p[0] and p[1])
        grounded = all(m in row["question"] for m in mentions)

        if res.get("invalid"):
            # invalid/reversed expressions must be refused, never guessed
            ok = exp["status"] == "UNSUPPORTED"
            per_run.append({"ok": ok, "why": "" if ok else "refused-valid-expression"})
            continue
        if not explicit:
            ok = not ranges and not mentions
            per_run.append({"ok": ok, "why": "" if ok else "hallucinated-time"})
            continue
        got = sorted(set(ranges))
        if got != exp_ranges:
            per_run.append({"ok": False, "why": f"range {got} != {exp_ranges}"})
        elif not grounded:
            per_run.append({"ok": False, "why": f"mention-not-grounded {mentions!r}"})
        elif any(a >= b for a, b in ranges):
            per_run.append({"ok": False, "why": "unordered-range"})
        else:
            per_run.append({"ok": True, "why": ""})
    return {"row": row, "runs": per_run,
            "pass": all(r["ok"] for r in per_run)}


def main() -> None:
    rows = [json.loads(l) for l in open(CORPUS, encoding="utf-8")]
    jobs = [(row, run) for row in rows for run in range(RUNS)]
    answers: dict[tuple[str, int], dict | None] = {}
    sem = threading.Semaphore(4)

    def work(job):
        row, run = job
        with sem:
            ans = call_llm(row["question"], row["referenceInstant"],
                           row["excludedSpans"])
            answers[(row["id"], run)] = parse_json(ans)

    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(work, jobs))

    evaluated = [evaluate_row(row, [answers[(row["id"], i)] for i in range(RUNS)])
                 for row in rows]
    g1 = [e for e in evaluated
          if e["row"]["category"] != "no_time_entity" and not e["pass"]]
    g2 = [e for e in evaluated
          if e["row"]["category"] == "no_time_entity" and not e["pass"]]
    pr20 = [e for e in evaluated if e["row"].get("pr20ReviewCase")]

    total = len(evaluated)
    print(json.dumps({
        "total_rows": total,
        "g1_explicit_failures": len(g1),
        "g2_no_time_failures": len(g2),
        "pr20_pass": len([e for e in pr20 if e['pass']]),
        "pr20_total": len(pr20),
        "overall_pass": total - len(g1) - len(g2),
        "llm_calls_made": stats["calls"],
    }, ensure_ascii=False))
    print("--- failures ---")
    for e in g1 + g2:
        first_bad = next((r for r in e["runs"] if not r["ok"]), None)
        print(f"{e['row']['id']} [{e['row']['expected']['status']}] "
              f"{e['row']['question']!r} -> {first_bad['why'][:160]}")


if __name__ == "__main__":
    main()
