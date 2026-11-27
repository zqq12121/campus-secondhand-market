"""进程级依赖的初始化：FastAPI 的 lifespan、MCP Server、评测脚本共用这一份，不各写各的。"""
import asyncio
import logging
from dataclasses import dataclass

from redis.asyncio import Redis

from app.config import Settings
from app.java_client import CircuitBreaker, JavaClient
from app.rag.embedder import Embedder, HttpEmbedder, SentenceTransformerEmbedder
from app.rag.goods_index import GoodsIndex
from app.rag.reranker import CrossEncoderReranker, HttpReranker, Reranker
from app.rag.rules_index import RulesIndex

log = logging.getLogger(__name__)


@dataclass
class Deps:
    settings: Settings
    redis: Redis
    java: JavaClient
    embedder: Embedder
    reranker: Reranker | None
    goods_index: GoodsIndex
    rules_index: RulesIndex

    async def aclose(self) -> None:
        for client in (self.embedder, self.reranker):
            if isinstance(client, (HttpEmbedder, HttpReranker)):
                await client.aclose()
        await self.java.aclose()
        await self.redis.aclose()


async def build_embedder(s: Settings) -> Embedder:
    if s.embedding_service_url:
        log.info("使用外置 embedding 服务：%s", s.embedding_service_url)
        return await HttpEmbedder.create(s.embedding_service_url, s.embedding_query_instruction)
    return await asyncio.to_thread(SentenceTransformerEmbedder, s.embedding_model, s.embedding_query_instruction)


async def build_reranker(s: Settings) -> Reranker | None:
    if s.rerank_service_url:
        log.info("使用外置重排序服务：%s", s.rerank_service_url)
        return HttpReranker(s.rerank_service_url)
    return await asyncio.to_thread(CrossEncoderReranker, s.rerank_model) if s.rerank_model else None


async def build_deps(s: Settings) -> Deps:
    """创建 Redis、Java 客户端、embedding / 重排序模型和两个索引。不启动后台同步，那是 lifespan 的事。"""
    redis = Redis.from_url(s.redis_url)
    java = JavaClient(s.backend_base_url,
                      breaker=CircuitBreaker(s.java_breaker_failures, s.java_breaker_cooldown_seconds))
    embedder = await build_embedder(s)
    reranker = await build_reranker(s)
    return Deps(s, redis, java, embedder, reranker, GoodsIndex(s, redis, embedder, reranker),
                RulesIndex(redis, embedder, java, reranker=reranker))
