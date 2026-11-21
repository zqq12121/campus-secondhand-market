"""调用 Java 后端 /api/v1 接口。所有用户相关请求都带用户自己的 token，权限由 Java 判定。"""
from typing import Any

import httpx


class JavaAuthError(Exception):
    """token 无效、过期或已登出（Java 返回 401）。"""


class JavaApiError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class JavaClient:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._http = httpx.AsyncClient(
            base_url=base_url.rstrip("/") + "/api/v1", timeout=10.0, transport=transport
        )

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, token: str | None = None, params: dict[str, Any] | None = None) -> Any:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        resp = await self._http.get(path, headers=headers, params=clean)
        if resp.status_code in (401, 403):
            raise JavaAuthError(resp.status_code)
        resp.raise_for_status()
        body = resp.json()
        if body.get("code") != 0:
            raise JavaApiError(body.get("code", -1), body.get("message", ""))
        return body.get("data")

    async def get_me(self, token: str) -> dict[str, Any]:
        return await self._get("/users/me", token)

    async def search_goods(self, token: str, **params: Any) -> list[dict[str, Any]]:
        data = await self._get("/goods", token, params)
        return data.get("list", []) if data else []

    async def goods_detail(self, token: str, goods_id: str) -> dict[str, Any]:
        return await self._get(f"/goods/{goods_id}", token)

    async def categories(self) -> list[dict[str, Any]]:
        return await self._get("/common/categories")

    async def campuses(self) -> list[dict[str, Any]]:
        return await self._get("/common/campuses")
