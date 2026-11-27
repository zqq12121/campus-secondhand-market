"""ai-service 入口：/chat 与 /resume（SSE 流式）、/memory（我的偏好）。"""
import asyncio
import json
import logging
import time
import uuid
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any, Literal

from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi import Path as PathParam
from fastapi.responses import JSONResponse, StreamingResponse
from langchain_core.callbacks import BaseCallbackHandler
from langchain_core.messages import AIMessage, AIMessageChunk, HumanMessage, ToolMessage
from langchain_deepseek import ChatDeepSeek
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from langgraph.graph.state import CompiledStateGraph
from langgraph.store.base import BaseStore
from langgraph.store.redis.aio import AsyncRedisStore
from langgraph.types import Command
from pydantic import BaseModel, Field
from redis.asyncio import Redis

from app.config import Settings, get_settings
from app.graph.builder import build_graph
from app.graph.context import RequestContext
from app.graph.images import human_content
from app.graph.memory import SLOTS, namespace
from app.guard import INPUT_REFUSAL, is_injection
from app.java_client import JavaApiError, JavaAuthError, JavaClient
from app.observability import TraceCollector, flush_langfuse, langfuse_handler
from app.rag.embedder import SentenceTransformerEmbedder
from app.rag.goods_index import GoodsIndex
from app.rag.reranker import CrossEncoderReranker
from app.rag.rules_index import RulesIndex
from app.ratelimit import check_rate
from app.redis_lock import RedisLock, run_as_leader

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
log = logging.getLogger("ai-service")

META_TTL_SECONDS = 600


CONV_ID_PATTERN = r"^[0-9a-f-]{36}$"
CANCELLED_BY_NEW_MESSAGE = "用户没有确认就发送了新消息，本次操作已取消。"


class ChatRequest(BaseModel):
    conversationId: str | None = Field(default=None, pattern=CONV_ID_PATTERN)
    message: str = Field(min_length=1, max_length=1000)
    # 先调 Java POST /files 上传，再把返回的地址传进来
    images: list[str] = Field(default_factory=list, max_length=3)


class ResumeRequest(BaseModel):
    interruptId: str = Field(min_length=1, max_length=64)
    decision: Literal["confirm", "cancel"]
    edits: dict[str, Any] | None = None


class AppState:
    """进程级依赖，lifespan 中创建。分类 / 校区列表缓存 10 分钟。"""

    def __init__(self, settings: Settings, redis: Redis, java: JavaClient, goods_index: GoodsIndex,
                 graph: CompiledStateGraph, store: BaseStore, rules_index: RulesIndex | None = None,
                 callbacks: list[BaseCallbackHandler] | None = None) -> None:
        self.settings = settings
        self.redis = redis
        self.java = java
        self.goods_index = goods_index
        self.graph = graph
        self.store = store
        self.rules_index = rules_index
        # 所有请求共用的额外回调（Langfuse）；每个请求自己的 TraceCollector 在 stream_chat 里创建
        self.callbacks = callbacks or []
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
    reranker = await asyncio.to_thread(CrossEncoderReranker, s.rerank_model) if s.rerank_model else None
    goods_index = GoodsIndex(s, redis, embedder, reranker)
    rules_index = RulesIndex(redis, embedder, java, reranker=reranker)
    langfuse = langfuse_handler(s)
    if langfuse:
        log.info("已启用 Langfuse 链路追踪：%s", s.langfuse_base_url)
    # 多实例 / 多 worker 时只有拿到锁的实例跑同步，其他实例定期重试抢锁
    ttl = s.ai_leader_lock_ttl_seconds
    tasks = [asyncio.create_task(
        run_as_leader(RedisLock(redis, s.rules_sync_lock_key, ttl), rules_index.run_forever))]
    if s.ai_mysql_user:
        tasks.append(asyncio.create_task(
            run_as_leader(RedisLock(redis, s.goods_sync_lock_key, ttl), goods_index.run_forever)))
    else:
        log.warning("未配置 AI_MYSQL_USER，商品向量索引不会同步，检索只用关键词")
    async with (
        AsyncRedisSaver.from_conn_string(
            s.redis_url, ttl={"default_ttl": s.ai_session_ttl_days * 24 * 60, "refresh_on_read": True}
        ) as checkpointer,
        AsyncRedisStore.from_conn_string(s.redis_url, store_prefix="ai:memory") as store,
    ):
        await checkpointer.asetup()
        await store.setup()
        graph = build_graph(build_llm(s), s, checkpointer, store)
        app.state.ai = AppState(s, redis, java, goods_index, graph, store, rules_index,
                                callbacks=[langfuse] if langfuse else [])
        yield
    for t in tasks:
        t.cancel()
    # 等它们释放完锁再关 Redis 连接
    await asyncio.gather(*tasks, return_exceptions=True)
    flush_langfuse(s)
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
    except JavaApiError as e:
        if e.code == 403:
            raise HTTPException(403, "账号已被限制")
        raise


