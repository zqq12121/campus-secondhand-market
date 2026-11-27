"""评测用：直接跑真实的图（DeepSeek、Java、Redis 里的索引），不经过 HTTP。

会话 checkpoint 和长期记忆用内存版，不读写线上用户的数据；每个问题开一个新会话。
前置：Java 后端在运行、Redis 里已有规则和商品索引（ai-service 启动过）；AI_EVAL_TOKEN 是任一用户的 token。
"""
import uuid
from typing import Any

from langchain_core.messages import BaseMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.store.memory import InMemoryStore

from app.config import Settings, get_settings
from app.deps import Deps, build_deps
from app.graph.builder import build_graph
from app.graph.context import RequestContext
from app.main import build_fallback_llm, build_llm


class Agent:
    def __init__(self, deps: Deps, ctx: RequestContext) -> None:
        self.deps = deps
        self.ctx = ctx
        s = deps.settings
        self.graph = build_graph(build_llm(s), s, InMemorySaver(), InMemoryStore(), build_fallback_llm(s))

    @classmethod
    async def open(cls, token: str, settings: Settings | None = None) -> "Agent":
        deps = await build_deps(settings or get_settings())
        me = await deps.java.get_me(token)
        categories, campuses = await deps.java.categories(), await deps.java.campuses()
        ctx = RequestContext(token=token, user=me, java=deps.java, goods_index=deps.goods_index,
                             categories=categories, campuses=campuses, rules_index=deps.rules_index)
        return cls(deps, ctx)

    async def ask(self, question: str) -> list[BaseMessage]:
        """在新会话里问一句，返回最终 state 的全部消息。写工具会停在确认卡片，不会真的发布。"""
        config: dict[str, Any] = {"configurable": {"thread_id": f"eval:{uuid.uuid4()}", "ctx": self.ctx},
                                  "recursion_limit": 40}
        state = await self.graph.ainvoke({"messages": [HumanMessage(question)]}, config)
        return state["messages"]

    async def aclose(self) -> None:
        await self.deps.aclose()
