"""规则检索评测：对比 向量 / 关键词 / 混合 / 混合+重排 四种方式的 top1、top3 命中率和 MRR。

前置：Redis 里已有规则索引（ai-service 启动过一次即可）；不需要 Java 后端和 DeepSeek。
用法：uv run python -m evals.run_rules_retrieval
命中 = 片段同时包含用例 must 里每组备选词中的至少一个（与 README 的粗判口径一致）；跳过 unanswerable 用例。
embedding / 重排序按 .env 配置走本地模型或 TEI；未配置重排序时跳过「混合+重排」。
"""
import asyncio
import json
import pathlib
from typing import Any

from redis.asyncio import Redis

from app.config import get_settings
from app.java_client import JavaClient
from app.deps import build_embedder, build_reranker
from app.rag.rules_index import RulesIndex

TOP_K = 5
CASES = pathlib.Path(__file__).with_name("rules.jsonl")


def hit(content: str, groups: list[str]) -> bool:
    return all(any(alt in content for alt in g.split("|")) for g in groups)


async def evaluate() -> dict[str, Any]:
    s = get_settings()
    redis = Redis.from_url(s.redis_url)
    java = JavaClient(s.backend_base_url)
    reranker = await build_reranker(s)
    rules = RulesIndex(redis, await build_embedder(s), java, reranker=reranker)
    if not await rules.ready():
        raise SystemExit("Redis 里还没有规则索引，先启动一次 ai-service")
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    cases = [c for c in cases if not c.get("unanswerable")]
    modes = ["vector", "keyword", "hybrid", *(["rerank"] if reranker else [])]
    top1 = dict.fromkeys(modes, 0)
    top3 = dict.fromkeys(modes, 0)
    rr = dict.fromkeys(modes, 0.0)
    for c in cases:
        rules.reranker = None
        results: dict[str, list[dict[str, Any]]] = {
            "vector": await rules.vector_search(c["question"], TOP_K),
            "keyword": await rules.keyword_search(c["question"], TOP_K),
            "hybrid": await rules.search(c["question"], TOP_K),
        }
        if reranker:
            rules.reranker = reranker
            results["rerank"] = await rules.search(c["question"], TOP_K)
        for mode, docs in results.items():
            flags = [hit(d["content"], c["must"]) for d in docs]
            top1[mode] += any(flags[:1])
            top3[mode] += any(flags[:3])
            rr[mode] += next((1 / (i + 1) for i, f in enumerate(flags) if f), 0.0)
        if not any(hit(d["content"], c["must"]) for d in results[modes[-1]][:3]):
            print(f"MISS {c['question']} → {[d.get('title') or d.get('source') for d in results[modes[-1]]]}")
    n = len(cases)
    print(f"cases={n}")
    print("top1 命中：" + "  ".join(f"{m}={top1[m]}/{n}" for m in modes))
    print("top3 命中：" + "  ".join(f"{m}={top3[m]}/{n}" for m in modes))
    print(f"MRR@{TOP_K}：" + "  ".join(f"{m}={rr[m] / n:.3f}" for m in modes))
    await java.aclose()
    await redis.aclose()
    return {"summary": {"cases": n, **{f"top1[{m}]": round(top1[m] / n, 3) for m in modes},
                        **{f"top3[{m}]": round(top3[m] / n, 3) for m in modes},
                        **{f"mrr[{m}]": round(rr[m] / n, 3) for m in modes}}}


if __name__ == "__main__":
    asyncio.run(evaluate())
