"""测试替身：按脚本回复的聊天模型、内存 Redis、Java 与索引桩。"""
import asyncio
import json
import uuid
from collections.abc import AsyncIterator, Callable
from typing import Any

from langchain_core.callbacks import AsyncCallbackManagerForLLMRun
from langchain_core.language_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult

from app.config import Settings
from app.graph.context import RequestContext
from app.rag.rules_index import RulesIndex


def call(name: str, **args: Any) -> AIMessage:
    """一条只含工具调用的模型回复。"""
    return AIMessage("", tool_calls=[{"name": name, "args": args, "id": f"call_{uuid.uuid4().hex[:8]}"}])


class ScriptedModel(BaseChatModel):
    """依次返回 script 中的回复；script 元素也可以是 (messages) -> AIMessage 的函数。"""

    script: list[Any]
    seen: list[list[BaseMessage]] = []
    bound_tools: bool = False
    # 偏好抽取调用（save_memory）单独应答，不占用 script
    memory_replies: list[str] = []
    memory_prompts: list[str] = []

    @property
    def _llm_type(self) -> str:
        return "scripted"

    def bind_tools(self, tools: Any, **kwargs: Any) -> "ScriptedModel":
        return self.model_copy(update={"bound_tools": True})

    def _next(self, messages: list[BaseMessage]) -> AIMessage:
        if "长期偏好。根据本轮对话" in messages[-1].text:
            self.memory_prompts.append(messages[-1].text)
            reply = self.memory_replies.pop(0) if self.memory_replies else '{"set": {}, "delete": []}'
            return AIMessage(reply)
        self.seen.append(messages)
        item = self.script.pop(0)
        return item(messages) if isinstance(item, Callable) else item

    def _generate(self, messages: list[BaseMessage], stop: Any = None, run_manager: Any = None,
                  **kwargs: Any) -> ChatResult:
        return ChatResult(generations=[ChatGeneration(message=self._next(messages))])

    async def _astream(self, messages: list[BaseMessage], stop: Any = None,
                       run_manager: AsyncCallbackManagerForLLMRun | None = None,
                       **kwargs: Any) -> AsyncIterator[ChatGenerationChunk]:
        msg = self._next(messages)
        chunks = [
            {"name": tc["name"], "args": json.dumps(tc["args"]), "id": tc["id"], "index": i}
            for i, tc in enumerate(msg.tool_calls)
        ]
        chunk = AIMessageChunk(content=msg.content, tool_call_chunks=chunks,
                               usage_metadata={"input_tokens": 10, "output_tokens": 5, "total_tokens": 15})
        if run_manager and msg.content:
            await run_manager.on_llm_new_token(msg.content, chunk=ChatGenerationChunk(message=chunk))
        yield ChatGenerationChunk(message=chunk)


class FakeRedis:
    def __init__(self) -> None:
        self.data: dict[str, int] = {}

    async def incr(self, key: str) -> int:
        self.data[key] = self.data.get(key, 0) + 1
        return self.data[key]

    async def expire(self, key: str, seconds: int) -> bool:
        return True


class FakeJava:
    def __init__(self, goods: dict[str, dict[str, Any]] | None = None, valid_token: str = "good") -> None:
        self.goods = goods or {}
        self.valid_token = valid_token
        self.calls: list[tuple[str, dict[str, Any]]] = []

    async def get_me(self, token: str) -> dict[str, Any]:
        from app.java_client import JavaAuthError

        if token != self.valid_token:
            raise JavaAuthError(401)
        return {"id": "7", "nickname": "小王", "campusId": "1", "campusName": "东区"}

    async def search_goods(self, token: str, **params: Any) -> list[dict[str, Any]]:
        self.calls.append(("search", params))
        return [{"id": g["id"], "title": g["title"], "price": g["price"], "campusName": g["campusName"],
                 "coverUrl": None, "conditionLevel": 3} for g in self.goods.values()]

    async def goods_detail(self, token: str, goods_id: str) -> dict[str, Any]:
        from app.java_client import JavaApiError

        self.calls.append(("detail", {"id": goods_id}))
        if goods_id not in self.goods:
            raise JavaApiError(2001, "商品不存在")
        return self.goods[goods_id]

    async def list_orders(self, token: str, role: str, status: list[int] | None = None,
                          size: int = 10) -> list[dict[str, Any]]:
        self.calls.append(("orders", {"role": role, "status": status}))
        return [{"id": "6001", "goodsTitle": "台灯", "totalAmount": "15.00", "tradeMode": 2, "status": 50,
                 "counterpart": {"nickname": "卖家"}, "createdAt": "2026-11-20 10:00:00"}]

    async def get_order(self, token: str, order_id: str) -> dict[str, Any]:
        from app.java_client import JavaApiError

        if order_id != "6001":
            raise JavaApiError(403, "无权限")
        return {"id": "6001", "orderNo": "N1", "myRole": "BUYER", "goods": {"title": "台灯", "location": "南区"},
                "totalAmount": "15.00", "tradeMode": 2, "status": 50,
                "deadline": {"action": "AUTO_CONFIRM", "at": "2026-11-27 12:00:00"},
                "actions": ["CONFIRM_RECEIPT", "APPLY_REFUND"]}

    async def create_want(self, token: str, body: dict[str, Any]) -> dict[str, Any]:
        self.calls.append(("create_want", body))
        return {"id": "701"}

    async def fetch_upload(self, relative_path: str) -> tuple[bytes, str]:
        self.calls.append(("fetch_upload", {"path": relative_path}))
        return b"\x89PNG-fake", "image/png"

    async def categories(self) -> list[dict[str, Any]]:
        return [{"id": "1", "name": "数码", "children": [{"id": "101", "name": "显示器"}]}]

    async def campuses(self) -> list[dict[str, Any]]:
        return [{"id": "1", "name": "东区"}, {"id": "2", "name": "西区"}]


