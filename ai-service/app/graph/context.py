"""单次请求的运行上下文，经 config["configurable"]["ctx"] 传给节点和工具。

刻意做成对象而不是字符串：LangGraph 会把 configurable 里的 str/int 值写进 checkpoint
元数据，用户 token 放成字符串就会被持久化到 Redis。
"""
from dataclasses import dataclass, field
from typing import Any

from langchain_core.runnables import RunnableConfig

from app.java_client import JavaClient
from app.rag.goods_index import GoodsIndex
from app.rag.rules_index import RulesIndex


@dataclass(frozen=True)
class RequestContext:
    token: str
    user: dict[str, Any]
    java: JavaClient
    goods_index: GoodsIndex
    categories: list[dict[str, Any]]
    campuses: list[dict[str, Any]]
    rules_index: RulesIndex | None = None
    # 本次请求内已转成 data URL 的图片，避免工具循环里重复读取
    image_cache: dict[str, str] = field(default_factory=dict)


def get_ctx(config: RunnableConfig) -> RequestContext:
    return config["configurable"]["ctx"]
