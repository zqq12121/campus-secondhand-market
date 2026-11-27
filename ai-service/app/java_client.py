"""调用 Java 后端 /api/v1 接口。所有用户相关请求都带用户自己的 token，权限由 Java 判定。

容错：连接级重试（只重试建连失败，请求还没发出去，POST 也安全）+ 熔断器。
"""
import time
from collections.abc import Callable
from typing import Any

import httpx


class JavaAuthError(Exception):
    """token 无效、过期或已登出（Java 返回 401）。"""


class JavaApiError(Exception):
    def __init__(self, code: int, message: str) -> None:
        super().__init__(f"{code}: {message}")
        self.code = code
        self.message = message


class CircuitBreaker:
    """连续失败 threshold 次后熔断 cooldown 秒，期间直接拒绝，不再请求下游。threshold 为 0 时不熔断。

    冷却结束后进入半开：只放行一个试探请求，成功就关闭熔断，失败就再熔断一个周期。
    不设半开的话，冷却一到就会把全部流量放回去，下游刚恢复又被打垮。
    试探请求超过 cooldown 还没有结果（比如被取消），就再放一个。
    """

    def __init__(self, threshold: int, cooldown_seconds: float,
                 clock: Callable[[], float] = time.monotonic) -> None:
        self._threshold = threshold
        self._cooldown = cooldown_seconds
        self._clock = clock
        self.state = "closed"
        self._failures = 0
        self._since = 0.0  # open：熔断开始时间；half_open：试探请求发出时间

    def allow(self) -> bool:
        if self._threshold <= 0 or self.state == "closed":
            return True
        if self._clock() - self._since < self._cooldown:
            return False
        self.state = "half_open"
        self._since = self._clock()
        return True

    def record_success(self) -> None:
        self.state = "closed"
        self._failures = 0

    def record_failure(self) -> None:
        self._failures += 1
        if self._threshold > 0 and (self.state == "half_open" or self._failures >= self._threshold):
            self.state = "open"
            self._since = self._clock()


MAX_IMAGE_BYTES = 5 * 1024 * 1024
IMAGE_TYPES = {"image/jpeg", "image/png", "image/webp", "image/gif"}


class JavaClient:
    def __init__(self, base_url: str, transport: httpx.AsyncBaseTransport | None = None,
                 breaker: CircuitBreaker | None = None) -> None:
        self._root = base_url.rstrip("/")
        # retries 只对建连失败（ConnectError / ConnectTimeout）生效
        transport = transport or httpx.AsyncHTTPTransport(retries=2)
        self._http = httpx.AsyncClient(base_url=self._root + "/api/v1", timeout=10.0, transport=transport)
        self._breaker = breaker or CircuitBreaker(0, 0)

    async def aclose(self) -> None:
        await self._http.aclose()

    async def _get(self, path: str, token: str | None = None, params: dict[str, Any] | None = None) -> Any:
        clean = {k: v for k, v in (params or {}).items() if v is not None}
        return await self._request("GET", path, token, params=clean)

    async def _post(self, path: str, token: str, body: dict[str, Any]) -> Any:
        return await self._request("POST", path, token, json={k: v for k, v in body.items() if v is not None})

    async def _request(self, method: str, path: str, token: str | None, **kwargs: Any) -> Any:
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        if not self._breaker.allow():
            raise JavaApiError(503, "服务暂不可用")
        try:
            resp = await self._http.request(method, path, headers=headers, **kwargs)
        except httpx.TransportError:
            self._breaker.record_failure()
            raise
        # 只有 5xx 和网络错误算下游故障；401/403/其他 4xx/业务错误码说明 Java 是正常在响应的
        if resp.status_code >= 500:
            self._breaker.record_failure()
        else:
            self._breaker.record_success()
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
