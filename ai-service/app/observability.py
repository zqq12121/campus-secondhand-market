"""可观测性。

TraceCollector：每个请求一个，挂在 LangChain 回调上，统计这次请求里所有模型调用（含摘要压缩、偏好抽取）
的 token、耗时和成本，以及每次工具调用的耗时和报错。只记录元数据，不记录对话内容。

配置了 LANGFUSE_PUBLIC_KEY / LANGFUSE_SECRET_KEY 时，额外挂 Langfuse 回调上报完整链路。
注意 Langfuse 会收到对话内容和图片，默认不开启。
"""
import time
from typing import Any
from uuid import UUID

from langchain_core.callbacks import AsyncCallbackHandler, BaseCallbackHandler
from langchain_core.messages import BaseMessage
from langchain_core.outputs import LLMResult
from langgraph.errors import GraphInterrupt

from app.config import Settings


def _ms(started: float) -> int:
    return int((time.monotonic() - started) * 1000)


class TraceCollector(AsyncCallbackHandler):
    def __init__(self, price_input_per_m: float = 0.0, price_output_per_m: float = 0.0) -> None:
        self._prices = (price_input_per_m, price_output_per_m)
        self._started: dict[UUID, tuple[float, str]] = {}
        self.llm_calls: list[dict[str, Any]] = []
        self.tool_calls: list[dict[str, Any]] = []

    async def on_chat_model_start(self, serialized: dict[str, Any], messages: list[list[BaseMessage]], *,
                                  run_id: UUID, metadata: dict[str, Any] | None = None, **kwargs: Any) -> None:
        self._started[run_id] = (time.monotonic(), (metadata or {}).get("langgraph_node", ""))

    async def on_llm_end(self, response: LLMResult, *, run_id: UUID, **kwargs: Any) -> None:
        started, node = self._started.pop(run_id, (time.monotonic(), ""))
        usage: dict[str, Any] = {}
        for generations in response.generations:
            for g in generations:
                usage = getattr(getattr(g, "message", None), "usage_metadata", None) or usage
        self.llm_calls.append({"node": node, "ms": _ms(started), "input_tokens": usage.get("input_tokens", 0),
                               "output_tokens": usage.get("output_tokens", 0)})

    async def on_llm_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        started, node = self._started.pop(run_id, (time.monotonic(), ""))
        self.llm_calls.append({"node": node, "ms": _ms(started), "input_tokens": 0, "output_tokens": 0,
                               "error": type(error).__name__})

    async def on_tool_start(self, serialized: dict[str, Any], input_str: str, *, run_id: UUID,
                            **kwargs: Any) -> None:
        self._started[run_id] = (time.monotonic(), serialized.get("name") or kwargs.get("name", ""))

    async def on_tool_end(self, output: Any, *, run_id: UUID, **kwargs: Any) -> None:
        started, name = self._started.pop(run_id, (time.monotonic(), ""))
        self.tool_calls.append({"name": name, "ms": _ms(started)})

    async def on_tool_error(self, error: BaseException, *, run_id: UUID, **kwargs: Any) -> None:
        started, name = self._started.pop(run_id, (time.monotonic(), ""))
        # 写工具停下等用户确认也走这里，不算报错
        status = "interrupted" if isinstance(error, GraphInterrupt) else type(error).__name__
        self.tool_calls.append({"name": name, "ms": _ms(started), "error": status})

    def usage(self) -> dict[str, int]:
        return {"input_tokens": sum(c["input_tokens"] for c in self.llm_calls),
                "output_tokens": sum(c["output_tokens"] for c in self.llm_calls)}

    def cost(self) -> float:
        """按 AI_PRICE_*_PER_M（元 / 百万 token）估算；没配置价格时为 0。"""
        u = self.usage()
        return round((u["input_tokens"] * self._prices[0] + u["output_tokens"] * self._prices[1]) / 1_000_000, 6)

    def summary(self) -> dict[str, Any]:
        return {**self.usage(), "cost": self.cost(), "llm": self.llm_calls, "tools": self.tool_calls}


def langfuse_handler(s: Settings) -> BaseCallbackHandler | None:
    """配置了密钥才启用；所有请求共用一个 handler（它按 run_id 区分各自的链路）。"""
    if not (s.langfuse_public_key and s.langfuse_secret_key):
        return None
    from langfuse import Langfuse
    from langfuse.langchain import CallbackHandler

    Langfuse(public_key=s.langfuse_public_key, secret_key=s.langfuse_secret_key, base_url=s.langfuse_base_url)
    return CallbackHandler(public_key=s.langfuse_public_key)


def flush_langfuse(s: Settings) -> None:
    if s.langfuse_public_key and s.langfuse_secret_key:
        from langfuse import get_client

        get_client(public_key=s.langfuse_public_key).shutdown()
