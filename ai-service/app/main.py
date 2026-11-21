"""ai-service 入口：POST /api/v1/ai/chat（SSE 流式）。"""
import asyncio
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, ToolMessage
from langchain_deepseek import ChatDeepSeek
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.graph.state import CompiledStateGraph
from pydantic import BaseModel, Field
from redis.asyncio import Redis

from app.config import Settings, get_settings
from app.graph.builder import build_graph
from app.graph.context import RequestContext
from app.java_client import JavaAuthError, JavaClient
from app.rag.embedder import SentenceTransformerEmbedder
from app.rag.goods_index import GoodsIndex
from app.ratelimit import check_rate

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ai-service")

META_TTL_SECONDS = 600


class ChatRequest(BaseModel):
    conversationId: str | None = Field(default=None, pattern=r"^[0-9a-f-]{36}$")
    message: str = Field(min_length=1, max_length=1000)


class AppState:
    """进程级依赖，lifespan 中创建。分类 / 校区列表缓存 10 分钟。"""

    def __init__(self, settings: Settings, redis: Redis, java: JavaClient, goods_index: GoodsIndex,
                 graph: CompiledStateGraph) -> None:
        self.settings = settings
        self.redis = redis
        self.java = java
        self.goods_index = goods_index
        self.graph = graph
        self._meta: tuple[float, list[dict[str, Any]], list[dict[str, Any]]] | None = None

    async def meta(self) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
        if self._meta is None or time.time() - self._meta[0] > META_TTL_SECONDS:
            categories, campuses = await asyncio.gather(self.java.categories(), self.java.campuses())
            self._meta = (time.time(), categories, campuses)
        return self._meta[1], self._meta[2]


def build_llm(s: Settings) -> ChatDeepSeek:
    return ChatDeepSeek(model=s.deepseek_model, api_key=s.deepseek_api_key, api_base=s.deepseek_base_url,
                        extra_body={"thinking": {"type": s.deepseek_thinking}}, stream_usage=True,
                        max_retries=2, timeout=60)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    s = get_settings()
    redis = Redis.from_url(s.redis_url)
    java = JavaClient(s.backend_base_url)
    embedder = await asyncio.to_thread(SentenceTransformerEmbedder, s.embedding_model, s.embedding_query_instruction)
    goods_index = GoodsIndex(s, redis, embedder)
    sync_task = None
    if s.ai_mysql_user:
        sync_task = asyncio.create_task(goods_index.run_forever())
    else:
        log.warning("未配置 AI_MYSQL_USER，商品向量索引不会同步，检索只用关键词")
    async with AsyncRedisSaver.from_conn_string(
        s.redis_url, ttl={"default_ttl": s.ai_session_ttl_days * 24 * 60, "refresh_on_read": True}
    ) as checkpointer:
        await checkpointer.asetup()
        graph = build_graph(build_llm(s), s, checkpointer)
        app.state.ai = AppState(s, redis, java, goods_index, graph)
        yield
    if sync_task:
        sync_task.cancel()
    await java.aclose()
    await redis.aclose()


app = FastAPI(title="campus-trade-ai", lifespan=lifespan)


def get_app_state(request: Request) -> AppState:
    return request.app.state.ai


async def current_user(authorization: str | None = Header(default=None),
                       st: AppState = Depends(get_app_state)) -> tuple[str, dict[str, Any]]:
    """登录校验交给 Java（含 token 黑名单、封禁）。"""
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(401, "未登录")
    token = authorization.removeprefix("Bearer ").strip()
    try:
        return token, await st.java.get_me(token)
    except JavaAuthError:
        raise HTTPException(401, "登录已失效")


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    # 与 Java 统一响应格式保持一致
    return JSONResponse({"code": exc.status_code, "message": exc.detail, "data": None},
                        status_code=exc.status_code, headers=exc.headers)


def sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


async def stream_chat(st: AppState, ctx: RequestContext, conv_id: str, message: str) -> AsyncIterator[str]:
    config = {"configurable": {"thread_id": f"{ctx.user['id']}:{conv_id}", "ctx": ctx}, "recursion_limit": 40}
    usage = {"input_tokens": 0, "output_tokens": 0}
    tool_trace: list[str] = []
    started = time.monotonic()
    last_msg_id: str | None = None
    try:
        async for mode, chunk in st.graph.astream({"messages": [HumanMessage(message)]}, config,
                                                  stream_mode=["messages", "updates"]):
            if mode == "messages":
                msg, meta = chunk
                if meta.get("langgraph_node") == "agent" and isinstance(msg, AIMessageChunk) and msg.text:
                    # 同一轮里 agent 调工具前后的多段发言，之间空一行
                    if last_msg_id is not None and msg.id != last_msg_id:
                        yield sse("token", "\n\n")
                    last_msg_id = msg.id
                    yield sse("token", msg.text)
                continue
            for node, update in chunk.items():
                for m in (update or {}).get("messages", []):
                    if isinstance(m, AIMessage):
                        for k in usage:
                            usage[k] += (m.usage_metadata or {}).get(k, 0)
                        for tc in m.tool_calls:
                            tool_trace.append(tc["name"])
                            yield sse("tool", {"name": tc["name"], "status": "start"})
                    elif isinstance(m, ToolMessage):
                        yield sse("tool", {"name": m.name, "status": "end"})
                        card = goods_card(m)
                        if card:
                            yield sse("goods", [card])
        yield sse("done", {"conversationId": conv_id, "usage": usage})
    except Exception:
        log.exception("对话失败 user=%s conv=%s", ctx.user["id"], conv_id)
        yield sse("error", {"code": 500, "message": "助手暂时不可用，请稍后再试"})
    finally:
        # 只记录调用序列、耗时和用量，不记录对话内容
        log.info("chat user=%s conv=%s tools=%s ms=%d usage=%s", ctx.user["id"], conv_id, tool_trace,
                 (time.monotonic() - started) * 1000, usage)


def goods_card(m: ToolMessage) -> dict[str, Any] | None:
    """核实过且可购买的商品推送成卡片。"""
    if m.name != "get_goods_detail":
        return None
    try:
        d = json.loads(m.text)
    except ValueError:
        return None
    if not d.get("available"):
        return None
    return {k: d.get(k) for k in ("id", "title", "price", "conditionLevel", "campusName", "coverUrl")}


@app.post("/api/v1/ai/chat")
async def chat(body: ChatRequest, user: tuple[str, dict[str, Any]] = Depends(current_user),
               st: AppState = Depends(get_app_state)) -> StreamingResponse:
    token, me = user
    retry_after = await check_rate(st.redis, me["id"], st.settings.ai_rate_per_minute, st.settings.ai_rate_per_day)
    if retry_after is not None:
        raise HTTPException(429, "提问太频繁，请稍后再试", headers={"Retry-After": str(retry_after)})
    categories, campuses = await st.meta()
    ctx = RequestContext(token=token, user=me, java=st.java, goods_index=st.goods_index,
                         categories=categories, campuses=campuses)
    conv_id = body.conversationId or str(uuid.uuid4())
    return StreamingResponse(stream_chat(st, ctx, conv_id, body.message), media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.get("/api/v1/ai/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
