"""规则知识库：knowledge/*.md + 上线公告，按二级标题切片后向量化，内容变化时整体重建。

检索：向量召回 + 全文（BM25）召回，RRF 合并后再重排。规则里有很多数字和专有词（「15 分钟」「信用分」），
向量召回对这些不敏感，关键词召回正好补上。
中文分词：redisvl 0.32 的 IndexSchema 不支持设置 RediSearch 的 LANGUAGE chinese，这里用 jieba 预分词，
把词用空格拼好写进 tokens 文本字段；入库和查询用同一个分词器，领域词（信用分、担保订单……）可以加进词典。

蓝绿重建：每个内容版本一个独立索引 ai_rules_<hash 前 8 位>，Redis 里的 POINTER_KEY 指向当前版本。
重建时先把新版本完整写进新索引，再切指针、删旧索引；重建期间检索一直读旧版本，不会落空。
"""
import asyncio
import functools
import hashlib
import json
import logging
import pathlib
from dataclasses import dataclass
from typing import Any

import jieba
from redis.asyncio import Redis
from redisvl.index import AsyncSearchIndex
from redisvl.query import TextQuery, VectorQuery
from redisvl.redis.utils import array_to_buffer
from redisvl.schema import IndexSchema

from app.java_client import JavaClient
from app.rag.embedder import Embedder
from app.rag.goods_index import rrf_merge
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
SCHEMA_VERSION = 2
MAX_CHUNK_CHARS = 500
REFRESH_SECONDS = 600
# 有重排序模型时，每路先召回 top_k 的这么多倍，合并重排后再取 top_k
RERANK_FACTOR = 3
RETURN_FIELDS = ["chunk_id", "title", "content"]
# 加进 jieba 词典的领域词：不加的话「信用分」会被切成「信用 / 分」
DOMAIN_WORDS = ("信用分", "担保订单", "面交订单", "确认收货", "收货前退款", "平台介入", "求购帖", "待面交", "自动下架")
# 不参与全文检索的虚词和疑问词：OR 查询里它们几乎每片都有，只会稀释 BM25 分数
STOPWORDS = frozenset("的 了 吗 呢 吧 啊 呀 是 有 会 能 要 我 你 他 她 它 在 和 与 或 就 都 也 还 被 把 给 让 "
                      "可以 怎么 怎样 什么 多少 如何 为什么 哪些 哪个 一个 这个 那个 没 没有 不".split())


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
            # title + content 经 jieba 分词后用空格拼接，供全文召回
            {"name": "tokens", "type": "text"},
            {"name": "embedding", "type": "vector",
             "attrs": {"dims": dims, "algorithm": "flat", "distance_metric": "cosine", "datatype": "float32"}},
        ],
    })


@functools.lru_cache(maxsize=1)
def _tokenizer() -> jieba.Tokenizer:
    jieba.setLogLevel(logging.WARNING)
    t = jieba.Tokenizer()
    for w in DOMAIN_WORDS:
        t.add_word(w)
    return t


def tokenize(text: str) -> list[str]:
    """搜索引擎模式分词（长词再切出子词，召回更多），去掉标点和虚词。首次调用加载词典约 1 秒。"""
    words = (w.strip().lower() for w in _tokenizer().cut_for_search(text))
    return [w for w in words if w and w not in STOPWORDS and any(ch.isalnum() for ch in w)]


def content_version(chunks: list[Chunk], dims: int) -> str:
    payload = {"schema": SCHEMA_VERSION, "dims": dims, "chunks": [c.__dict__ for c in chunks]}
    return hashlib.sha1(json.dumps(payload, ensure_ascii=False).encode()).hexdigest()[:8]


class RulesIndex:
    def __init__(self, redis: Redis, embedder: Embedder, java: JavaClient,
                 knowledge_dir: pathlib.Path = KNOWLEDGE_DIR, reranker: Reranker | None = None) -> None:
        self._redis = redis
        self.reranker = reranker
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

    async def ready(self) -> bool:
        """当前版本的索引存在，检索才有结果。"""
        version = await self.current_version()
        return bool(version) and await self._versioned(version).exists()

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
        texts = [f"{c.title}\n{c.content}" for c in chunks]
        vectors = await self._embedder.embed_documents(texts)
        tokens = await asyncio.to_thread(lambda: [" ".join(tokenize(t)) for t in texts])
        new = self._versioned(version)
        # 同版本的残留（上次重建中途失败）先清掉；这个索引还没被指针指向，删了不影响检索
        await new.create(overwrite=True, drop=True)
        await new.load([
            {"chunk_id": c.chunk_id, "title": c.title, "content": c.content, "tokens": t,
             "embedding": array_to_buffer(v, dtype="float32")}
            for c, v, t in zip(chunks, vectors, tokens)
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

    async def vector_search(self, question: str, n: int) -> list[dict[str, Any]]:
        vector = await self._embedder.embed_query(question)
        return await self._query(VectorQuery(vector=vector, vector_field_name="embedding",
                                             return_fields=RETURN_FIELDS, num_results=n))

    async def keyword_search(self, question: str, n: int) -> list[dict[str, Any]]:
        words = await asyncio.to_thread(tokenize, question)
        if not words:  # 全是虚词或标点
            return []
        return await self._query(TextQuery(" ".join(words), text_field_name="tokens", return_fields=RETURN_FIELDS,
                                           num_results=n, stopwords=None))

    async def search(self, question: str, top_k: int = 5) -> list[dict[str, Any]]:
        """向量 + 全文各召回 n 条，RRF 合并；有重排序模型时对合并后的前 n 条重排。任意一路失败就只用另一路。"""
        n = top_k * RERANK_FACTOR if self.reranker else top_k
        vec_res, kw_res = await asyncio.gather(self.vector_search(question, n), self.keyword_search(question, n),
                                               return_exceptions=True)
        if isinstance(vec_res, Exception):
            log.warning("规则向量召回失败，仅用关键词结果: %s", vec_res)
            vec_res = []
        if isinstance(kw_res, Exception):
            log.warning("规则关键词召回失败，仅用向量结果: %s", kw_res)
            kw_res = []
        docs = {d["chunk_id"]: d for d in [*kw_res, *vec_res]}
        hits = [docs[c] for c in rrf_merge([d["chunk_id"] for d in vec_res], [d["chunk_id"] for d in kw_res])[:n]]
        if self.reranker is not None and len(hits) > 1:
            try:
                order = await rerank(self.reranker, question, [f"{d['title']}\n{d['content']}" for d in hits])
                hits = [hits[i] for i in order]
            except Exception as e:
                log.warning("规则重排序失败，使用 RRF 顺序: %s", e)
        return [{"source": d["title"], "content": d["content"]} for d in hits[:top_k]]
