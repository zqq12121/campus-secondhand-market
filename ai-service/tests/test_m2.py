"""M2：长期记忆、规则知识库切片、订单工具、规则检索工具。"""
import dataclasses
import json
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from app.graph.memory import MemoryUpdate, clean_update, should_extract
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

    await graph.ainvoke({"messages": [HumanMessage("平时用的台灯坏了，找个台灯")]}, config(ctx, "7:b"))  # 新会话
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
    out = await graph.ainvoke({"messages": [HumanMessage("我平时在东区")]},
                              config(make_ctx(FakeJava(), FakeIndex()), "7:d"))
    assert out["messages"][-1].content == "你好"


def test_clean_update_filters_and_truncates() -> None:
    u = clean_update(MemoryUpdate(set={"budget": "很" * 100, "x": "y", "avoid": " "}, delete=["campus", "bad"]))
    assert set(u.set) == {"budget"} and len(u.set["budget"]) == 60
    assert u.delete == ["campus"]


def test_memory_update_schema_describes_slots() -> None:
    props = MemoryUpdate.model_json_schema()["properties"]
    assert all(k in props["set"]["description"] for k in ("campus", "budget", "interests", "avoid", "trade_pref"))
    assert "删除" in props["delete"]["description"]


async def test_long_value_truncated_and_bad_slot_dropped_via_structured_output() -> None:
    store = InMemoryStore()
    model = ScriptedModel(script=[AIMessage("好的")], memory_replies=[
        json.dumps({"set": {"interests": "显示器" * 30, "phone": "13812345678"}, "delete": ["nope"]})])
    graph = build_graph(model, settings(), InMemorySaver(), store)
    await graph.ainvoke({"messages": [HumanMessage("我平时喜欢淘显示器")]}, config(make_ctx(FakeJava(), FakeIndex()), "7:s"))
    assert len((await store.aget(("user", "7"), "interests")).value["value"]) == 60
    assert await store.aget(("user", "7"), "phone") is None


async def test_extraction_model_error_does_not_break_the_turn() -> None:
    model = ScriptedModel(script=[AIMessage("你好")], memory_replies=[RuntimeError("model down")])
    graph = build_graph(model, settings(), InMemorySaver(), InMemoryStore())
    out = await graph.ainvoke({"messages": [HumanMessage("以后都只要面交")]},
                              config(make_ctx(FakeJava(), FakeIndex()), "7:x"))
    assert out["messages"][-1].content == "你好"


async def test_invalid_structured_args_do_not_break_the_turn() -> None:
    model = ScriptedModel(script=[AIMessage("你好")], memory_replies=['{"set": "不是对象"}'])
    graph = build_graph(model, settings(), InMemorySaver(), InMemoryStore())
    out = await graph.ainvoke({"messages": [HumanMessage("以后都只要面交")]},
                              config(make_ctx(FakeJava(), FakeIndex()), "7:y"))
    assert out["messages"][-1].content == "你好"


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


# ---------- 抽取预判 ----------

def test_should_extract_signals_and_corrections() -> None:
    assert not should_extract("东区 100 以内的显示器", {})
    assert not should_extract("确认收货后还能退款吗", {})
    assert should_extract("我一般预算 300 以内", {})
    assert should_extract("以后都只要面交", {})
    assert should_extract("别给我推教材了", {})
    assert not should_extract("不对，是 27 寸的", {})  # 没有已有偏好，「不对」只是在改本次需求
    assert should_extract("不对，我不在贝克兰德了", {"campus": "贝克兰德"})
    assert should_extract("改成廷根", {"campus": "贝克兰德"})


async def test_plain_search_skips_extraction() -> None:
    model = ScriptedModel(script=[AIMessage("找到了")])
    graph = build_graph(model, settings(), InMemorySaver(), InMemoryStore())
    out = await graph.ainvoke({"messages": [HumanMessage("东区 100 以内的显示器")]},
                              config(make_ctx(FakeJava(), FakeIndex()), "7:p"))
    assert model.memory_prompts == []  # 模型没收到抽取提示词
    assert out["memory_skipped"] is True


async def test_signal_message_still_extracts() -> None:
    store = InMemoryStore()
    model = ScriptedModel(script=[AIMessage("好的")], memory_replies=['{"set": {"trade_pref": "只要面交"}}'])
    graph = build_graph(model, settings(), InMemorySaver(), store)
    out = await graph.ainvoke({"messages": [HumanMessage("以后都只要面交")]},
                              config(make_ctx(FakeJava(), FakeIndex()), "7:q"))
    assert len(model.memory_prompts) == 1 and out["memory_skipped"] is False
    assert (await store.aget(("user", "7"), "trade_pref")).value == {"value": "只要面交"}


async def test_correction_with_existing_profile_still_extracts() -> None:
    store = InMemoryStore()
    await store.aput(("user", "7"), "campus", {"value": "贝克兰德"})
    model = ScriptedModel(script=[AIMessage("好的")], memory_replies=['{"set": {"campus": "廷根"}}'])
    graph = build_graph(model, settings(), InMemorySaver(), store)
    await graph.ainvoke({"messages": [HumanMessage("不对，改成廷根")]}, config(make_ctx(FakeJava(), FakeIndex()), "7:r"))
    assert len(model.memory_prompts) == 1
    assert (await store.aget(("user", "7"), "campus")).value == {"value": "廷根"}
