"""把只读工具暴露成 MCP Server，可以接入 Claude Desktop、Cursor 等 MCP 客户端。

启动：uv run python -m app.mcp_server（默认 stdio）；AI_MCP_TRANSPORT=streamable-http 时监听 AI_MCP_HOST:AI_MCP_PORT/mcp。
用的是官方 Python SDK（mcp 包）2.x，原来的 FastMCP 在 2.x 里改名为 MCPServer。

只暴露 5 个只读工具。写工具（draft_want_post、suggest_listing）不暴露：它们靠 LangGraph 的 interrupt()
停在确认卡片，等用户在前端确认后经 /resume 从 checkpoint 继续执行。MCP 调用是一次请求一次返回，没有这套
「暂停—确认—恢复」的会话状态，直接暴露就会绕过用户确认。

鉴权与 /chat 一致：每次调用都把用户 token 交给 Java 的 /users/me 校验（含黑名单、封禁），之后的 Java 请求都带这个
token，权限仍由 Java 判定。HTTP 模式从请求头 Authorization 取 token，stdio 模式从环境变量 AI_MCP_USER_TOKEN 读。
工具结果和 agent 用的是同一份实现，同样经过 redact_untrusted 过滤间接注入。
"""
from collections.abc import AsyncIterator, Callable
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from typing import Annotated, Any

from langchain_core.tools import BaseTool
from mcp.server.mcpserver import Context, MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp_types import ToolAnnotations
from pydantic import Field

from app.config import get_settings
from app.deps import Deps, build_deps
from app.graph import tools as lc
from app.graph.context import RequestContext
from app.java_client import JavaApiError, JavaAuthError

READ_ONLY = ToolAnnotations(read_only_hint=True, destructive_hint=False, open_world_hint=False)


def _arg(tool: BaseTool, name: str) -> str:
    """参数说明取自 LangChain 工具的 docstring，两边只维护一份。"""
    return tool.tool_call_schema.model_json_schema()["properties"][name].get("description", "")


@asynccontextmanager
async def default_lifespan(server: MCPServer) -> AsyncIterator[Deps]:
    # 与 FastAPI 的 lifespan 共用 build_deps；MCP 进程只读索引，不跑后台同步（同步由 ai-service 主实例负责）
    deps = await build_deps(get_settings())
    try:
        yield deps
    finally:
        await deps.aclose()


async def request_ctx(ctx: Context) -> RequestContext:
    deps: Deps = ctx.request_context.lifespan_context
    headers = ctx.headers
    if headers is not None:  # streamable HTTP
        auth = headers.get("authorization", "")
        token = auth.removeprefix("Bearer ").strip() if auth.startswith("Bearer ") else ""
    else:  # stdio：本机单用户
        token = deps.settings.ai_mcp_user_token
    if not token:
        raise ToolError("未提供用户 token：HTTP 模式请带 Authorization: Bearer <token>，stdio 模式请设置 AI_MCP_USER_TOKEN")
    try:
        me = await deps.java.get_me(token)
    except JavaAuthError:
        raise ToolError("登录已失效")
    except JavaApiError as e:
        raise ToolError("账号已被限制" if e.code == 403 else e.message)
    # 只读工具用不到分类和校区列表
    return RequestContext(token=token, user=me, java=deps.java, goods_index=deps.goods_index,
                          categories=[], campuses=[], rules_index=deps.rules_index)


def create_server(lifespan: Callable[[MCPServer], AbstractAsyncContextManager[Any]] = default_lifespan) -> MCPServer:
    server = MCPServer("campus-trade", instructions="校园二手平台助手的只读工具：找在售商品、查商品详情、查平台规则、查自己的订单。",
                       lifespan=lifespan)

    @server.tool(description=lc.search_goods.description, annotations=READ_ONLY)
    async def search_goods(
        query: Annotated[str, Field(description=_arg(lc.search_goods, "query"))],
        ctx: Context,
        campus_id: Annotated[str | None, Field(description=_arg(lc.search_goods, "campus_id"))] = None,
        category_id: Annotated[str | None, Field(description=_arg(lc.search_goods, "category_id"))] = None,
        min_price: Annotated[float | None, Field(description=_arg(lc.search_goods, "min_price"))] = None,
        max_price: Annotated[float | None, Field(description=_arg(lc.search_goods, "max_price"))] = None,
    ) -> str:
        return await lc.search_goods_impl(await request_ctx(ctx), query, campus_id, category_id, min_price, max_price)

    @server.tool(description=lc.get_goods_detail.description, annotations=READ_ONLY)
    async def get_goods_detail(goods_id: Annotated[str, Field(description=_arg(lc.get_goods_detail, "goods_id"))],
                               ctx: Context) -> str:
        return await lc.get_goods_detail_impl(await request_ctx(ctx), goods_id)

    @server.tool(description=lc.search_rules.description, annotations=READ_ONLY)
    async def search_rules(question: Annotated[str, Field(description=_arg(lc.search_rules, "question"))],
                           ctx: Context) -> str:
        return await lc.search_rules_impl(await request_ctx(ctx), question)

    @server.tool(description=lc.list_my_orders.description, annotations=READ_ONLY)
    async def list_my_orders(
        ctx: Context,
        role: Annotated[str, Field(description=_arg(lc.list_my_orders, "role"))] = "BUYER",
        status: Annotated[list[int] | None, Field(description=_arg(lc.list_my_orders, "status"))] = None,
    ) -> str:
        return await lc.list_my_orders_impl(await request_ctx(ctx), role, status)

    @server.tool(description=lc.get_order.description, annotations=READ_ONLY)
    async def get_order(order_id: Annotated[str, Field(description=_arg(lc.get_order, "order_id"))],
                        ctx: Context) -> str:
        return await lc.get_order_impl(await request_ctx(ctx), order_id)

    return server


def main() -> None:
    s = get_settings()
    server = create_server()
    if s.ai_mcp_transport == "streamable-http":
        server.run("streamable-http", host=s.ai_mcp_host, port=s.ai_mcp_port)
    else:
        server.run("stdio")


if __name__ == "__main__":
    main()
