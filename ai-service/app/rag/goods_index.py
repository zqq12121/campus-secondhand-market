"""商品向量索引：MySQL 增量同步到 Redis，检索时与 Java 关键词搜索做 RRF 混合。"""
import asyncio
import logging
from datetime import datetime
from typing import Any

import pymysql
from pymysql.cursors import DictCursor
from redis.asyncio import Redis
from redisvl.index import AsyncSearchIndex
from redisvl.query import VectorQuery
from redisvl.query.filter import FilterExpression, Num, Tag
from redisvl.redis.utils import array_to_buffer
from redisvl.schema import IndexSchema

from app.config import Settings
from app.java_client import JavaClient
from app.rag.embedder import Embedder

log = logging.getLogger(__name__)

PREFIX = "ai:goods"
WATERMARK_KEY = "ai:goods_index:watermark"
ON_SALE = 2
RRF_K = 60
RETURN_FIELDS = ["goods_id", "title", "price", "campus_id", "campus_name", "category_name", "cover_url", "condition_level"]

SYNC_SQL = """
SELECT g.id, g.title, g.description, g.category_id, c.parent_id, c.name AS category_name,
       pc.name AS parent_category_name, g.campus_id, cp.name AS campus_name, g.price,
       g.cover_url, g.condition_level, g.status, g.stock, g.deleted, g.updated_at
FROM goods g
JOIN category c ON c.id = g.category_id
LEFT JOIN category pc ON pc.id = c.parent_id
JOIN campus cp ON cp.id = g.campus_id
WHERE g.updated_at >= %s
ORDER BY g.updated_at, g.id
"""


def build_schema(dims: int) -> IndexSchema:
    return IndexSchema.from_dict({
        "index": {"name": "ai_goods", "prefix": PREFIX, "storage_type": "hash"},
        "fields": [
            # 不用 "id"：redisvl 查询结果的 id 字段是 Redis 键名
            {"name": "goods_id", "type": "tag"},
            {"name": "title", "type": "text"},
            {"name": "category_id", "type": "tag"},
            {"name": "parent_category_id", "type": "tag"},
            {"name": "campus_id", "type": "tag"},
            {"name": "price", "type": "numeric"},
            {"name": "embedding", "type": "vector",
             "attrs": {"dims": dims, "algorithm": "hnsw", "distance_metric": "cosine", "datatype": "float32"}},
        ],
    })


def embed_text(row: dict[str, Any]) -> str:
    category = "/".join(x for x in (row.get("parent_category_name"), row["category_name"]) if x)
    return f"{row['title']}\n分类：{category}\n校区：{row['campus_name']}\n{row['description']}"


def is_listed(row: dict[str, Any]) -> bool:
    return row["status"] == ON_SALE and row["deleted"] == 0 and row["stock"] > 0


def build_filter(campus_id: str | None, category_id: str | None,
                 min_price: float | None, max_price: float | None) -> FilterExpression | None:
    parts: list[FilterExpression] = []
    if campus_id:
        parts.append(Tag("campus_id") == campus_id)
    if category_id:
        parts.append((Tag("category_id") == category_id) | (Tag("parent_category_id") == category_id))
    if min_price is not None:
        parts.append(Num("price") >= min_price)
    if max_price is not None:
        parts.append(Num("price") <= max_price)
    if not parts:
        return None
    expr = parts[0]
    for p in parts[1:]:
        expr = expr & p
    return expr


def rrf_merge(*ranked: list[str], k: int = RRF_K) -> list[str]:
    """Reciprocal Rank Fusion：每路排名 r 贡献 1/(k+r)，按总分降序。"""
    scores: dict[str, float] = {}
    for ids in ranked:
        for rank, gid in enumerate(ids, start=1):
            scores[gid] = scores.get(gid, 0.0) + 1.0 / (k + rank)
    return sorted(scores, key=lambda g: scores[g], reverse=True)


