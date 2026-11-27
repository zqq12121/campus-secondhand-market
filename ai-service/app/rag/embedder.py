"""embedding：本地 sentence-transformers，或外置的 text-embeddings-inference（TEI）服务。向量都已归一化，配合余弦距离。"""
import asyncio
from typing import Any, Protocol

import httpx

# TEI 默认 --max-client-batch-size 为 32，超过会报错，文档按这个大小分批发
TEI_BATCH_SIZE = 32
TEI_TIMEOUT_SECONDS = 10.0


class Embedder(Protocol):
    dims: int

    async def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


class SentenceTransformerEmbedder:
    def __init__(self, model_name: str, query_instruction: str) -> None:
        from sentence_transformers import SentenceTransformer

        self._model = SentenceTransformer(model_name, device="cpu")
        self._instruction = query_instruction
        self.dims: int = self._model.get_embedding_dimension()

    def _encode(self, texts: list[str]) -> list[list[float]]:
        return self._model.encode(texts, normalize_embeddings=True, batch_size=32).tolist()

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return await asyncio.to_thread(self._encode, texts)

    async def embed_query(self, text: str) -> list[float]:
        vectors = await asyncio.to_thread(self._encode, [self._instruction + text])
        return vectors[0]


class HttpEmbedder:
    """调用 TEI 的 POST /embed。query 加检索指令前缀，与本地实现一致。用 create() 创建：启动时探测向量维度。"""

    def __init__(self, http: httpx.AsyncClient, query_instruction: str, dims: int) -> None:
        self._http = http
        self._instruction = query_instruction
        self.dims = dims

    @classmethod
    async def create(cls, base_url: str, query_instruction: str,
                     transport: httpx.AsyncBaseTransport | None = None) -> "HttpEmbedder":
        http = httpx.AsyncClient(base_url=base_url.rstrip("/"), timeout=TEI_TIMEOUT_SECONDS, transport=transport)
        probe = await _tei_embed(http, ["维度探测"])
        return cls(http, query_instruction, len(probe[0]))

    async def aclose(self) -> None:
        await self._http.aclose()

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        vectors: list[list[float]] = []
        for i in range(0, len(texts), TEI_BATCH_SIZE):
            vectors += await _tei_embed(self._http, texts[i:i + TEI_BATCH_SIZE])
        return vectors

    async def embed_query(self, text: str) -> list[float]:
        return (await _tei_embed(self._http, [self._instruction + text]))[0]


async def _tei_embed(http: httpx.AsyncClient, texts: list[str]) -> list[list[float]]:
    # truncate：超长文本截断而不是报错，与本地模型按 max_seq_length 截断的行为一致
    body: dict[str, Any] = {"inputs": texts, "normalize": True, "truncate": True}
    resp = await http.post("/embed", json=body)
    resp.raise_for_status()
    return resp.json()
