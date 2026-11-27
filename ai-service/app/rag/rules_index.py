"""规则知识库：knowledge/*.md + 上线公告，按二级标题切片后向量化，内容变化时整体重建。检索时向量召回再重排。

蓝绿重建：每个内容版本一个独立索引 ai_rules_<hash 前 8 位>，Redis 里的 POINTER_KEY 指向当前版本。
重建时先把新版本完整写进新索引，再切指针、删旧索引；重建期间检索一直读旧版本，不会落空。
"""
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
# 当前版本指针，值是内容 hash 的前 8 位
POINTER_KEY = "ai:rules_index:current"
# 蓝绿改造前的固定索引和 hash 键，首次蓝绿重建后删除
LEGACY_INDEX = "ai_rules"
LEGACY_PREFIX = "ai:rules"
LEGACY_HASH_KEY = "ai:rules_index:hash"
# 索引 schema 变化时加 1：版本号算进内容 hash，保证 schema 变了会重建
SCHEMA_VERSION = 1
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


def build_schema(dims: int, name: str, prefix: str) -> IndexSchema:
    return IndexSchema.from_dict({
        "index": {"name": name, "prefix": prefix, "storage_type": "hash"},
        "fields": [
            {"name": "chunk_id", "type": "tag"},
            {"name": "title", "type": "text"},
            {"name": "embedding", "type": "vector",
             "attrs": {"dims": dims, "algorithm": "flat", "distance_metric": "cosine", "datatype": "float32"}},
        ],
    })


def content_version(chunks: list[Chunk], dims: int) -> str:
    payload = {"schema": SCHEMA_VERSION, "dims": dims, "chunks": [c.__dict__ for c in chunks]}
    return hashlib.sha1(json.dumps(payload, ensure_ascii=False).encode()).hexdigest()[:8]


class RulesIndex:
    def __init__(self, redis: Redis, embedder: Embedder, java: JavaClient,
                 knowledge_dir: pathlib.Path = KNOWLEDGE_DIR, reranker: Reranker | None = None) -> None:
        self._redis = redis
        self._reranker = reranker
        self._embedder = embedder
        self._java = java
        self._dir = knowledge_dir
        self._indexes: dict[str, AsyncSearchIndex] = {}

    def _open(self, name: str, prefix: str) -> AsyncSearchIndex:
        # 缓存索引对象：redisvl 首次使用时会校验 Redis 模块，不必每次检索都重来
        if name not in self._indexes:
            self._indexes[name] = AsyncSearchIndex(build_schema(self._embedder.dims, name, prefix),
                                                   redis_client=self._redis)
        return self._indexes[name]

    def _versioned(self, version: str) -> AsyncSearchIndex:
        # 前缀刻意不以 ai:rules 开头：旧索引 ai_rules 的前缀是 ai:rules，会把新数据也收进去，删旧索引时连带删掉
        return self._open(f"ai_rules_{version}", f"ai:kb:{version}")

    async def current_version(self) -> str | None:
        raw = await self._redis.get(POINTER_KEY)
        return raw.decode() if isinstance(raw, bytes) else raw

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
        """内容有变化（或当前版本的索引不存在）才重建，返回是否重建。只应由选主拿到锁的实例调用。"""
        chunks = await self.collect()
        version = content_version(chunks, self._embedder.dims)
        current = await self.current_version()
        if current == version and await self._versioned(version).exists():
            return False
        vectors = await self._embedder.embed_documents([f"{c.title}\n{c.content}" for c in chunks])
        new = self._versioned(version)
        # 同版本的残留（上次重建中途失败）先清掉；这个索引还没被指针指向，删了不影响检索
        await new.create(overwrite=True, drop=True)
        await new.load([
            {"chunk_id": c.chunk_id, "title": c.title, "content": c.content,
             "embedding": array_to_buffer(v, dtype="float32")}
            for c, v in zip(chunks, vectors)
        ], id_field="chunk_id")
        # 新索引完整写好后才切指针，之后的检索都读新版本
        await self._redis.set(POINTER_KEY, version)
        if current and current != version:
            await self._versioned(current).delete(drop=True)
        legacy = self._open(LEGACY_INDEX, LEGACY_PREFIX)
        if await legacy.exists():
            await legacy.delete(drop=True)
            await self._redis.delete(LEGACY_HASH_KEY)
        log.info("规则知识库重建：%d 个片段，版本 %s → %s", len(chunks), current, version)
        return True

    async def run_forever(self) -> None:
        while True:
            try:
                await self.refresh()
            except Exception:
                log.exception("规则知识库刷新失败")
            await asyncio.sleep(REFRESH_SECONDS)

    async def _query(self, q: Any) -> list[dict[str, Any]]:
        version = await self.current_version()
        if version is None:  # 主实例还没建好第一个版本
            return []
        try:
            return await self._versioned(version).query(q)
        except Exception:
            # 读完指针后刚好切换、旧索引已被删：按新指针重查一次
            latest = await self.current_version()
            if latest is None or latest == version:
                raise
            return await self._versioned(latest).query(q)

    async def search(self, question: str, top_k: int = 5) -> list[dict[str, Any]]:
        vector = await self._embedder.embed_query(question)
        n = top_k * RERANK_FACTOR if self._reranker else top_k
        q = VectorQuery(vector=vector, vector_field_name="embedding", return_fields=["title", "content"],
                        num_results=n)
        hits = await self._query(q)
        if self._reranker is not None and len(hits) > 1:
            try:
                order = await rerank(self._reranker, question, [f"{d['title']}\n{d['content']}" for d in hits])
                hits = [hits[i] for i in order]
            except Exception as e:
                log.warning("规则重排序失败，使用向量顺序: %s", e)
        return [{"source": d["title"], "content": d["content"]} for d in hits[:top_k]]