@app.exception_handler(HTTPException)
async def http_error(_: Request, exc: HTTPException) -> JSONResponse:
    # 与 Java 统一响应格式保持一致
    return JSONResponse({"code": exc.status_code, "message": exc.detail, "data": None},
                        status_code=exc.status_code, headers=exc.headers)


def sse(event: str, data: Any) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def thread_config(ctx: RequestContext, conv_id: str) -> dict[str, Any]:
    # thread_id 含用户 id：别人拿到会话 id 也续不上、恢复不了
    return {"configurable": {"thread_id": f"{ctx.user['id']}:{conv_id}", "ctx": ctx}, "recursion_limit": 40}


async def stream_chat(st: AppState, ctx: RequestContext, conv_id: str, graph_input: Any) -> AsyncIterator[str]:
    trace = TraceCollector(st.settings.ai_price_input_per_m, st.settings.ai_price_output_per_m)
    config = {**thread_config(ctx, conv_id), "callbacks": [trace, *st.callbacks],
              # Langfuse 按这两个字段归组用户和会话
              "metadata": {"langfuse_user_id": str(ctx.user["id"]), "langfuse_session_id": conv_id}}
    started = time.monotonic()
    last_msg_id: str | None = None
    # 本轮 agent 每条发言的全文，输出防护改写某条时据此拼出整轮的新文本
    texts: dict[str, str] = {}
    revised: list[str] = []
    pending = False
    try:
        async for mode, chunk in st.graph.astream(graph_input, config, stream_mode=["messages", "updates"]):
            if mode == "messages":
                msg, meta = chunk
                if meta.get("langgraph_node") == "agent" and isinstance(msg, AIMessageChunk) and msg.text:
                    # 同一轮里 agent 调工具前后的多段发言，之间空一行
                    if last_msg_id is not None and msg.id != last_msg_id:
                        yield sse("token", "\n\n")
                    last_msg_id = msg.id
                    texts[msg.id] = texts.get(msg.id, "") + msg.text
                    yield sse("token", msg.text)
                continue
            for node, update in chunk.items():
                if node == "__interrupt__":  # 写工具停下等用户确认
                    pending = True
                    for it in update:
                        yield sse("confirm", {"interruptId": it.id, **it.value})
                    continue
                for m in (update or {}).get("messages", []):
                    if node == "output_guard" and isinstance(m, AIMessage):
                        # 回复已经流式发出去了，整轮文本替换成处理后的版本
                        revised.append(m.id)
                        # 正常情况下与流式片段同 id；对不上时它就是本轮最后一条发言
                        texts[m.id if m.id in texts or not texts else list(texts)[-1]] = m.text
                        yield sse("revise", "\n\n".join(texts.values()))
                    elif isinstance(m, AIMessage):
                        for tc in m.tool_calls:
                            yield sse("tool", {"name": tc["name"], "status": "start"})
                    elif isinstance(m, ToolMessage):
                        yield sse("tool", {"name": m.name, "status": "end"})
                        if card := goods_card(m):
                            yield sse("goods", [card])
                        if draft := listing_draft(m):
                            yield sse("listing", draft)
        yield sse("done", {"conversationId": conv_id, "usage": trace.usage(), "pending": pending})
    except Exception:
        log.exception("对话失败 user=%s conv=%s", ctx.user["id"], conv_id)
        yield sse("error", {"code": 500, "message": "助手暂时不可用，请稍后再试"})
    finally:
        # 每个请求一行 JSON：各次模型调用和工具调用的耗时、用量、成本，不记录对话内容
        log.info("chat_trace %s", json.dumps({
            "user": ctx.user["id"], "conv": conv_id, "ms": int((time.monotonic() - started) * 1000),
            "pending": pending, "revised": bool(revised), **trace.summary(),
        }, ensure_ascii=False))


def _tool_json(m: ToolMessage, name: str) -> dict[str, Any] | None:
    if m.name != name:
        return None
    try:
        return json.loads(m.text)
    except ValueError:
        return None


def goods_card(m: ToolMessage) -> dict[str, Any] | None:
    """核实过且可购买的商品推送成卡片。"""
    d = _tool_json(m, "get_goods_detail")
    if not d or not d.get("available"):
        return None
    return {k: d.get(k) for k in ("id", "title", "price", "conditionLevel", "campusName", "coverUrl")}


def listing_draft(m: ToolMessage) -> dict[str, Any] | None:
    """用户确认后的发布草稿，前端拿去预填发布页。"""
    d = _tool_json(m, "suggest_listing")
    return d["draft"] if d and d.get("status") == "confirmed" else None


