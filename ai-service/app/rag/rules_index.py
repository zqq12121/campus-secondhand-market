"""规则知识库：knowledge/*.md + 上线公告，按二级标题切片后向量化，内容变化时整体重建。检索时向量召回再重排。"""
import asyncio
import hashlib
import json
import logging
import pathlib
from dataclasses import dataclass
from typing import Any

from redis.asyncio import Redis
from redisvl.index import AsyncSearchIndex
from redisvl.query import VectorQuery
from redisvl.redis.utils import array_to_buffer
from redisvl.schema import IndexSchema

from app.java_client import JavaClient
from app.rag.embedder import Embedder
from app.rag.reranker import Reranker, rerank

log = logging.getLogger(__name__)

KNOWLEDGE_DIR = pathlib.Path(__file__).resolve().parents[2] / "knowledge"
HASH_KEY = "ai:rules_index:hash"
MAX_CHUNK_CHARS = 500
REFRESH_SECONDS = 600
# 有重排序模型时，向量先召回 top_k 的这么多倍，重排后再取 top_k
RERANK_FACTOR = 3


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    title: str
    content: str


def split_markdown(doc_name: str, text: str) -> list[Chunk]:
    """按 `## ` 切节；超长的节按段落再切，每片不超过 MAX_CHUNK_CHARS。"""
    lines = text.strip().splitlines()
    doc_title = lines[0].lstrip("# ").strip() if lines and lines[0].startswith("# ") else doc_name
    sections: list[tuple[str, list[str]]] = []
    for line in lines[1:]:
        if line.startswith("## "):
            sections.append((line[3:].strip(), []))
        elif sections:
            sections[-1][1].append(line)
    chunks: list[Chunk] = []
    for heading, body in sections:
        paragraphs = [p.strip() for p in "\n".join(body).split("\n\n") if p.strip()]
        buf: list[str] = []
        part = 0
        for p in paragraphs + [None]:
            if p is None or (buf and len("\n\n".join(buf + [p])) > MAX_CHUNK_CHARS):
                if buf:
                    part += 1
                    chunks.append(Chunk(f"{doc_name}#{heading}#{part}", f"{doc_title} > {heading}", "\n\n".join(buf)))
                buf = []
            if p is not None:
                buf.append(p)
    return chunks


def build_schema(dims: int) -> IndexSchema:
    return IndexSchema.from_dict({
        "index": {"name": "ai_rules", "prefix": "ai:rules", "storage_type": "hash"},
        "fields": [
            {"name": "chunk_id", "type": "tag"},
            {"name": "title", "type": "text"},
            {"name": "embedding", "type": "vector",
             "attrs": {"dims": dims, "algorithm": "flat", "distance_metric": "cosine", "datatype": "float32"}},
        ],
    })


class RulesIndex:
    def __init__(self, redis: Redis, embedder: Embedder, java: JavaClient,
                 knowledge_dir: pathlib.Path = KNOWLEDGE_DIR, reranker: Reranker | None = None) -> None:
        self._redis = redis
        self._reranker = reranker
        self._embedder = embedder
        self._java = java
        self._dir = knowledge_dir
        self.index = AsyncSearchIndex(build_schema(embedder.dims), redis_client=redis)

    async def collect(self) -> list[Chunk]:
        chunks: list[Chunk] = []
        for path in sorted(self._dir.glob("*.md")):
            chunks += split_markdown(path.stem, path.read_text(encoding="utf-8"))
        try:  # 公告不可用时只用本地规则
            listing = await self._java.announcements()
            for a in listing:
                detail = await self._java.announcement(a["id"])
                if detail.get("content"):
                    chunks.append(Chunk(f"announcement#{a['id']}", f"平台公告 > {detail['title']}",
                                        detail["content"][:MAX_CHUNK_CHARS * 2]))
        except Exception as e:
            log.warning("读取公告失败，本次只索引本地规则: %s", e)
        return chunks

    async def refresh(self) -> bool:
        """内容有变化（或索引不存在）才重建，返回是否重建。"""
        chunks = await self.collect()
        digest = hashlib.sha1(json.dumps([c.__dict__ for c in chunks], ensure_ascii=False).encode()).hexdigest()
        current = await self._redis.get(HASH_KEY)
        if await self.index.exists() and current and current.decode() == digest:
            return False
        vectors = await self._embedder.embed_documents([f"{c.title}\n{c.content}" for c in chunks])
        await self.index.create(overwrite=True, drop=True)
        await self.index.load([
            {"chunk_id": c.chunk_id, "title": c.title, "content": c.content,
             "embedding": array_to_buffer(v, dtype="float32")}
            for c, v in zip(chunks, vectors)
        ], id_field="chunk_id")
        await self._redis.set(HASH_KEY, digest)
        log.info("规则知识库重建：%d 个片段", len(chunks))
        return True

    async def run_forever(self) -> None:
        while True:
            try:
                await self.refresh()
            except Exception:
                log.exception("规则知识库刷新失败")
            await asyncio.sleep(REFRESH_SECONDS)

    async def search(self, question: str, top_k: int = 5) -> list[dict[str, Any]]:
        vector = await self._embedder.embed_query(question)
        n = top_k * RERANK_FACTOR if self._reranker else top_k
        q = VectorQuery(vector=vector, vector_field_name="embedding", return_fields=["title", "content"],
                        num_results=n)
        hits = await self.index.query(q)
        if self._reranker is not None and len(hits) > 1:
            try:
                order = await rerank(self._reranker, question, [f"{d['title']}\n{d['content']}" for d in hits])
                hits = [hits[i] for i in order]
            except Exception as e:
                log.warning("规则重排序失败，使用向量顺序: %s", e)
        return [{"source": d["title"], "content": d["content"]} for d in hits[:top_k]]
