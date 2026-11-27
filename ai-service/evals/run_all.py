"""汇总评测：依次跑各项评测，输出一张总表，并把结果写进 evals/results/<日期>.json，方便前后对比。

前置：Java 后端、Redis（已有索引）、ai-service（rules_keyword 走 HTTP）在运行，配置了 DEEPSEEK_API_KEY。
用法：AI_EVAL_TOKEN=<用户 token> uv run python -m evals.run_all [--base http://localhost:8090] [--skip goods_retrieval]
某一项失败不影响其他项，失败原因记在结果文件里。
"""
import argparse
import asyncio
import datetime
import json
import os
import pathlib
import subprocess
import traceback
from collections.abc import Awaitable, Callable
from typing import Any

from app.config import get_settings
from evals import judge, run_retrieval, run_rules, run_rules_retrieval, run_trajectory

RESULTS = pathlib.Path(__file__).with_name("results")


def suites(args: argparse.Namespace, token: str) -> dict[str, Callable[[], Awaitable[dict[str, Any]]]]:
    return {
        "rules_keyword": lambda: asyncio.to_thread(run_rules.evaluate, args.base, token, args.interval),
        "rules_judge": lambda: judge.evaluate(token),
        "trajectory": lambda: run_trajectory.evaluate(token),
        "rules_retrieval": run_rules_retrieval.evaluate,
        "goods_retrieval": lambda: run_retrieval.evaluate(token),
    }


def git_commit() -> str:
    try:
        return subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True,
                              check=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def table(results: dict[str, dict[str, Any]]) -> str:
    rows = ["| 评测 | 指标 | 结果 |", "| --- | --- | --- |"]
    for name, r in results.items():
        if "error" in r:
            rows.append(f"| {name} | 失败 | {r['error'].splitlines()[-1][:80]} |")
            continue
        for metric, value in r["summary"].items():
            shown = f"{value:.1%}" if isinstance(value, float) and metric != "cases" and "mrr" not in metric else value
            rows.append(f"| {name} | {metric} | {shown} |")
    return "\n".join(rows)


async def run(args: argparse.Namespace) -> None:
    token = os.environ["AI_EVAL_TOKEN"]
    results: dict[str, dict[str, Any]] = {}
    for name, fn in suites(args, token).items():
        if name in args.skip:
            continue
        print(f"\n===== {name} =====")
        try:
            results[name] = await fn()
        except Exception:
            traceback.print_exc()
            results[name] = {"error": traceback.format_exc()}
    s = get_settings()
    today = datetime.date.today().isoformat()
    RESULTS.mkdir(exist_ok=True)
    out = RESULTS / f"{today}.json"
    out.write_text(json.dumps({
        "date": today, "commit": git_commit(), "model": s.deepseek_model,
        "judge_model": s.ai_eval_judge_model or s.deepseek_model, "embedding": s.embedding_model,
        "rerank": s.rerank_model or None, "results": results,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("\n" + table(results))
    print(f"\n结果已写入 {out}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://localhost:8090", help="ai-service 地址（rules_keyword 用）")
    parser.add_argument("--interval", type=float, default=6.5, help="rules_keyword 每题间隔，避开限流")
    parser.add_argument("--skip", nargs="*", default=[], help="跳过的评测项")
    asyncio.run(run(parser.parse_args()))


if __name__ == "__main__":
    main()
