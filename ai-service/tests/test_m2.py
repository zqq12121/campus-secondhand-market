"""M2：长期记忆、规则知识库切片、订单工具、规则检索工具。"""
import dataclasses
import json
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from app.graph.memory import parse_update
from app.rag.rules_index import KNOWLEDGE_DIR, MAX_CHUNK_CHARS, split_markdown
from tests.fakes import FakeIndex, FakeJava, ScriptedModel, call, make_ctx, settings


def config(ctx: Any, thread: str) -> dict:
    return {"configurable": {"thread_id": thread, "ctx": ctx}}


async def test_preference_is_saved_and_used_in_a_new_conversation() -> None:
    store = InMemoryStore()
    model = ScriptedModel(script=[AIMessage("好的，记住了"), AIMessage("按贝克兰德找的……")],
                          memory_replies=['{"set": {"campus": "贝克兰德", "phone": "138"}, "delete": []}'])
    graph = build_graph(model, settings(), InMemorySaver(), store)
    ctx = make_ctx(FakeJava(), FakeIndex())

    await graph.ainvoke({"messages": [HumanMessage("我一般在贝克兰德")]}, config(ctx, "7:a"))
    saved = await store.aget(("user", "7"), "campus")
    assert saved.value == {"value": "贝克兰德"}
    assert await store.aget(("user", "7"), "phone") is None  # 非白名单槽位被丢弃

    await graph.ainvoke({"messages": [HumanMessage("找个台灯")]}, config(ctx, "7:b"))  # 新会话
    system_prompt = model.seen[-1][0].content
    assert "常用校区：贝克兰德" in system_prompt
    assert '"campus": "贝克兰德"' in model.memory_prompts[-1]  # 抽取时能看到已有偏好


async def test_preference_can_be_deleted_by_user_correction() -> None:
    store = InMemoryStore()
    await store.aput(("user", "7"), "campus", {"value": "贝克兰德"})
    model = ScriptedModel(script=[AIMessage("好的")], memory_replies=['{"set": {}, "delete": ["campus"]}'])
    graph = build_graph(model, settings(), InMemorySaver(), store)
    await graph.ainvoke({"messages": [HumanMessage("我搬去廷根了，别按贝克兰德找")]},
                        config(make_ctx(FakeJava(), FakeIndex()), "7:c"))
    assert await store.aget(("user", "7"), "campus") is None


async def test_bad_extraction_output_does_not_break_the_turn() -> None:
    model = ScriptedModel(script=[AIMessage("你好")], memory_replies=["不是 JSON"])
    graph = build_graph(model, settings(), InMemorySaver(), InMemoryStore())
    out = await graph.ainvoke({"messages": [HumanMessage("hi")]}, config(make_ctx(FakeJava(), FakeIndex()), "7:d"))
    assert out["messages"][-1].content == "你好"


def test_parse_update_filters_and_truncates() -> None:
    u = parse_update('```json\n{"set": {"budget": "' + "很" * 100 + '", "x": "y", "avoid": " "}, '
                     '"delete": ["campus", "bad"]}\n```')
    assert set(u.set) == {"budget"} and len(u.set["budget"]) == 60
    assert u.delete == ["campus"]
    assert parse_update("no json") is None


def test_split_markdown_by_section_with_size_limit() -> None:
    text = "# 售后\n\n## 申请条件\n\n" + "\n\n".join(["一段规则说明。" * 20] * 4) + "\n\n## 撤销\n\n可以撤销。"
    chunks = split_markdown("aftersale", text)
    assert {c.title for c in chunks} == {"售后 > 申请条件", "售后 > 撤销"}
    assert all(len(c.content) <= MAX_CHUNK_CHARS for c in chunks)
    assert len({c.chunk_id for c in chunks}) == len(chunks)


def test_knowledge_base_files_are_chunked() -> None:
    chunks = []
    for path in KNOWLEDGE_DIR.glob("*.md"):
        chunks += split_markdown(path.stem, path.read_text(encoding="utf-8"))
    assert len(chunks) >= 20
    assert any("信用分 > 扣分" == c.title for c in chunks)


class FakeRules:
    async def search(self, question: str, top_k: int = 5) -> list[dict[str, Any]]:
        return [{"source": "售后与纠纷 > 哪些订单可以申请售后", "content": "订单已完成后，不能再申请售后。"}]


async def test_rules_and_order_tools() -> None:
    java = FakeJava()
    ctx = dataclasses.replace(make_ctx(java, FakeIndex()), rules_index=FakeRules())
    model = ScriptedModel(script=[
        call("search_rules", question="确认收货后还能退款吗"),
        call("list_my_orders", role="buyer", status=[50]),
        call("get_order", order_id="6001"),
        call("get_order", order_id="9999"),
        AIMessage("不能。（来源：售后与纠纷 > 哪些订单可以申请售后）"),
    ])
    graph = build_graph(model, settings(), InMemorySaver(), InMemoryStore())
    out = await graph.ainvoke({"messages": [HumanMessage("确认收货后还能退款吗，我那单台灯呢")]}, config(ctx, "7:e"))
    results = [json.loads(m.content) for m in out["messages"] if isinstance(m, ToolMessage)]
    assert results[0]["results"][0]["source"].startswith("售后与纠纷")
    assert results[1]["role"] == "BUYER" and results[1]["orders"][0]["status"] == "待收货"
    assert ("orders", {"role": "BUYER", "status": [50]}) in java.calls
    assert results[2]["status"] == "待收货" and results[2]["actions"] == ["CONFIRM_RECEIPT", "APPLY_REFUND"]
    assert results[3] == {"id": "9999", "error": "无权限"}
