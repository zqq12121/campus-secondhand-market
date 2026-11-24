"""本地重排序（cross-encoder）：把查询和每个候选拼在一起打相关度分，比向量相似度准，但更慢，只用于召回后的几十条候选。"""
import asyncio
from typing import Protocol


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


async def rerank(reranker: Reranker, query: str, docs: list[str]) -> list[int]:
    """返回按相关度从高到低的下标；同分时保持原顺序。"""
    s = await reranker.scores(query, docs)
    return sorted(range(len(docs)), key=lambda i: -s[i])
