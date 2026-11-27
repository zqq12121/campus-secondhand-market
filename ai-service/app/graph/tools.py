"""agent 的全部工具。只读工具在本文件，写工具（需用户确认）在 drafts.py。工具结果一律是 JSON 字符串。"""
import json
import logging
from typing import Any

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool

from app.graph.context import get_ctx
from app.graph.drafts import draft_want_post, suggest_listing
from app.guard import redact_untrusted
from app.java_client import JavaApiError
from app.metrics import GUARD_TRIGGERS
from app.rag.goods_index import hybrid_search

log = logging.getLogger(__name__)

# 这些原因下商品不能推荐给当前用户
UNAVAILABLE_REASONS = {"NOT_ON_SALE", "OUT_OF_STOCK", "BLOCKED"}


def _dump(obj: Any) -> str:
    # 商品、订单、公告里有卖家和运营写的内容，先过滤疑似注入再交给模型
    safe, n = redact_untrusted(obj)
    if n:
        log.warning("工具结果过滤疑似注入 %d 处", n)
        GUARD_TRIGGERS.labels("tool_redacted").inc()
    return json.dumps(safe, ensure_ascii=False)


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


@tool(parse_docstring=True)
async def search_rules(question: str, config: RunnableConfig) -> str:
    """检索平台规则、FAQ 和公告（交易流程、售后、信用分、发布与求购、账号与评价等）。回答规则问题前必须调用。

    Args:
        question: 用户的规则问题，如「确认收货后还能退款吗」。
    """
    ctx = get_ctx(config)
    if ctx.rules_index is None:
        return _dump({"results": [], "note": "规则库暂不可用"})
    return _dump({"results": await ctx.rules_index.search(question)})


ORDER_STATUS = {10: "待卖家确认", 20: "待面交", 30: "待支付", 40: "待交货", 50: "待收货", 60: "售后中",
                70: "已完成", 80: "已取消", 90: "已退款"}
TRADE_MODE = {1: "面交", 2: "担保"}


@tool(parse_docstring=True)
async def list_my_orders(config: RunnableConfig, role: str = "BUYER", status: list[int] | None = None) -> str:
    """查询当前用户的订单列表（最近 10 条）。只能查询，不能操作订单。

    Args:
        role: BUYER 我买到的，SELLER 我卖出的。
        status: 按状态码筛选，可多选：10 待卖家确认 / 20 待面交 / 30 待支付 / 40 待交货 / 50 待收货 / 60 售后中 / 70 已完成 / 80 已取消 / 90 已退款。
    """
    ctx = get_ctx(config)
    role = "SELLER" if role.upper() == "SELLER" else "BUYER"
    try:
        orders = await ctx.java.list_orders(ctx.token, role, status)
    except JavaApiError as e:
        return _dump({"error": e.message})
    return _dump({"role": role, "orders": [
        {"id": o["id"], "goodsTitle": o["goodsTitle"], "totalAmount": o["totalAmount"],
         "tradeMode": TRADE_MODE.get(o["tradeMode"]), "status": ORDER_STATUS.get(o["status"], o["status"]),
         "counterpart": (o.get("counterpart") or {}).get("nickname"), "createdAt": o.get("createdAt")}
        for o in orders
    ]})


@tool(parse_docstring=True)
async def get_order(order_id: str, config: RunnableConfig) -> str:
    """查询一个订单的详情：当前状态、时间线、下一个超时节点、售后情况和用户此刻可做的操作。

    Args:
        order_id: 订单 id（来自 list_my_orders）。
    """
    ctx = get_ctx(config)
    try:
        o = await ctx.java.get_order(ctx.token, order_id)
    except JavaApiError as e:
        return _dump({"id": order_id, "error": e.message})
    goods_info = o.get("goods") or {}
    return _dump({
        "id": o["id"], "orderNo": o.get("orderNo"), "myRole": o.get("myRole"),
        "goods": {k: goods_info.get(k) for k in ("title", "location", "campusName")},
        "totalAmount": o.get("totalAmount"), "tradeMode": TRADE_MODE.get(o.get("tradeMode")),
        "status": ORDER_STATUS.get(o.get("status"), o.get("status")), "timeline": o.get("timeline"),
        "deadline": o.get("deadline"), "cancelReason": o.get("cancelReason"),
        "afterSale": o.get("afterSale"), "actions": o.get("actions"),
    })


TOOLS = [search_goods, get_goods_detail, search_rules, list_my_orders, get_order, draft_want_post, suggest_listing]