class GoodsIndex:
    def __init__(self, settings: Settings, redis: Redis, embedder: Embedder) -> None:
        self._settings = settings
        self._redis = redis
        self._embedder = embedder
        self.index = AsyncSearchIndex(build_schema(embedder.dims), redis_client=redis)

    # ---------- 同步 ----------

    def _fetch_changed(self, since: datetime) -> list[dict[str, Any]]:
        s = self._settings
        conn = pymysql.connect(host=s.mysql_host, port=s.mysql_port, user=s.ai_mysql_user,
                               password=s.ai_mysql_password, database=s.mysql_db,
                               cursorclass=DictCursor, charset="utf8mb4")
        try:
            with conn.cursor() as cur:
                cur.execute(SYNC_SQL, (since,))
                return list(cur.fetchall())
        finally:
            conn.close()

    async def sync_once(self, rebuild: bool = False) -> int:
        """增量同步一次，返回处理的行数。rebuild=True 时删索引重建并全量同步。"""
        if rebuild or not await self.index.exists():
            await self.index.create(overwrite=True, drop=True)
            await self._redis.delete(WATERMARK_KEY)
        raw = await self._redis.get(WATERMARK_KEY)
        since = datetime.fromisoformat(raw.decode() if isinstance(raw, bytes) else raw) if raw else datetime(1970, 1, 1)
        rows = await asyncio.to_thread(self._fetch_changed, since)
        if not rows:
            return 0
        listed = [r for r in rows if is_listed(r)]
        gone = [f"{PREFIX}:{r['id']}" for r in rows if not is_listed(r)]
        if gone:
            await self.index.drop_keys(gone)
        if listed:
            vectors = await self._embedder.embed_documents([embed_text(r) for r in listed])
            await self.index.load([
                {
                    "goods_id": str(r["id"]), "title": r["title"], "price": float(r["price"]),
                    "category_id": str(r["category_id"]), "parent_category_id": str(r["parent_id"]),
                    "category_name": r["category_name"], "campus_id": str(r["campus_id"]),
                    "campus_name": r["campus_name"], "cover_url": r["cover_url"],
                    "condition_level": int(r["condition_level"]),
                    "embedding": array_to_buffer(v, dtype="float32"),
                }
                for r, v in zip(listed, vectors)
            ], id_field="goods_id")
        # 用 >= 比较，同一秒内的后续更新下次还会被扫到，重复处理是幂等的
        await self._redis.set(WATERMARK_KEY, max(r["updated_at"] for r in rows).isoformat())
        return len(rows)

    async def run_forever(self) -> None:
        while True:
            try:
                n = await self.sync_once()
                if n:
                    log.info("商品索引同步 %d 行", n)
            except Exception:  # 同步失败不影响对话，下个周期重试
                log.exception("商品索引同步失败")
            await asyncio.sleep(self._settings.goods_sync_interval_seconds)

    # ---------- 检索 ----------

    async def vector_search(self, query: str, flt: FilterExpression | None, top_k: int) -> list[dict[str, Any]]:
        vector = await self._embedder.embed_query(query)
        q = VectorQuery(vector=vector, vector_field_name="embedding", return_fields=RETURN_FIELDS,
                        filter_expression=flt, num_results=top_k)
        return await self.index.query(q)


async def hybrid_search(index: GoodsIndex, java: JavaClient, token: str, query: str,
                        campus_id: str | None = None, category_id: str | None = None,
                        min_price: float | None = None, max_price: float | None = None,
                        top_k: int = 20, limit: int = 10) -> list[dict[str, Any]]:
    """向量 topK + Java 关键词 topK，RRF 合并后取 limit 条，返回统一的商品卡片字段。"""
    flt = build_filter(campus_id, category_id, min_price, max_price)
    vec_task = index.vector_search(query, flt, top_k)
    kw_task = java.search_goods(token, keyword=query[:30], campusId=campus_id, categoryId=category_id,
                                priceMin=min_price, priceMax=max_price, size=top_k)
    vec_res, kw_res = await asyncio.gather(vec_task, kw_task, return_exceptions=True)
    if isinstance(vec_res, Exception):
        log.warning("向量检索失败，仅用关键词结果: %s", vec_res)
        vec_res = []
    if isinstance(kw_res, Exception):
        log.warning("关键词检索失败，仅用向量结果: %s", kw_res)
        kw_res = []

    cards: dict[str, dict[str, Any]] = {}
    for d in vec_res:
        cards[d["goods_id"]] = {"id": d["goods_id"], "title": d["title"], "price": f"{float(d['price']):.2f}",
                          "campusName": d["campus_name"], "categoryName": d["category_name"],
                          "coverUrl": d["cover_url"], "conditionLevel": int(d["condition_level"])}
    for g in kw_res:  # Java 的数据更新鲜，覆盖同 id 的索引字段
        cards[g["id"]] = {**cards.get(g["id"], {}), "id": g["id"], "title": g["title"], "price": g["price"],
                          "campusName": g.get("campusName"), "coverUrl": g.get("coverUrl"),
                          "conditionLevel": g.get("conditionLevel")}
    merged = rrf_merge([d["goods_id"] for d in vec_res], [g["id"] for g in kw_res])
    return [cards[gid] for gid in merged[:limit]]
