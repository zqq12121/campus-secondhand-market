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


MAX_IMAGE_BYTES = 5 * 1024 * 1024
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif"}


class JavaClient:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None) -> None:
        self._root = base_url.rstrip("/")
        self._http = httpx.AsyncClient(base_url=self._root + "/api/v1", timeout=10.0, transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, token: str | None = None, params: dict[str, Any] | None = None) -> Any:
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return await self._request("GET", path, token, params=clean)

    async def _post(self, path: str, token: str, body: dict[str, Any]) -> Any:
        return await self._request("POST", path, token, json={k: v for k, v in body.items() if v is not None})

    async def _request(self, method: str, path: str, token: str | None, **kwargs: Any) -> Any:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        resp = await self._http.request(method, path, headers=headers, **kwargs)
        if resp.status_code == 401:
            raise JavaAuthError(resp.status_code)
        if resp.status_code == 403:  # 账号封禁，或访问不属于自己的资源
            raise JavaApiError(403, "无权限")
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

    async def list_orders(self, token: str, role: str, status: list[int] | None = None,
                          size: int = 10) -> list[dict[str, Any]]:
        data = await self._get("/orders", token, {"role": role, "status": status, "size": size})
        return data.get("list", []) if data else []

    async def get_order(self, token: str, order_id: str) -> dict[str, Any]:
        return await self._get(f"/orders/{order_id}", token)

    async def announcements(self) -> list[dict[str, Any]]:
        data = await self._get("/announcements", params={"size": 50})
        return data.get("list", []) if data else []

    async def announcement(self, announcement_id: str) -> dict[str, Any]:
        return await self._get(f"/announcements/{announcement_id}")

    async def create_want(self, token: str, body: dict[str, Any]) -> dict[str, Any]:
        return await self._post("/wants", token, body)

    async def fetch_upload(self, relative_path: str) -> tuple[bytes, str]:
        """读取 Java 上传目录里的图片（走内网地址），返回 (内容, MIME)。"""
        if ".." in relative_path or relative_path.startswith("/"):
            raise ValueError("非法图片路径")
        resp = await self._http.get(f"{self._root}/uploads/{relative_path}")
        resp.raise_for_status()
        mime = resp.headers.get("content-type", "").split(";")[0].strip()
        if mime not in IMAGE_TYPES:
            raise ValueError(f"不支持的图片类型：{mime}")
        if len(resp.content) > MAX_IMAGE_BYTES:
            raise ValueError("图片超过 5MB")
        return resp.content, mime

    async def categories(self) -> list[dict[str, Any]]:
        return await self._get("/common/categories")

    async def campuses(self) -> list[dict[str, Any]]:
        return await self._get("/common/campuses")
