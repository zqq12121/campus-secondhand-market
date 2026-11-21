"""M3 写工具：生成草稿 → interrupt() 等用户在确认卡片里确认 / 修改 / 取消 → 才产生副作用。

恢复（resume）时 LangGraph 会重新执行整个 tools 节点，interrupt() 之前的代码会再跑一遍，
所以那部分只能是校验和只读查询，写操作一律放在 interrupt() 之后。
"""
import json
import statistics
from typing import Annotated, Any

from langchain_core.runnables import RunnableConfig
from langchain_core.tools import tool
from langgraph.prebuilt import InjectedState
from langgraph.types import interrupt

from app.graph.context import RequestContext, get_ctx
from app.graph.images import image_urls
from app.java_client import JavaApiError

VALID_DAYS = (3, 7, 15, 30)
MIN_COMPARABLES = 3
PRICE_SPREAD = 0.2
WANT_EDITABLE = {"title", "description", "categoryId", "campusId", "priceMin", "priceMax", "validDays"}
LISTING_EDITABLE = {"title", "description", "categoryId", "conditionLevel", "price"}


def _dump(obj: Any) -> str:
    return json.dumps(obj, ensure_ascii=False)


def second_level_category(ctx: RequestContext, category_id: str) -> str | None:
    """返回二级分类名；不是启用中的二级分类时返回 None。"""
    for parent in ctx.categories:
        for child in parent.get("children", []):
            if str(child["id"]) == str(category_id):
                return f"{parent['name']} > {child['name']}"
    return None


def campus_name(ctx: RequestContext, campus_id: str | None) -> str | None:
    return next((c["name"] for c in ctx.campuses if str(c["id"]) == str(campus_id)), None)


def apply_edits(draft: dict[str, Any], edits: dict[str, Any] | None, editable: set[str]) -> dict[str, Any]:
    return {**draft, **{k: v for k, v in (edits or {}).items() if k in editable}}


def validate_want(ctx: RequestContext, d: dict[str, Any]) -> str | None:
    if not isinstance(d.get("title"), str) or not d["title"] or len(d["title"]) > 30:
        return "标题必填，且不超过 30 字"
    if not isinstance(d.get("description") or "", str) or len(d.get("description") or "") > 500:
        return "描述不超过 500 字"
    if second_level_category(ctx, d.get("categoryId")) is None:
        return "分类必须是二级分类"
    if campus_name(ctx, d.get("campusId")) is None:
        return "校区不存在"
    if d.get("validDays") not in VALID_DAYS:
        return "有效期只能是 3 / 7 / 15 / 30 天"
    try:  # 修改项来自前端，类型不可信
        lo = None if d.get("priceMin") is None else float(d["priceMin"])
        hi = None if d.get("priceMax") is None else float(d["priceMax"])
    except (TypeError, ValueError):
        return "预算格式不正确"
    if lo is not None and hi is not None and lo > hi:
        return "最低价不能高于最高价"
    return None


def validate_listing(ctx: RequestContext, d: dict[str, Any]) -> str | None:
    if not isinstance(d.get("title"), str) or not d["title"] or len(d["title"]) > 30:
        return "标题必填，且不超过 30 字"
    if not isinstance(d.get("description"), str) or not d["description"] or len(d["description"]) > 1000:
        return "描述必填，且不超过 1000 字"
    if second_level_category(ctx, d.get("categoryId")) is None:
        return "分类必须是二级分类"
    if d.get("conditionLevel") not in (1, 2, 3, 4):
        return "成色只能是 1–4"
    try:
        price = float(d["price"])
    except (KeyError, TypeError, ValueError):
        return "价格格式不正确"
    if not 0 <= price <= 99999:
        return "价格需在 0–99999 元之间"
    return None


def reference_price(prices: list[float], estimated: float) -> dict[str, Any]:
    """同类在售 ≥ 3 件时用中位数 ±20%；否则用模型估价并注明仅供参考。"""
    prices = [p for p in prices if p > 0]
    if len(prices) >= MIN_COMPARABLES:
        mid = statistics.median(prices)
        return {"price": round(mid), "priceRange": [round(mid * (1 - PRICE_SPREAD)), round(mid * (1 + PRICE_SPREAD))],
                "priceBasis": f"参考了 {len(prices)} 件同类在售商品"}
    return {"price": round(estimated), "priceRange": None, "priceBasis": "同类在售商品不足 3 件，为模型估价，仅供参考"}


def _money(v: Any) -> str | None:
    return None if v is None else f"{float(v):.2f}"


