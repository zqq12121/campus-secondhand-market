import json

import httpx
from fakeredis import FakeAsyncRedis
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from app.main import AppState, app
from tests.fakes import FakeIndex, FakeJava, ScriptedModel, call, goods, settings


def setup_app(script: list, **overrides: object) -> FakeJava:
    s = settings(**overrides)
    java = FakeJava({"g1": goods("g1", "台灯", "15.00"), "g2": goods("g2", "旧台灯", "5.00", reason="BLOCKED")})
    store = InMemoryStore()
    graph = build_graph(ScriptedModel(script=script), s, InMemorySaver(), store)
    app.state.ai = AppState(s, FakeAsyncRedis(), java, FakeIndex(), graph, store)
    return java


def client() -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def parse_sse(text: str) -> list[tuple[str, object]]:
    events = []
    for block in text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.split("\n"))
        events.append((lines["event"], json.loads(lines["data"])))
    return events


async def test_requires_login() -> None:
    setup_app([])
    async with client() as c:
        r1 = await c.post("/api/v1/ai/chat", json={"message": "hi"})
        r2 = await c.post("/api/v1/ai/chat", json={"message": "hi"}, headers={"Authorization": "Bearer bad"})
    assert r1.status_code == 401 and r1.json()["code"] == 401
    assert r2.status_code == 401


async def test_stream_emits_tools_goods_tokens_and_done() -> None:
    first = call("get_goods_detail", goods_id="g1")
    first.content = "我查一下。"
    setup_app([first, call("get_goods_detail", goods_id="g2"), AIMessage("推荐台灯 g1。")])
    async with client() as c:
        r = await c.post("/api/v1/ai/chat", json={"message": "想要台灯"}, headers={"Authorization": "Bearer good"})
    assert r.headers["content-type"].startswith("text/event-stream")
    events = parse_sse(r.text)
    names = [e for e, _ in events]
    assert names.count("tool") == 4  # 两次调用各一对 start / end
    goods_events = [d for e, d in events if e == "goods"]
    assert goods_events == [[{"id": "g1", "title": "台灯", "price": "15.00", "conditionLevel": 3,
                              "campusName": "东区", "coverUrl": "/uploads/g1.jpg"}]]  # 被拉黑的 g2 不推卡片
    assert "".join(d for e, d in events if e == "token") == "我查一下。\n\n推荐台灯 g1。"
    # 3 次 agent 调用，每次 5；普通找物消息不触发偏好抽取
    assert names[-1] == "done" and events[-1][1]["usage"]["output_tokens"] == 15


async def test_conversation_continues_with_same_id() -> None:
    setup_app([AIMessage("你好"), AIMessage("还记得")])
    async with client() as c:
        h = {"Authorization": "Bearer good"}
        first = parse_sse((await c.post("/api/v1/ai/chat", json={"message": "hi"}, headers=h)).text)
        conv = first[-1][1]["conversationId"]
        second = parse_sse((await c.post("/api/v1/ai/chat", json={"message": "again", "conversationId": conv},
                                         headers=h)).text)
    # 「again」没有偏好信号词，usage 只有 agent 那次调用
    assert second[-1] == ("done", {"conversationId": conv, "usage": {"input_tokens": 10, "output_tokens": 5},
                                       "pending": False})
    state = await app.state.ai.graph.aget_state({"configurable": {"thread_id": f"7:{conv}"}})
    assert [m.content for m in state.values["messages"]] == ["hi", "你好", "again", "还记得"]


async def test_rate_limited() -> None:
    setup_app([AIMessage("1"), AIMessage("2")], ai_rate_per_minute=2)
    async with client() as c:
        h = {"Authorization": "Bearer good"}
        codes = [(await c.post("/api/v1/ai/chat", json={"message": "x"}, headers=h)).status_code for _ in range(3)]
    assert codes == [200, 200, 429]


async def test_model_failure_becomes_error_event() -> None:
    setup_app([])  # 脚本为空，模型一调用就抛错
    async with client() as c:
        r = await c.post("/api/v1/ai/chat", json={"message": "x"}, headers={"Authorization": "Bearer good"})
    assert parse_sse(r.text)[-1][0] == "error"


async def test_memory_endpoints_list_and_delete() -> None:
    setup_app([])
    store = app.state.ai.store
    await store.aput(("user", "7"), "campus", {"value": "贝克兰德"})
    await store.aput(("user", "7"), "budget", {"value": "300 以内"})
    await store.aput(("user", "8"), "campus", {"value": "廷根"})  # 别人的偏好不可见
    h = {"Authorization": "Bearer good"}
    async with client() as c:
        listed = (await c.get("/api/v1/ai/memory", headers=h)).json()["data"]
        assert {(m["key"], m["label"], m["value"]) for m in listed} == {("campus", "常用校区", "贝克兰德"),
                                                                        ("budget", "预算习惯", "300 以内")}
        assert (await c.delete("/api/v1/ai/memory/campus", headers=h)).status_code == 200
        assert (await c.delete("/api/v1/ai/memory/unknown", headers=h)).status_code == 404
        assert [m["key"] for m in (await c.get("/api/v1/ai/memory", headers=h)).json()["data"]] == ["budget"]
        await c.delete("/api/v1/ai/memory", headers=h)
        assert (await c.get("/api/v1/ai/memory", headers=h)).json()["data"] == []
        assert (await c.get("/api/v1/ai/memory")).status_code == 401
    assert (await store.aget(("user", "8"), "campus")) is not None
