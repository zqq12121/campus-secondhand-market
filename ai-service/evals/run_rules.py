"""规则问答评测：经 /chat 接口端到端提问，按关键事实与出处判分。

前置：ai-service 与 Java 后端在运行。
用法：AI_EVAL_TOKEN=<用户 token> uv run python -m evals.run_rules [--base http://localhost:8090]
判分：must / also 每组用 | 分隔的备选词至少命中一个；可回答的问题还要求带「来源」。
"""
import argparse
import json
import os
import pathlib
import time

import httpx

CASES = pathlib.Path(__file__).with_name("rules.jsonl")


def ask(base: str, token: str, question: str) -> tuple[str, list[str]]:
    text: list[str] = []
    tools: list[str] = []
    event = ""
    with httpx.stream("POST", f"{base}/api/v1/ai/chat", json={"message": question}, timeout=120,
                      headers={"Authorization": f"Bearer {token}"}) as r:
        r.raise_for_status()
        for line in r.iter_lines():
            if line.startswith("event: "):
                event = line[7:]
            elif line.startswith("data: "):
                data = json.loads(line[6:])
                if event == "token":
                    text.append(data)
                elif event == "tool" and data["status"] == "start":
                    tools.append(data["name"])
                elif event == "error":
                    raise RuntimeError(data)
    return "".join(text), tools


def matches(answer: str, groups: list[str]) -> bool:
    return all(any(alt in answer for alt in g.split("|")) for g in groups)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8090")
    # 默认每分钟限流 10 次，评测时按间隔发送
    parser.add_argument("--interval", type=float, default=6.5)
    args = parser.parse_args()
    token = os.environ["AI_EVAL_TOKEN"]
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    correct = cited = 0
    for i, c in enumerate(cases):
        if i:
            time.sleep(args.interval)
        answer, tools = ask(args.base, token, c["question"])
        ok = matches(answer, c["must"] + c.get("also", []))
        has_source = "来源" in answer
        correct += ok
        cited += has_source or c.get("unanswerable", False)
        flag = "OK " if ok else "BAD"
        print(f"{flag} {'[src]' if has_source else '[---]'} {c['question']}  tools={tools}")
        if not ok:
            print("    " + answer.replace("\n", " ")[:300])
    n = len(cases)
    print(f"cases={n}  正确率={correct / n:.0%}  带出处={cited / n:.0%}")


if __name__ == "__main__":
    main()