class FakeIndex:
    def __init__(self, hits: list[dict[str, Any]] | None = None, reranker: Any = None) -> None:
        self.hits = hits or []
        self.queries: list[str] = []
        self.reranker = reranker

    async def vector_search(self, query: str, flt: Any, top_k: int) -> list[dict[str, Any]]:
        self.queries.append(query)
        return self.hits


class FakeReranker:
    """按文本里是否含关键词打分；fail=True 时抛错。"""

    def __init__(self, keyword: str, fail: bool = False) -> None:
        self.keyword = keyword
        self.fail = fail
        self.calls: list[tuple[str, list[str]]] = []

    async def scores(self, query: str, docs: list[str]) -> list[float]:
        self.calls.append((query, docs))
        if self.fail:
            raise RuntimeError("model down")
        return [1.0 if self.keyword in d else 0.0 for d in docs]


class FakeSearchIndex:
    """内存版 redisvl AsyncSearchIndex：同名索引共享 store 里的同一份文档，query 按写入顺序返回前 n 条。

    load_gate 不为 None 时，load 写完一半先停下等它放行，用来观察重建过程中的检索结果。
    """

    def __init__(self, store: dict[str, list[dict[str, Any]]], name: str,
                 load_gate: asyncio.Event | None = None, loading: asyncio.Event | None = None) -> None:
        self._store = store
        self.name = name
        self._gate = load_gate
        self._loading = loading

    async def exists(self) -> bool:
        return self.name in self._store

    async def create(self, overwrite: bool = False, drop: bool = False) -> None:
        self._store[self.name] = []

    async def load(self, data: list[dict[str, Any]], id_field: str | None = None) -> None:
        half = len(data) // 2
        self._store[self.name].extend(data[:half])
        if self._gate is not None:
            self._loading.set()
            await self._gate.wait()
        self._store[self.name].extend(data[half:])

    async def delete(self, drop: bool = True) -> None:
        self._store.pop(self.name, None)

    async def query(self, q: Any) -> list[dict[str, Any]]:
        if self.name not in self._store:
            raise RuntimeError(f"{self.name}: no such index")
        return self._store[self.name][:q._num_results]


class InMemoryRulesIndex(RulesIndex):
    """RulesIndex 的索引换成 FakeSearchIndex；指针等仍写在传入的 Redis（fakeredis）里。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        super().__init__(*args, **kwargs)
        self.store: dict[str, list[dict[str, Any]]] = {}
        self.load_gate: asyncio.Event | None = None
        self.loading = asyncio.Event()

    def _open(self, name: str, prefix: str) -> Any:
        return FakeSearchIndex(self.store, name, self.load_gate, self.loading)


def goods(gid: str, title: str, price: str, status: int = 2, reason: str | None = None) -> dict[str, Any]:
    return {"id": gid, "title": title, "price": price, "status": status, "cannotBuyReason": reason,
            "campusName": "东区", "images": [f"/uploads/{gid}.jpg"], "conditionLevel": 3,
            "seller": {"nickname": "卖家", "creditLevel": "GOOD", "goodRate": "98.0"}}


def make_ctx(java: FakeJava, index: FakeIndex) -> RequestContext:
    return RequestContext(token="good", user={"id": "7", "nickname": "小王", "campusId": "1", "campusName": "东区"},
                          java=java, goods_index=index, categories=[], campuses=[])


def settings(**overrides: Any) -> Settings:
    return Settings(_env_file=None, **overrides)
