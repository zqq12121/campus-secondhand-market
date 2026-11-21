"""M1 工具：商品混合检索 + 商品实时详情。工具结果一律是 JSON 字符串。"""
import json
from typing import Any

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from app.graph.context import get_ctx
from app.java_client import JavaApiError
from app.rag.goods_index import hybrid_search

# 这些原因下商品不能推荐给当前用户
UNAVAILABLE_REASONS = {"NOT_ON_SALE", "OUT_OF_STOCK", "BLOCKED"}


def _dump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False)


@tool(parse_docstring=True)
async def search_goods(query: str, config: RunnableConfig, campus_id: str | None = None,
                       category_id: str | None = None, min_price: float | None = None,
                       max_price: float | None = None) -> str:
    """按自然语言需求检索在售商品（语义 + 关键词混合），最多返回 10 件。

    Args:
        query: 用户想要的东西，尽量具体，如「宿舍用小台灯」。
        campus_id: 校区 id，用户限定了校区时才传。
        category_id: 一级或二级分类 id，能确定分类时才传。
        min_price: 最低价（元）。
        max_price: 最高价（元）。
    """
    ctx = get_ctx(config)
    cards = await hybrid_search(ctx.goods_index, ctx.java, ctx.token, query, campus_id=campus_id,
                                category_id=category_id, min_price=min_price, max_price=max_price)
    return _dump({"count": len(cards), "goods": cards})


@tool(parse_docstring=True)
async def get_goods_detail(goods_id: str, config: RunnableConfig) -> str:
    """查询商品的实时详情与可购买状态。向用户推荐任何商品前都必须先调用它核实。

    Args:
        goods_id: 商品 id。
    """
    ctx = get_ctx(config)
    try:
        d = await ctx.java.goods_detail(ctx.token, goods_id)
    except JavaApiError as e:
        return _dump({"id": goods_id, "available": False, "reason": e.message})
    available = d.get("status") == 2 and d.get("cannotBuyReason") not in UNAVAILABLE_REASONS
    seller = d.get("seller") or {}
    return _dump({
        "id": d["id"], "available": available, "reason": d.get("cannotBuyReason"),
        "title": d["title"], "description": (d.get("description") or "")[:300],
        "price": d["price"], "originalPrice": d.get("originalPrice"),
        "conditionLevel": d.get("conditionLevel"), "tradeMode": d.get("tradeMode"),
        "categoryPath": d.get("categoryPath"), "campusName": d.get("campusName"),
        "location": d.get("location"), "coverUrl": (d.get("images") or [None])[0],
        "isMine": d.get("isMine"),
        "seller": {"nickname": seller.get("nickname"), "creditLevel": seller.get("creditLevel"),
                   "goodRate": seller.get("goodRate")},
    })


TOOLS = [search_goods, get_goods_detail]
