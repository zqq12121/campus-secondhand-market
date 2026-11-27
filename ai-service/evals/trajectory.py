"""Agent 轨迹判定：从图的最终消息里提取工具调用序列，按用例的约束用代码判定，不用模型打分。

用例里可写的约束（evals/trajectory.jsonl）：
- must_call：这些工具都必须调用过，如问规则必须调 search_rules。
- must_call_any：至少调用其中一个，如问订单调 list_my_orders 或 get_order。
- must_not_call：这些工具一个都不能调，如问订单不能调写工具。
- no_tools：true 表示不能调用任何工具（闲聊）。
- verify_recommended：true 表示最终回答里推荐的每件商品，都要先用 get_goods_detail 核实过，且核实结果可购买。
  「推荐」按 search_goods 返回的商品判定：id 或标题出现在最终回答里就算推荐了。
"""
import json
from dataclasses import dataclass
from typing import Any

from langchain_core.messages import AIMessage, BaseMessage, ToolMessage

WRITE_TOOLS = ("draft_want_post", "suggest_listing")
CHECK_KEYS = {"must_call", "must_call_any", "must_not_call", "no_tools", "verify_recommended"}


@dataclass
class Step:
    name: str
    args: dict[str, Any]
    result: str | None  # 写工具停在确认卡片时还没有结果


def extract(messages: list[BaseMessage]) -> tuple[list[Step], str]:
    """返回（按调用顺序的工具调用，最终回答）。messages 是本轮的消息（新会话里就是全部消息）。"""
    results = {m.tool_call_id: m.text for m in messages if isinstance(m, ToolMessage)}
    steps = [Step(tc["name"], tc["args"], results.get(tc["id"]))
             for m in messages if isinstance(m, AIMessage) for tc in m.tool_calls]
    last = messages[-1] if messages else None
    answer = last.text if isinstance(last, AIMessage) and not last.tool_calls else ""
    return steps, answer


def _json(text: str | None) -> dict[str, Any]:
    try:
        return json.loads(text) if text else {}
    except ValueError:
        return {}


def recommended_goods(steps: list[Step], answer: str) -> list[dict[str, Any]]:
    """search_goods 返回过、且 id 或标题出现在最终回答里的商品。"""
    seen: dict[str, dict[str, Any]] = {}
    for s in steps:
        if s.name == "search_goods":
            for g in _json(s.result).get("goods", []):
                seen.setdefault(str(g["id"]), g)
    return [g for gid, g in seen.items() if gid in answer or (g.get("title") and g["title"] in answer)]


def check(case: dict[str, Any], steps: list[Step], answer: str) -> list[str]:
    """返回违反的约束；空列表表示通过。"""
    names = [s.name for s in steps]
    failures: list[str] = []
    for tool in case.get("must_call", []):
        if tool not in names:
            failures.append(f"没有调用 {tool}")
    if case.get("must_call_any") and not set(case["must_call_any"]) & set(names):
        failures.append(f"{' / '.join(case['must_call_any'])} 一个都没调用")
    for tool in case.get("must_not_call", []):
        if tool in names:
            failures.append(f"不该调用 {tool}")
    if case.get("no_tools") and names:
        failures.append(f"闲聊不该调用工具：{names}")
    if case.get("verify_recommended"):
        verified = {str(s.args.get("goods_id")): _json(s.result) for s in steps if s.name == "get_goods_detail"}
        for g in recommended_goods(steps, answer):
            gid = str(g["id"])
            if gid not in verified:
                failures.append(f"推荐 {gid}（{g.get('title')}）前没有调用 get_goods_detail 核实")
            elif verified[gid].get("available") is False:
                failures.append(f"推荐了不可购买的商品 {gid}")
    return failures
