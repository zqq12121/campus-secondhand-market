"""M3：写工具 + interrupt 确认 + /resume、图片输入。"""
import json
from typing import Any

import httpx
from langchain_core.messages import AIMessage, BaseMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from app.graph.drafts import reference_price
from app.main import AppState, app
from tests.fakes import FakeIndex, FakeJava, FakeRedis, ScriptedModel, call, goods, settings

H = {"Authorization": "Bearer good"}
UPLOAD = "http://localhost:8080/uploads"


def setup(script: list, java: FakeJava | None = None) -> tuple[FakeJava, ScriptedModel]:
    s = settings()
    java = java or FakeJava()
    model = ScriptedModel(script=script)
    store = InMemoryStore()
    app.state.ai = AppState(s, FakeRedis(), java, FakeIndex(), build_graph(model, s, InMemorySaver(), store), store)
    return java, model


def parse_sse(text: str) -> list[tuple[str, Any]]:
    events = []
    for block in text.strip().split("\n\n"):
        lines = dict(line.split(": ", 1) for line in block.split("\n"))
        events.append((lines["event"], json.loads(lines["data"])))
    return events


async def post(c: httpx.AsyncClient, path: str, body: dict) -> list[tuple[str, Any]]:
    r = await c.post(path, json=body, headers=H)
    assert r.status_code == 200, r.text
    return parse_sse(r.text)


def client() -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test")


def want_call(**overrides: Any) -> AIMessage:
    args = {"title": "求一台 24 寸显示器", "category_id": "101", "price_max": 300, "valid_days": 7, **overrides}
    return call("draft_want_post", **args)


def last_tool_result(messages: list[BaseMessage]) -> dict:
    return json.loads(next(m for m in reversed(messages) if isinstance(m, ToolMessage)).content)


async def test_want_post_waits_for_confirmation_then_publishes_with_edits() -> None:
    java, model = setup([want_call(), lambda msgs: AIMessage(f"已发布：{last_tool_result(msgs)['id']}，{last_tool_result(msgs)['want']['validDays']} 天")])
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "帮我发个求购，想要显示器，300 以内"})
        confirm = next(d for e, d in first if e == "confirm")
        assert confirm["kind"] == "want_post"
        assert confirm["draft"]["categoryName"] == "数码 > 显示器" and confirm["draft"]["campusName"] == "东区"
        assert first[-1][1]["pending"] is True
        assert not any(name == "create_want" for name, _ in java.calls)  # 确认前不写

        conv = first[-1][1]["conversationId"]
        second = await post(c, f"/api/v1/ai/chat/{conv}/resume",
                            {"interruptId": confirm["interruptId"], "decision": "confirm",
                             "edits": {"validDays": 15, "priceMax": 280, "status": "hack"}})
    body = next(b for name, b in java.calls if name == "create_want")
    assert body == {"title": "求一台 24 寸显示器", "description": "", "categoryId": "101", "campusId": "1",
                    "priceMin": None, "priceMax": "280.00", "validDays": 15}  # 非白名单字段被丢弃
    assert "".join(d for e, d in second if e == "token") == "已发布：701，15 天"
    assert second[-1][1]["pending"] is False


async def test_cancel_does_not_write() -> None:
    java, _ = setup([want_call(), lambda msgs: AIMessage(f"状态：{last_tool_result(msgs)['status']}")])
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "发求购"})
        confirm = next(d for e, d in first if e == "confirm")
        second = await post(c, f"/api/v1/ai/chat/{first[-1][1]['conversationId']}/resume",
                            {"interruptId": confirm["interruptId"], "decision": "cancel"})
    assert "".join(d for e, d in second if e == "token") == "状态：cancelled"
    assert not any(name == "create_want" for name, _ in java.calls)


async def test_resume_with_unknown_interrupt_is_rejected() -> None:
    setup([want_call(), AIMessage("x")])
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "发求购"})
        conv = first[-1][1]["conversationId"]
        r = await c.post(f"/api/v1/ai/chat/{conv}/resume", json={"interruptId": "nope", "decision": "confirm"}, headers=H)
        assert r.status_code == 409
        r = await c.post(f"/api/v1/ai/chat/{conv}/resume", json={"interruptId": "x", "decision": "maybe"}, headers=H)
        assert r.status_code == 422


