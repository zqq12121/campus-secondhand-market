"""下游故障容错：大模型降级到备用模型、Java 调用熔断。"""
import json
import logging
from typing import Any

import httpx
import openai
import pytest
from fakeredis import FakeAsyncRedis
from langchain_core.messages import AIMessage, BaseMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.graph.builder import build_graph
from app.java_client import CircuitBreaker, JavaApiError, JavaAuthError, JavaClient
from app.main import AppState, app, build_fallback_llm, build_llm
from tests.fakes import FakeIndex, FakeJava, ScriptedModel, call, goods, make_ctx, settings
from tests.test_api import client, parse_sse

H = {"Authorization": "Bearer good"}


def timeout(_: list[BaseMessage]) -> AIMessage:
    raise openai.APITimeoutError(request=httpx.Request("POST", "https://api.deepseek.com/chat/completions"))


def rate_limited(_: list[BaseMessage]) -> AIMessage:
    req = httpx.Request("POST", "https://api.deepseek.com/chat/completions")
    raise openai.RateLimitError("429", response=httpx.Response(429, request=req), body=None)


# ---------- 大模型降级 ----------

def test_fallback_llm_only_when_configured() -> None:
    assert build_fallback_llm(settings()) is None
    assert build_llm(settings()).max_retries == 2  # 没有备用模型时行为不变
    s = settings(fallback_model="gpt-x", fallback_api_key="sk-test", fallback_base_url="http://fallback/v1")
    fb = build_fallback_llm(s)
    assert fb is not None and fb.model_name == "gpt-x"
    assert build_llm(s).max_retries == 0  # 有备用时失败一次就切


async def test_primary_failure_answers_with_fallback() -> None:
    primary = ScriptedModel(script=[timeout])
    fallback = ScriptedModel(script=[AIMessage("备用模型的回答")])
    graph = build_graph(primary, settings(), InMemorySaver(), InMemoryStore(), fallback)
    out = await graph.ainvoke({"messages": [HumanMessage("hi")]},
                              {"configurable": {"thread_id": "7:f1", "ctx": make_ctx(FakeJava(), FakeIndex())}})
    assert out["messages"][-1].content == "备用模型的回答"


async def test_tool_calls_also_fall_back() -> None:
    java = FakeJava({"g1": goods("g1", "台灯", "15.00")})
    primary = ScriptedModel(script=[rate_limited, timeout])
    fallback = ScriptedModel(script=[call("get_goods_detail", goods_id="g1"), AIMessage("推荐 g1")])
    graph = build_graph(primary, settings(), InMemorySaver(), InMemoryStore(), fallback)
    out = await graph.ainvoke({"messages": [HumanMessage("台灯")]},
                              {"configurable": {"thread_id": "7:f2", "ctx": make_ctx(java, FakeIndex())}})
    assert primary.script == [] and fallback.script == []  # 两次 agent 调用都由备用模型完成
    assert json.loads(next(m for m in out["messages"] if isinstance(m, ToolMessage)).content)["available"] is True
    assert out["messages"][-1].content == "推荐 g1"


async def test_non_transient_error_does_not_fall_back() -> None:
    def bad_request(_: list[BaseMessage]) -> AIMessage:
        raise ValueError("bad request")

    fallback = ScriptedModel(script=[AIMessage("不该用到")])
    graph = build_graph(ScriptedModel(script=[bad_request]), settings(), InMemorySaver(), InMemoryStore(), fallback)
    with pytest.raises(ValueError):
        await graph.ainvoke({"messages": [HumanMessage("hi")]},
                            {"configurable": {"thread_id": "7:f3", "ctx": make_ctx(FakeJava(), FakeIndex())}})
    assert fallback.script == [AIMessage("不该用到")]


async def test_chat_trace_marks_fallback_calls(caplog: pytest.LogCaptureFixture) -> None:
    s = settings()
    store = InMemoryStore()
    graph = build_graph(ScriptedModel(script=[timeout]), s, InMemorySaver(), store,
                        ScriptedModel(script=[AIMessage("备用")]))
    app.state.ai = AppState(s, FakeAsyncRedis(), FakeJava(), FakeIndex(), graph, store)
    with caplog.at_level(logging.INFO, logger="ai-service"):
        async with client() as c:
            events = parse_sse((await c.post("/api/v1/ai/chat", json={"message": "hi"}, headers=H)).text)
    assert "".join(d for e, d in events if e == "token") == "备用"
    line = next(r.getMessage() for r in caplog.records if r.getMessage().startswith("chat_trace "))
    trace = json.loads(line.removeprefix("chat_trace "))
    agent_calls = [c for c in trace["llm"] if c["node"] == "agent"]
    assert agent_calls[0]["error"] == "APITimeoutError" and "fallback" not in agent_calls[0]
    assert agent_calls[1]["fallback"] is True
    assert trace["fallback"] is True


# ---------- 熔断器 ----------

class Clock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


