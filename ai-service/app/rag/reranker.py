"""重排序（cross-encoder）：把查询和每个候选拼在一起打相关度分，比向量相似度准，但更慢，只用于召回后的几十条候选。

本地 sentence-transformers，或外置的 text-embeddings-inference（TEI）服务。
"""
import asyncio
from typing import Protocol

import httpx

TEI_TIMEOUT_SECONDS = 10.0


class Reranker(Protocol):
    async def scores(self, query: str, docs: list[str]) -> list[float]: ...


class CrossEncoderReranker:
    def __init__(self, model_name: str) -> None:
        from sentence_transformers import CrossEncoder

        self._model = CrossEncoder(model_name, device="cpu", max_length=512)

    def _predict(self, query: str, docs: list[str]) -> list[float]:
        return self._model.predict([(query, d) for d in docs], batch_size=32).tolist()

    async def scores(self, query: str, docs: list[str]) -> list[float]:
        if not docs:
            return []
        return await asyncio.to_thread(self._predict, query, docs)


class HttpReranker:
    """调用 TEI 的 POST /rerank。TEI 按分数降序返回 [{index, score}]，这里按 index 还原成与 docs 对齐的分数。"""

    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._http = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=TEI_TIMEOUT_SECONDS,
                                       transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def scores(self, query: str, docs: list[str]) -> list[float]:
        if not docs:
            return []
        # truncate：与本地 CrossEncoder(max_length=512) 一样截断超长输入
        resp = await self._http.post("/rerank", json={"query": query, "texts": docs, "truncate": True})
        resp.raise_for_status()
        out = [0.0] * len(docs)
        for item in resp.json():
            out[item["index"]] = item["score"]
        return out


async def rerank(reranker: Reranker, query: str, docs: list[str]) -> list[int]:
    """返回按相关度从高到低的下标；同分时保持原顺序。"""
    s = await reranker.scores(query, docs)
    return sorted(range(len(docs)), key=lambda i: -s[i])