async def cancel_pending(graph: CompiledStateGraph, config: dict[str, Any]) -> None:
    """会话里还有没确认的写操作时，用户直接发了新消息：给这些工具调用补上「已取消」结果，再开始新一轮。"""
    state = await graph.aget_state(config)
    if not state.interrupts:
        return
    last = state.values["messages"][-1]
    cancelled = json.dumps({"status": "cancelled", "reason": CANCELLED_BY_NEW_MESSAGE}, ensure_ascii=False)
    await graph.aupdate_state(config, {"messages": [
        ToolMessage(cancelled, tool_call_id=tc["id"], name=tc["name"]) for tc in last.tool_calls
    ]}, as_node="tools")


async def guarded_context(st: AppState, token: str, me: dict[str, Any]) -> RequestContext:
    retry_after = await check_rate(st.redis, me["id"], st.settings.ai_rate_per_minute, st.settings.ai_rate_per_day)
    if retry_after is not None:
        raise HTTPException(429, "提问太频繁，请稍后再试", headers={"Retry-After": str(retry_after)})
    categories, campuses = await st.meta()
    return RequestContext(token=token, user=me, java=st.java, goods_index=st.goods_index,
                          categories=categories, campuses=campuses, rules_index=st.rules_index)


async def refusal(st: AppState, ctx: RequestContext, conv_id: str) -> AsyncIterator[str]:
    state = await st.graph.aget_state(thread_config(ctx, conv_id))
    yield sse("token", INPUT_REFUSAL)
    yield sse("done", {"conversationId": conv_id, "usage": {"input_tokens": 0, "output_tokens": 0},
                       "pending": bool(state.interrupts)})


def sse_response(gen: AsyncIterator[str]) -> StreamingResponse:
    return StreamingResponse(gen, media_type="text/event-stream",
                             headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})


@app.post("/api/v1/ai/chat")
async def chat(body: ChatRequest, user: tuple[str, dict[str, Any]] = Depends(current_user),
               st: AppState = Depends(get_app_state)) -> StreamingResponse:
    token, me = user
    prefix = st.settings.upload_public_base_url.rstrip("/") + "/"
    if any(not u.startswith(prefix) for u in body.images):
        raise HTTPException(400, "图片必须先上传到平台")
    ctx = await guarded_context(st, token, me)
    conv_id = body.conversationId or str(uuid.uuid4())
    if is_injection(body.message):
        # 不进模型、不写入会话历史，也不取消待确认的卡片
        log.warning("输入防护拦截 user=%s conv=%s", me["id"], conv_id)
        return sse_response(refusal(st, ctx, conv_id))
    await cancel_pending(st.graph, thread_config(ctx, conv_id))
    message = HumanMessage(human_content(body.message, body.images))
    return sse_response(stream_chat(st, ctx, conv_id, {"messages": [message]}))


@app.post("/api/v1/ai/chat/{conversation_id}/resume")
async def resume(body: ResumeRequest, conversation_id: str = PathParam(pattern=CONV_ID_PATTERN),
                 user: tuple[str, dict[str, Any]] = Depends(current_user),
                 st: AppState = Depends(get_app_state)) -> StreamingResponse:
    """确认卡片的回调：用户确认 / 修改后确认 / 取消一个写操作。"""
    token, me = user
    ctx = await guarded_context(st, token, me)
    state = await st.graph.aget_state(thread_config(ctx, conversation_id))
    if body.interruptId not in {i.id for i in state.interrupts}:
        raise HTTPException(409, "没有待确认的操作，可能已处理或已取消")
    command = Command(resume={body.interruptId: {"decision": body.decision, "edits": body.edits}})
    return sse_response(stream_chat(st, ctx, conversation_id, command))


@app.get("/api/v1/ai/memory")
async def list_memory(user: tuple[str, dict[str, Any]] = Depends(current_user),
                      st: AppState = Depends(get_app_state)) -> dict[str, Any]:
    """「我的偏好」：查看长期记忆。"""
    items = await st.store.asearch(namespace(user[1]["id"]), limit=len(SLOTS))
    data = [{"key": i.key, "label": SLOTS[i.key], "value": i.value["value"],
             "updatedAt": i.updated_at.astimezone().strftime("%Y-%m-%d %H:%M:%S")}
            for i in items if i.key in SLOTS]
    return {"code": 0, "message": "ok", "data": data}


@app.delete("/api/v1/ai/memory/{key}")
async def delete_memory(key: str, user: tuple[str, dict[str, Any]] = Depends(current_user),
                        st: AppState = Depends(get_app_state)) -> dict[str, Any]:
    if key not in SLOTS:
        raise HTTPException(404, "偏好不存在")
    await st.store.adelete(namespace(user[1]["id"]), key)
    return {"code": 0, "message": "ok", "data": None}


@app.delete("/api/v1/ai/memory")
async def clear_memory(user: tuple[str, dict[str, Any]] = Depends(current_user),
                       st: AppState = Depends(get_app_state)) -> dict[str, Any]:
    for key in SLOTS:
        await st.store.adelete(namespace(user[1]["id"]), key)
    return {"code": 0, "message": "ok", "data": None}


@app.get("/api/v1/ai/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}