@tool(parse_docstring=True)
async def draft_want_post(title: str, category_id: str, config: RunnableConfig, description: str = "",
                          campus_id: str | None = None, price_min: float | None = None,
                          price_max: float | None = None, valid_days: int = 7) -> str:
    """帮用户起草求购帖，用户在确认卡片里确认后才会发布。用户明确想发求购时才调用。

    Args:
        title: 求购标题，不超过 30 字，如「求一台 24 寸显示器」。
        category_id: 二级分类 id。
        description: 补充说明，不超过 500 字。
        campus_id: 交易校区 id，用户没说时用其常用或注册校区。
        price_min: 预算下限（元）。
        price_max: 预算上限（元）。
        valid_days: 有效天数，只能是 3、7、15、30。
    """
    ctx = get_ctx(config)
    campus_id = campus_id or ctx.user.get("campusId")
    draft = {"title": title, "description": description, "categoryId": str(category_id),
             "campusId": str(campus_id), "priceMin": price_min, "priceMax": price_max, "validDays": valid_days}
    if error := validate_want(ctx, draft):
        return _dump({"status": "invalid", "error": error})
    shown = {**draft, "categoryName": second_level_category(ctx, draft["categoryId"]),
             "campusName": campus_name(ctx, draft["campusId"])}
    decision = interrupt({"kind": "want_post", "draft": shown})

    if decision.get("decision") != "confirm":
        return _dump({"status": "cancelled"})
    draft = apply_edits(draft, decision.get("edits"), WANT_EDITABLE)
    if error := validate_want(ctx, draft):
        return _dump({"status": "invalid", "error": error})
    try:
        created = await ctx.java.create_want(ctx.token, {
            **draft, "priceMin": _money(draft["priceMin"]), "priceMax": _money(draft["priceMax"])})
    except JavaApiError as e:
        return _dump({"status": "failed", "error": e.message})
    # 返回最终生效的内容（含用户在卡片里的修改），避免模型复述草稿里的旧值
    return _dump({"status": "published", "id": created["id"],
                  "want": {**draft, "categoryName": second_level_category(ctx, draft["categoryId"]),
                           "campusName": campus_name(ctx, draft["campusId"])}})


@tool(parse_docstring=True)
async def suggest_listing(title: str, description: str, category_id: str, condition_level: int,
                          keyword: str, estimated_price: float, config: RunnableConfig,
                          state: Annotated[dict, InjectedState]) -> str:
    """根据用户的商品照片和描述生成发布草稿（标题、描述、分类、成色、参考价）。用户确认后草稿会填入发布页，
    由用户自己检查并点发布；本工具不会直接发布商品。

    Args:
        title: 商品标题，不超过 30 字，写明品牌型号等关键信息。
        description: 商品描述，客观写明规格、成色、瑕疵；看不清的信息不要编，留给用户补充。
        category_id: 最贴切的二级分类 id。电子产品优先归到「数码电子」下（电子书阅读器、平板算「手机平板」），
            实在没有合适分类才用「其他闲置」；拿不准时先用 search_goods 搜同类商品，看它们在哪个分类。
        condition_level: 成色：1 全新，2 几乎全新，3 轻微使用，4 明显使用。
        keyword: 商品的核心品类词，如「显示器」「台灯」「羽毛球拍」，用于查找同类在售商品来定参考价。
        estimated_price: 你估计的合理二手价（元），同类在售商品不足时作为参考价。
    """
    ctx = get_ctx(config)
    human = next((m for m in reversed(state["messages"]) if m.type == "human"), None)
    images = image_urls(human) if human else []
    draft = {"title": title, "description": description, "categoryId": str(category_id),
             "conditionLevel": condition_level, "price": estimated_price}
    if error := validate_listing(ctx, draft):
        return _dump({"status": "invalid", "error": error})
    # 同分类下仍可能混着别的东西（电脑配件里有键盘也有显示器），用品类词的关键词命中来限定「同类」
    comparables = await ctx.java.search_goods(ctx.token, keyword=keyword[:30], categoryId=str(category_id), size=20)
    pricing = reference_price([float(c["price"]) for c in comparables], estimated_price)
    shown = {**draft, **pricing, "categoryName": second_level_category(ctx, draft["categoryId"]), "images": images}
    decision = interrupt({"kind": "listing", "draft": shown})

    if decision.get("decision") != "confirm":
        return _dump({"status": "cancelled"})
    final = apply_edits(shown, decision.get("edits"), LISTING_EDITABLE)
    if error := validate_listing(ctx, final):
        return _dump({"status": "invalid", "error": error})
    final["categoryName"] = second_level_category(ctx, final["categoryId"])
    return _dump({"status": "confirmed", "draft": final})
