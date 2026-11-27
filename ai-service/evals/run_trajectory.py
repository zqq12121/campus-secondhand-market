"""Agent 轨迹评测：走真实的图，按 evals/trajectory.jsonl 里的约束检查工具调用序列（判定逻辑见 evals/trajectory.py）。

前置：Java 后端在运行、Redis 里已有索引、配置了 DEEPSEEK_API_KEY。
用法：AI_EVAL_TOKEN=<用户 token> uv run python -m evals.run_trajectory
"""
import asyncio
import json
import os
import pathlib
from typing import Any

from evals.harness import Agent
from evals.trajectory import check, extract

CASES = pathlib.Path(__file__).with_name("trajectory.jsonl")


def load_cases() -> list[dict[str, Any]]:
    return [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]


async def evaluate(token: str) -> dict[str, Any]:
    agent = await Agent.open(token)
    cases = load_cases()
    by_category: dict[str, list[bool]] = {}
    failed: list[dict[str, Any]] = []
    try:
        for c in cases:
            steps, answer = extract(await agent.ask(c["question"]))
            failures = check(c, steps, answer)
            by_category.setdefault(c["category"], []).append(not failures)
            print(f"{'OK ' if not failures else 'BAD'} [{c['category']}] {c['question']}  tools={[s.name for s in steps]}")
            for f in failures:
                print(f"    {f}")
            if failures:
                failed.append({"id": c["id"], "tools": [s.name for s in steps], "failures": failures})
    finally:
        await agent.aclose()
    passed = sum(sum(v) for v in by_category.values())
    summary = {"cases": len(cases), "pass_rate": round(passed / len(cases), 3),
               **{f"pass_rate[{k}]": round(sum(v) / len(v), 3) for k, v in by_category.items()}}
    print("  ".join(f"{k}={v}" for k, v in summary.items()))
    return {"summary": summary, "failed": failed}


def main() -> None:
    asyncio.run(evaluate(os.environ["AI_EVAL_TOKEN"]))


if __name__ == "__main__":
    main()