async def test_new_message_cancels_pending_confirmation() -> None:
    java, model = setup([want_call(), lambda msgs: AIMessage(f"上一步：{last_tool_result(msgs)['status']}")])
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "发求购"})
        conv = first[-1][1]["conversationId"]
        second = await post(c, "/api/v1/ai/chat", {"message": "算了，不发了", "conversationId": conv})
    assert "".join(d for e, d in second if e == "token") == "上一步：cancelled"
    assert second[-1][1]["pending"] is False
    assert not any(name == "create_want" for name, _ in java.calls)
    state = await app.state.ai.graph.aget_state({"configurable": {"thread_id": f"7:{conv}"}})
    assert not state.interrupts


async def test_invalid_draft_is_returned_to_the_model_without_confirmation() -> None:
    setup([want_call(category_id="1"), lambda msgs: AIMessage(last_tool_result(msgs)["error"])])
    async with client() as c:
        events = await post(c, "/api/v1/ai/chat", {"message": "发求购"})
    assert not any(e == "confirm" for e, _ in events)
    assert "".join(d for e, d in events if e == "token") == "分类必须是二级分类"


async def test_listing_draft_uses_comparables_and_images() -> None:
    java = FakeJava({gid: goods(gid, "显示器", p) for gid, p in (("a", "200.00"), ("b", "300.00"), ("c", "260.00"))})
    java_seen: list[list[BaseMessage]] = []

    def answer(msgs: list[BaseMessage]) -> AIMessage:
        java_seen.append(msgs)
        return call("suggest_listing", title="小米 24 寸显示器", description="屏幕无坏点", category_id="101",
                    condition_level=3, keyword="显示器", estimated_price=999)

    _, model = setup([answer, AIMessage("草稿已填入发布页")], java)
    image = f"{UPLOAD}/2026/11/a.png"
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "帮我卖这个", "images": [image]})
        confirm = next(d for e, d in first if e == "confirm")
        assert confirm["kind"] == "listing"
        assert confirm["draft"]["price"] == 260 and confirm["draft"]["priceRange"] == [208, 312]
        assert confirm["draft"]["images"] == [image]
        second = await post(c, f"/api/v1/ai/chat/{first[-1][1]['conversationId']}/resume",
                            {"interruptId": confirm["interruptId"], "decision": "confirm", "edits": {"price": 250}})
    listing = next(d for e, d in second if e == "listing")
    assert listing["price"] == 250 and listing["categoryName"] == "数码 > 显示器" and listing["images"] == [image]
    # 模型收到的是 data URL，checkpoint 里存的仍是公开地址
    sent = java_seen[0][-1].content
    assert sent[1]["image_url"]["url"].startswith("data:image/png;base64,")
    assert ("fetch_upload", {"path": "2026/11/a.png"}) in java.calls
    assert ("search", {"keyword": "显示器", "categoryId": "101", "size": 20}) in java.calls  # 按品类词找同类


async def test_images_must_come_from_platform_uploads() -> None:
    setup([])
    async with client() as c:
        r = await c.post("/api/v1/ai/chat", headers=H,
                         json={"message": "看看", "images": ["http://evil.example/x.png"]})
        assert r.status_code == 400
        r = await c.post("/api/v1/ai/chat", headers=H,
                         json={"message": "看看", "images": [f"{UPLOAD}/{i}.png" for i in range(4)]})
        assert r.status_code == 422


async def test_earlier_images_become_placeholders() -> None:
    java, model = setup([AIMessage("看到了"), AIMessage("好的")])
    async with client() as c:
        first = await post(c, "/api/v1/ai/chat", {"message": "这个", "images": [f"{UPLOAD}/a.png"]})
        await post(c, "/api/v1/ai/chat", {"message": "继续", "conversationId": first[-1][1]["conversationId"]})
    second_turn = model.seen[-1]
    assert second_turn[1].content == "这个 [用户上传的图片]"


def test_reference_price() -> None:
    assert reference_price([100, 200, 300, 0], 999)["price"] == 200
    fallback = reference_price([100, 0], 150)
    assert fallback["price"] == 150 and fallback["priceRange"] is None and "仅供参考" in fallback["priceBasis"]