def test_breaker_closed_open_half_open_closed() -> None:
    clock = Clock()
    b = CircuitBreaker(3, 30, clock)
    for _ in range(2):
        assert b.allow()
        b.record_failure()
    assert b.state == "closed"
    b.record_success()  # 中间成功一次，计数清零
    for _ in range(3):
        b.allow()
        b.record_failure()
    assert b.state == "open" and not b.allow()

    clock.now = 29
    assert not b.allow()
    clock.now = 30
    assert b.allow() and b.state == "half_open"  # 冷却结束，放一个试探请求
    assert not b.allow()  # 试探请求还没回来，其他请求继续拒绝
    b.record_success()
    assert b.state == "closed" and b.allow()


def test_breaker_failed_probe_reopens() -> None:
    clock = Clock()
    b = CircuitBreaker(1, 10, clock)
    b.record_failure()
    clock.now = 10
    assert b.allow()
    b.record_failure()  # 试探失败，再熔断一个周期
    assert b.state == "open" and not b.allow()
    clock.now = 19
    assert not b.allow()
    clock.now = 20
    assert b.allow()


def test_breaker_lost_probe_lets_another_through() -> None:
    clock = Clock()
    b = CircuitBreaker(1, 10, clock)
    b.record_failure()
    clock.now = 10
    assert b.allow()  # 试探请求发出后被取消，没有结果
    clock.now = 20
    assert b.allow()


def test_breaker_disabled_when_threshold_zero() -> None:
    b = CircuitBreaker(0, 30)
    for _ in range(100):
        b.record_failure()
    assert b.allow() and b.state == "closed"


class JavaStub:
    """按顺序返回预设响应的 Java；记录收到的请求数。"""

    def __init__(self, responses: list[Any]) -> None:
        self.responses = responses
        self.count = 0

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.count += 1
        r = self.responses.pop(0) if len(self.responses) > 1 else self.responses[0]
        if isinstance(r, Exception):
            raise r
        status, body = r
        return httpx.Response(status, json=body)


def java_with(stub: JavaStub, clock: Clock, threshold: int = 2) -> JavaClient:
    return JavaClient("http://java", transport=httpx.MockTransport(stub), breaker=CircuitBreaker(threshold, 30, clock))


async def test_java_breaker_opens_after_5xx_and_recovers() -> None:
    clock = Clock()
    stub = JavaStub([(500, {}), (502, {}), (200, {"code": 0, "data": {"id": "7"}})])
    java = java_with(stub, clock)
    for _ in range(2):
        with pytest.raises(httpx.HTTPStatusError):
            await java.get_me("t")
    with pytest.raises(JavaApiError) as e:
        await java.get_me("t")
    assert e.value.code == 503 and e.value.message == "服务暂不可用"
    assert stub.count == 2  # 熔断期间不再发请求

    clock.now = 30
    assert await java.get_me("t") == {"id": "7"}  # 半开试探成功，关闭熔断
    assert await java.get_me("t") == {"id": "7"}
    assert stub.count == 4


async def test_java_breaker_counts_network_errors() -> None:
    clock = Clock()
    stub = JavaStub([httpx.ReadTimeout("slow")])
    java = java_with(stub, clock)
    for _ in range(2):
        with pytest.raises(httpx.ReadTimeout):
            await java.get_me("t")
    with pytest.raises(JavaApiError):
        await java.get_me("t")
    assert stub.count == 2


async def test_business_and_auth_errors_do_not_trip_breaker() -> None:
    clock = Clock()
    stub = JavaStub([(401, {}), (403, {}), (200, {"code": 2001, "message": "商品不存在"}), (404, {}),
                     (200, {"code": 2001, "message": "商品不存在"})])
    java = java_with(stub, clock, threshold=1)
    with pytest.raises(JavaAuthError):
        await java.get_me("t")
    with pytest.raises(JavaApiError):
        await java.goods_detail("t", "1")
    with pytest.raises(JavaApiError):
        await java.goods_detail("t", "1")
    with pytest.raises(httpx.HTTPStatusError):
        await java.goods_detail("t", "1")
    with pytest.raises(JavaApiError) as e:
        await java.goods_detail("t", "1")
    assert e.value.code == 2001 and stub.count == 5  # 阈值是 1 也没熔断


async def test_default_transport_retries_connect_errors() -> None:
    java = JavaClient("http://java")
    assert java._http._transport._pool._retries == 2
    await java.aclose()


async def test_breaker_open_on_login_returns_503() -> None:
    class DownJava(FakeJava):
        async def get_me(self, token: str) -> dict[str, Any]:
            raise JavaApiError(503, "服务暂不可用")

    s = settings()
    store = InMemoryStore()
    app.state.ai = AppState(s, FakeAsyncRedis(), DownJava(), FakeIndex(),
                            build_graph(ScriptedModel(script=[]), s, InMemorySaver(), store), store)
    async with client() as c:
        r = await c.post("/api/v1/ai/chat", json={"message": "hi"}, headers=H)
    assert r.status_code == 503 and r.json() == {"code": 503, "message": "服务暂不可用", "data": None}
