"""商品检索评测：对比 向量 / 关键词 / 混合 / 混合+重排 四种方式的 top5 命中率和 MRR。

前置：演示数据已导入（sql/demo_goods.sql）、索引已同步、Java 后端在运行。
用法：AI_EVAL_TOKEN=<任一用户 token> uv run python -m evals.run_retrieval
命中 = top5 中至少有一件 relevant 商品；MRR = 第一件 relevant 商品排名倒数的平均值（看重排把对的排得多靠前）。
未配置 RERANK_MODEL 时跳过「混合+重排」。
"""
import asyncio
import json
import os
import pathlib
from typing import Any

from redis.asyncio import Redis

from app.config import get_settings
from app.java_client import JavaClient
from app.rag.embedder import SentenceTransformerEmbedder
from app.rag.goods_index import GoodsIndex, build_filter, hybrid_search
from app.rag.reranker import CrossEncoderReranker

TOP_K = 5
CASES = pathlib.Path(__file__).with_name("retrieval.jsonl")


async def evaluate(token: str) -> dict[str, Any]:
    s = get_settings()
    redis = Redis.from_url(s.redis_url)
    java = JavaClient(s.backend_base_url)
    index = GoodsIndex(s, redis, SentenceTransformerEmbedder(s.embedding_model, s.embedding_query_instruction))
    reranker = CrossEncoderReranker(s.rerank_model) if s.rerank_model else None
    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    hits = {"vector": 0, "keyword": 0, "hybrid": 0, **({"rerank": 0} if reranker else {})}
    rr = dict.fromkeys(hits, 0.0)
    misses: list[str] = []
    for c in cases:
        f: dict[str, Any] = {k: c.get(k) for k in ("campus_id", "category_id", "min_price", "max_price")}
        relevant = set(c["relevant"])
        vec = await index.vector_search(c["query"], build_filter(**f), TOP_K)
        kw = await java.search_goods(token, keyword=c["query"], campusId=f["campus_id"], categoryId=f["category_id"],
                                     priceMin=f["min_price"], priceMax=f["max_price"], size=TOP_K)
        index.reranker = None
        hyb = await hybrid_search(index, java, token, c["query"], limit=TOP_K, **f)
        results = {"vector": [d["goods_id"] for d in vec], "keyword": [g["id"] for g in kw],
                   "hybrid": [g["id"] for g in hyb]}
        if reranker:
            index.reranker = reranker
            results["rerank"] = [g["id"] for g in await hybrid_search(index, java, token, c["query"],
                                                                       limit=TOP_K, **f)]
        for name, ids in results.items():
            hits[name] += bool(relevant & set(ids))
            rr[name] += next((1 / (i + 1) for i, gid in enumerate(ids) if gid in relevant), 0.0)
        best = "rerank" if reranker else "hybrid"
        if not relevant & set(results[best]):
            misses.append(f"{c['query']} {f} → {results[best]}")
    n = len(cases)
    print(f"cases={n}  top{TOP_K} 命中率：" + "  ".join(f"{k}={v / n:.0%}" for k, v in hits.items()))
    print("MRR：" + "  ".join(f"{k}={v / n:.3f}" for k, v in rr.items()))
    for m in misses:
        print("MISS", m)
    await java.aclose()
    await redis.aclose()
    return {"summary": {"cases": n, **{f"top{TOP_K}[{k}]": round(v / n, 3) for k, v in hits.items()},
                        **{f"mrr[{k}]": round(v / n, 3) for k, v in rr.items()}}}


if __name__ == "__main__":
    asyncio.run(evaluate(os.environ["AI_EVAL_TOKEN"]))
