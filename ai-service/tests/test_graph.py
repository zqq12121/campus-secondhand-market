import json

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver

from app.graph.builder import build_graph
from tests.fakes import FakeIndex, FakeJava, ScriptedModel, call, goods, make_ctx, settings


def config(ctx, thread: str = "7:t1") -> dict:
    return {"configurable": {"thread_id": thread, "ctx": ctx}}


async def test_search_then_verify_then_answer_and_follow_up_keeps_context() -> None:
    java = FakeJava({"g1": goods("g1", "24 寸显示器", "90.00"), "g2": goods("g2", "27 寸显示器", "60.00")})
    index = FakeIndex([{"id": "ai:goods:g1", "goods_id": "g1", "title": "24 寸显示器", "price": "90", "campus_name": "东区",
                        "category_name": "显示器", "cover_url": "", "condition_level": "3"}])
    model = ScriptedModel(script=[
        call("search_goods", query="显示器", campus_id="1", max_price=100),
        call("get_goods_detail", goods_id="g1"),
        AIMessage("推荐 g1：24 寸显示器 90 元。"),
        # 第二轮：追问更便宜的
        call("get_goods_detail", goods_id="g2"),
        AIMessage("更便宜的有 g2，60 元。"),
    ])
    graph = build_graph(model, settings(), InMemorySaver())
    ctx = make_ctx(java, index)

    out = await graph.ainvoke({"messages": [HumanMessage("东区 100 以内的显示器")]}, config(ctx))
    assert out["messages"][-1].content == "推荐 g1：24 寸显示器 90 元。"
    search_result = json.loads(next(m for m in out["messages"] if isinstance(m, ToolMessage)).content)
    assert {g["id"] for g in search_result["goods"]} == {"g1", "g2"}  # 向量 + 关键词合并
    assert ("search", {"keyword": "显示器", "campusId": "1", "categoryId": None, "priceMin": None,
                       "priceMax": 100, "size": 20}) in java.calls

    out = await graph.ainvoke({"messages": [HumanMessage("有没有更便宜的")]}, config(ctx))
    assert out["messages"][-1].content == "更便宜的有 g2，60 元。"
    # 第二轮模型看到了第一轮的全部上下文（短期记忆）
    second_turn_input = model.seen[3]
    assert isinstance(second_turn_input[0], SystemMessage)
    assert any(m.content == "东区 100 以内的显示器" for m in second_turn_input)


async def test_unavailable_goods_are_marked() -> None:
    java = FakeJava({"g1": goods("g1", "台灯", "10.00", status=5)})
    model = ScriptedModel(script=[call("get_goods_detail", goods_id="g1"),
                                  call("get_goods_detail", goods_id="nope"), AIMessage("都卖掉了")])
    graph = build_graph(model, settings(), InMemorySaver())
    out = await graph.ainvoke({"messages": [HumanMessage("台灯")]}, config(make_ctx(java, FakeIndex())))
    results = [json.loads(m.content) for m in out["messages"] if isinstance(m, ToolMessage)]
    assert results[0]["available"] is False
    assert results[1] == {"id": "nope", "available": False, "reason": "商品不存在"}


async def test_tool_call_limit_forces_answer() -> None:
    java = FakeJava({"g1": goods("g1", "台灯", "10.00")})
    model = ScriptedModel(script=[call("get_goods_detail", goods_id="g1"), call("get_goods_detail", goods_id="g1"),
                                  AIMessage("就这些")])
    graph = build_graph(model, settings(ai_max_tool_calls=2), InMemorySaver())
    out = await graph.ainvoke({"messages": [HumanMessage("台灯")]}, config(make_ctx(java, FakeIndex())))
    assert out["messages"][-1].content == "就这些"
    assert "上限" in model.seen[-1][-1].content


async def test_long_history_is_summarized() -> None:
    java = FakeJava()
    script: list = []
    for i in range(6):
        script.append(AIMessage(f"a{i}"))
    # 第 5 轮（q4）开始时历史 9 条 > 8，先调一次模型生成摘要
    script.insert(4, AIMessage("用户想要显示器"))
    model = ScriptedModel(script=script)
    graph = build_graph(model, settings(ai_history_max_messages=8, ai_history_keep_messages=4), InMemorySaver())
    ctx = make_ctx(java, FakeIndex())
    for i in range(5):
        await graph.ainvoke({"messages": [HumanMessage(f"q{i}")]}, config(ctx))
    out = await graph.ainvoke({"messages": [HumanMessage("q5")]}, config(ctx))
    assert out["summary"] == "用户想要显示器"
    texts = [m.content for m in out["messages"]]
    assert "q0" not in texts and "q1" not in texts and texts[-1] == "a5"
    assert "用户想要显示器" in model.seen[-1][0].content
