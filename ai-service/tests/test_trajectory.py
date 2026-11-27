"""轨迹判定的离线回归：用 ScriptedModel 跑真实的图，验证 evals/trajectory.py 的判定函数本身是对的。"""
import dataclasses
import json
from typing import Any

import pytest
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from evals.run_trajectory import load_cases
from evals.trajectory import CHECK_KEYS, check, extract, recommended_goods
from tests.fakes import FakeIndex, FakeJava, ScriptedModel, call, goods, make_ctx, settings

GOODS = {"g1": goods("g1", "护眼台灯", "15.00"), "g2": goods("g2", "旧台灯", "5.00", reason="BLOCKED")}


async def run(script: list[Any], question: str = "想要台灯") -> tuple[list, str]:
    graph = build_graph(ScriptedModel(script=script), settings(), InMemorySaver(), InMemoryStore())
    java = FakeJava(GOODS)
    ctx = dataclasses.replace(make_ctx(java, FakeIndex()), categories=await java.categories(),
                              campuses=await java.campuses())
    state = await graph.ainvoke({"messages": [HumanMessage(question)]}, {"configurable": {"thread_id": "7:t", "ctx": ctx}})
    return extract(state["messages"])


RECOMMEND = {"verify_recommended": True, "must_call": ["search_goods"]}


async def test_recommend_without_verifying_is_flagged() -> None:
    steps, answer = await run([call("search_goods", query="台灯"), AIMessage("推荐护眼台灯，15 元。")])
    assert [g["id"] for g in recommended_goods(steps, answer)] == ["g1"]
    assert check(RECOMMEND, steps, answer) == ["推荐 g1（护眼台灯）前没有调用 get_goods_detail 核实"]


async def test_recommend_after_verifying_passes() -> None:
    steps, answer = await run([call("search_goods", query="台灯"), call("get_goods_detail", goods_id="g1"),
                               AIMessage("推荐护眼台灯（g1），15 元。")])
    assert check(RECOMMEND, steps, answer) == []


async def test_recommending_unavailable_goods_is_flagged() -> None:
    steps, answer = await run([call("search_goods", query="台灯"), call("get_goods_detail", goods_id="g2"),
                               AIMessage("旧台灯只要 5 元。")])
    assert check(RECOMMEND, steps, answer) == ["推荐了不可购买的商品 g2"]


async def test_search_results_not_mentioned_are_not_recommendations() -> None:
    steps, answer = await run([call("search_goods", query="台灯"), AIMessage("没有找到合适的，建议放宽条件。")])
    assert recommended_goods(steps, answer) == [] and check(RECOMMEND, steps, answer) == []


async def test_rules_question_must_call_search_rules() -> None:
    case = {"must_call": ["search_rules"]}
    steps, answer = await run([AIMessage("不能退款。")], "确认收货后还能退款吗")
    assert check(case, steps, answer) == ["没有调用 search_rules"]
    steps, answer = await run([call("search_rules", question="确认收货后退款"), AIMessage("不确定。")])
    assert check(case, steps, answer) == []


async def test_order_question_must_not_call_write_tools() -> None:
    case = {"must_call_any": ["list_my_orders", "get_order"], "must_not_call": ["draft_want_post", "suggest_listing"]}
    steps, answer = await run([call("list_my_orders"), AIMessage("你有 1 个待收货订单。")], "我的订单")
    assert check(case, steps, answer) == []
    # 写工具会停在确认卡片：没有工具结果，但调用本身已经违规
    steps, answer = await run([call("draft_want_post", title="求购", category_id="101", price_max=10, valid_days=7)])
    assert steps[0].result is None and answer == ""
    assert check(case, steps, answer) == ["list_my_orders / get_order 一个都没调用", "不该调用 draft_want_post"]


async def test_chitchat_must_not_call_tools() -> None:
    steps, answer = await run([AIMessage("你好！")], "你好")
    assert check({"no_tools": True}, steps, answer) == [] and answer == "你好！"
    steps, answer = await run([call("search_rules", question="你好"), AIMessage("你好！")], "你好")
    assert check({"no_tools": True}, steps, answer) == ["闲聊不该调用工具：['search_rules']"]


@pytest.mark.parametrize("case", load_cases(), ids=lambda c: c["id"])
def test_trajectory_cases_are_well_formed(case: dict[str, Any]) -> None:
    assert {"id", "category", "question"} <= set(case)
    assert set(case) - {"id", "category", "question"} <= CHECK_KEYS
    assert set(case) & CHECK_KEYS, "每条用例至少要有一个约束"


def test_trajectory_set_covers_each_scenario() -> None:
    cases = load_cases()
    assert 15 <= len(cases) <= 20 and len({c["id"] for c in cases}) == len(cases)
    assert {c["category"] for c in cases} == {"规则", "找物", "订单", "闲聊", "写操作"}


def test_rules_cases_have_reference() -> None:
    from evals.judge import CASES

    cases = [json.loads(line) for line in CASES.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert all(c.get("reference") for c in cases)
