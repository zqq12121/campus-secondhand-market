# /// script
# requires-python = ">=3.11"
# dependencies = ["httpx>=0.27", "pyjwt>=2.8"]
# ///
"""
订单与资金的并发压测：只对本机开发环境（docker compose --profile app，PAY_MODE=mock）运行。

用法（仓库根目录）：
    uv run scripts/stress/order_concurrency.py            # 跑全部场景
    uv run scripts/stress/order_concurrency.py --cleanup  # 只清理压测数据

做法：
- 直接往库里插压测用户（id 7000000 起）和商品，用 .env 里的 JWT_USER_SECRET 签发 token，绕过注册和登录限流。
- 每个场景并发打真实接口（经 Nginx :8088），结束后查库核对不变量，而不是只看接口返回。
- 每次运行前先清理上一次的压测数据；脚本不会碰 id 段以外的数据。
"""
from __future__ import annotations

import argparse
import asyncio
import os
import subprocess
import time
import uuid
from collections import Counter
from dataclasses import dataclass, field
from pathlib import Path

import httpx
import jwt

BASE_URL = os.environ.get("STRESS_BASE_URL", "http://localhost:8088")
MYSQL_CONTAINER = os.environ.get("STRESS_MYSQL_CONTAINER", "campus-trade-mysql")
REDIS_CONTAINER = os.environ.get("STRESS_REDIS_CONTAINER", "campus-trade-redis")

SELLER_ID = 7_000_000
BUYER_BASE = 7_000_001
BUYER_COUNT = 400
GOODS_BASE = 7_000_000

FACE, GUARANTEE, BOTH = 1, 2, 3
WAIT_SELLER_CONFIRM, WAIT_PAY, WAIT_DELIVER, WAIT_RECEIVE = 10, 30, 40, 50
FINISHED, CANCELED = 70, 80
PAY_UNPAID, PAY_PAID, PAY_CLOSED, PAY_REFUNDED = 0, 1, 2, 3


# ---------------------------------------------------------------- 基础设施

def load_env(path: Path) -> dict[str, str]:
    env: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def sql(statement: str) -> list[list[str]]:
    """在 MySQL 容器里执行 SQL（root 密码只在容器内展开，不经过本进程）。"""
    out = subprocess.run(
        ["docker", "exec", "-i", MYSQL_CONTAINER, "sh", "-c",
         'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" --default-character-set=utf8mb4 -N -B campus_trade'],
        input=statement, capture_output=True, text=True, check=True,
    ).stdout
    return [row.split("\t") for row in out.splitlines() if row]


def scalar(statement: str) -> int:
    rows = sql(statement)
    return int(rows[0][0]) if rows and rows[0][0] != "NULL" else 0


def redis(*args: str) -> str:
    return subprocess.run(["docker", "exec", REDIS_CONTAINER, "redis-cli", *args],
                          capture_output=True, text=True, check=True).stdout.strip()


def token(secret: str, user_id: int) -> str:
    now = time.time()
    return jwt.encode({"sub": str(user_id), "jti": str(uuid.uuid4()), "iat": int(now),
                       "iatMs": int(now * 1000), "exp": int(now) + 3600}, secret, algorithm="HS256")


@dataclass
class Resp:
    http: int
    code: int | None
    message: str
    data: object
    ms: float


class Api:
    def __init__(self, secret: str, concurrency: int) -> None:
        self.secret = secret
        self.client = httpx.AsyncClient(base_url=BASE_URL, timeout=30,
                                        limits=httpx.Limits(max_connections=concurrency,
                                                            max_keepalive_connections=concurrency))
        self.tokens: dict[int, str] = {}

    def auth(self, user_id: int) -> dict[str, str]:
        if user_id not in self.tokens:
            self.tokens[user_id] = token(self.secret, user_id)
        return {"Authorization": f"Bearer {self.tokens[user_id]}"}

    async def call(self, method: str, path: str, user_id: int | None = None, **kw: object) -> Resp:
        headers = self.auth(user_id) if user_id else {}
        t0 = time.perf_counter()
        try:
            r = await self.client.request(method, path, headers=headers, **kw)  # type: ignore[arg-type]
        except httpx.HTTPError as e:
            return Resp(0, None, f"{type(e).__name__}: {e}", None, (time.perf_counter() - t0) * 1000)
        ms = (time.perf_counter() - t0) * 1000
        try:
            body = r.json()
            return Resp(r.status_code, body.get("code"), body.get("message", ""), body.get("data"), ms)
        except ValueError:
            return Resp(r.status_code, None, r.text[:120], None, ms)

    async def close(self) -> None:
        await self.client.aclose()


# ---------------------------------------------------------------- 数据准备

def cleanup() -> None:
    lo, hi = SELLER_ID, BUYER_BASE + BUYER_COUNT
    orders = f"SELECT id FROM trade_order WHERE seller_id BETWEEN {lo} AND {hi}"
    convs = f"SELECT id FROM conversation WHERE user_small_id BETWEEN {lo} AND {hi} OR user_big_id BETWEEN {lo} AND {hi}"
    sql(f"""
        DELETE FROM order_log WHERE order_id IN ({orders});
        DELETE FROM payment WHERE order_id IN ({orders});
        DELETE FROM balance_log WHERE user_id BETWEEN {lo} AND {hi};
        DELETE FROM trade_order WHERE seller_id BETWEEN {lo} AND {hi};
        DELETE FROM message WHERE conversation_id IN (SELECT id FROM ({convs}) c);
        DELETE FROM conversation WHERE user_small_id BETWEEN {lo} AND {hi} OR user_big_id BETWEEN {lo} AND {hi};
        DELETE FROM notification WHERE user_id BETWEEN {lo} AND {hi};
        DELETE FROM credit_log WHERE user_id BETWEEN {lo} AND {hi};
        DELETE FROM goods_image WHERE goods_id IN (SELECT id FROM goods WHERE seller_id = {SELLER_ID});
        DELETE FROM goods WHERE seller_id = {SELLER_ID};
        DELETE FROM `user` WHERE id BETWEEN {lo} AND {hi};
    """)
    for gid in range(GOODS_BASE, GOODS_BASE + 100):
        redis("DEL", f"goods:stock:{gid}")
    for pattern in ("order:cancel:700*", "order:req:700*"):
        keys = redis("--scan", "--pattern", pattern).split()
        if keys:
            redis("DEL", *keys)


def seed_users() -> None:
    school, campus = sql("SELECT id, (SELECT id FROM campus WHERE school_id = s.id ORDER BY id LIMIT 1) "
                         "FROM school s ORDER BY id LIMIT 1")[0]
    rows = [f"({SELLER_ID},{school},{campus},'stress.seller@stress.test','!stress-no-login','压测卖家',90)"]
    rows += [f"({uid},{school},{campus},'stress.b{uid}@stress.test','!stress-no-login','压测买家{uid}',80)"
             for uid in range(BUYER_BASE, BUYER_BASE + BUYER_COUNT)]
    sql("INSERT INTO `user` (id, school_id, campus_id, email, password_hash, nickname, credit_score) VALUES "
        + ",".join(rows) + ";")


_next_goods = GOODS_BASE


def new_goods(stock: int, trade_mode: int, price: str = "10.00") -> int:
    global _next_goods
    gid, _next_goods = _next_goods, _next_goods + 1
    sql(f"""INSERT INTO goods (id, seller_id, school_id, campus_id, category_id, title, description, cover_url, price,
              condition_level, trade_mode, location, stock, status, published_at)
            SELECT {gid}, {SELLER_ID}, school_id, campus_id,
                   (SELECT id FROM category WHERE parent_id IS NOT NULL AND parent_id <> 0 ORDER BY id LIMIT 1),
                   '压测商品 {gid}', '并发压测数据', '', {price}, 3, {trade_mode}, '压测', {stock}, 2, NOW()
            FROM `user` WHERE id = {SELLER_ID};""")
    return gid


# ---------------------------------------------------------------- 场景与结果

@dataclass
class Result:
    name: str
    requests: int = 0
    seconds: float = 0
    outcomes: Counter[str] = field(default_factory=Counter)
    checks: list[tuple[str, bool, str]] = field(default_factory=list)
    latencies: list[float] = field(default_factory=list)

    def add(self, rs: list[Resp]) -> None:
        self.requests += len(rs)
        for r in rs:
            self.latencies.append(r.ms)
            if r.http >= 500 or r.http == 0:
                self.outcomes[f"HTTP {r.http} {r.message[:60]}"] += 1
            elif r.code == 0 or r.http in (200, 302) and r.code is None:
                self.outcomes["成功"] += 1
            else:
                self.outcomes[f"code={r.code} {r.message[:40]}"] += 1

    def check(self, label: str, ok: bool, detail: str) -> None:
        self.checks.append((label, ok, detail))

    @property
    def errors_5xx(self) -> int:
        return sum(n for k, n in self.outcomes.items() if k.startswith("HTTP "))

    def p(self, q: float) -> float:
        s = sorted(self.latencies)
        return s[min(len(s) - 1, int(len(s) * q))] if s else 0


async def burst(coros: list) -> list[Resp]:  # type: ignore[type-arg]
    return list(await asyncio.gather(*coros))


def order_req(goods_id: int, mode: int, request_id: str | None = None) -> dict[str, object]:
    return {"goodsId": str(goods_id), "quantity": 1, "tradeMode": mode, "requestId": request_id or uuid.uuid4().hex}


async def s_flash_single(api: Api, buyers: int) -> Result:
    res = Result(f"S1 抢购单件：{buyers} 人抢库存 1")
    gid = new_goods(1, FACE)
    t0 = time.perf_counter()
    res.add(await burst([api.call("POST", "/api/v1/orders", BUYER_BASE + i, json=order_req(gid, FACE))
                         for i in range(buyers)]))
    res.seconds = time.perf_counter() - t0
    n = scalar(f"SELECT COUNT(*) FROM trade_order WHERE goods_id = {gid}")
    stock, status = sql(f"SELECT stock, status FROM goods WHERE id = {gid}")[0]
    res.check("只生成 1 个订单", n == 1, f"订单数 {n}")
    res.check("库存为 0 且商品进入交易中", stock == "0" and status == "4", f"stock={stock} status={status}")
    return res


async def s_flash_multi(api: Api, buyers: int, stock: int) -> Result:
    res = Result(f"S2 多库存抢购：{buyers} 人抢库存 {stock}")
    gid = new_goods(stock, FACE)
    t0 = time.perf_counter()
    res.add(await burst([api.call("POST", "/api/v1/orders", BUYER_BASE + i, json=order_req(gid, FACE))
                         for i in range(buyers)]))
    res.seconds = time.perf_counter() - t0
    n = scalar(f"SELECT COUNT(*) FROM trade_order WHERE goods_id = {gid}")
    left = scalar(f"SELECT stock FROM goods WHERE id = {gid}")
    cache = redis("GET", f"goods:stock:{gid}")
    res.check("不超卖：订单数 ≤ 库存", n <= stock, f"订单数 {n}")
    res.check("库存守恒：订单数 + 剩余库存 = 初始库存", n + left == stock, f"{n} + {left} = {n + left}")
    res.check("不少卖：库存全部卖出", n == stock, f"卖出 {n}/{stock}，Redis 预扣缓存剩 {cache or '无'}")
    return res


async def s_idempotent(api: Api, repeats: int) -> Result:
    res = Result(f"S3 下单幂等：同一 requestId 并发提交 {repeats} 次")
    gid = new_goods(5, FACE)
    body = order_req(gid, FACE, f"stress-same-{uuid.uuid4().hex[:8]}")
    t0 = time.perf_counter()
    rs = await burst([api.call("POST", "/api/v1/orders", BUYER_BASE, json=body) for _ in range(repeats)])
    res.seconds = time.perf_counter() - t0
    res.add(rs)
    n = scalar(f"SELECT COUNT(*) FROM trade_order WHERE goods_id = {gid}")
    ids = {str(r.data.get("id")) for r in rs if r.code == 0 and isinstance(r.data, dict)}  # type: ignore[union-attr]
    res.check("只生成 1 个订单", n == 1, f"订单数 {n}")
    res.check("成功返回的都是同一个订单", len(ids) <= 1, f"返回的订单 id：{ids or '无'}")
    res.check("没有 5xx", res.errors_5xx == 0, f"5xx 次数 {res.errors_5xx}")
    return res


async def guarantee_order(api: Api, buyer: int, gid: int) -> tuple[int, str]:
    r = await api.call("POST", "/api/v1/orders", buyer, json=order_req(gid, GUARANTEE))
    assert r.code == 0, f"建担保订单失败：{r.message}"
    oid = int(r.data["id"])  # type: ignore[index]
    p = await api.call("POST", f"/api/v1/orders/{oid}/pay", buyer)
    assert p.code == 0, f"发起支付失败：{p.message}"
    return oid, str(p.data["outTradeNo"])  # type: ignore[index]


async def s_pay_start(api: Api, repeats: int) -> Result:
    res = Result(f"S4 并发发起支付：同一订单点 {repeats} 次「去支付」")
    gid = new_goods(1, GUARANTEE)
    r = await api.call("POST", "/api/v1/orders", BUYER_BASE + 1, json=order_req(gid, GUARANTEE))
    assert r.code == 0, r.message
    oid = int(r.data["id"])  # type: ignore[index]
    t0 = time.perf_counter()
    res.add(await burst([api.call("POST", f"/api/v1/orders/{oid}/pay", BUYER_BASE + 1) for _ in range(repeats)]))
    res.seconds = time.perf_counter() - t0
    n = scalar(f"SELECT COUNT(*) FROM payment WHERE order_id = {oid}")
    res.check("只生成 1 张支付单", n == 1, f"支付单 {n} 张")
    res.check("没有 5xx", res.errors_5xx == 0, f"5xx 次数 {res.errors_5xx}")
    return res


async def s_pay_notify(api: Api, repeats: int) -> Result:
    res = Result(f"S5 重复支付回调：同一支付单并发回调 {repeats} 次")
    gid = new_goods(1, GUARANTEE)
    oid, no = await guarantee_order(api, BUYER_BASE + 2, gid)
    t0 = time.perf_counter()
    res.add(await burst([api.call("POST", "/api/pay/mock/confirm", data={"outTradeNo": no}) for _ in range(repeats)]))
    res.seconds = time.perf_counter() - t0
    status = scalar(f"SELECT status FROM trade_order WHERE id = {oid}")
    paid = scalar(f"SELECT COUNT(*) FROM payment WHERE order_id = {oid} AND status = {PAY_PAID}")
    logs = scalar(f"SELECT COUNT(*) FROM order_log WHERE order_id = {oid} AND action = 'PAY'")
    res.check("订单变为待交货", status == WAIT_DELIVER, f"status={status}")
    res.check("支付单只入账一次", paid == 1, f"已支付单 {paid}")
    res.check("只记一条支付流转日志", logs == 1, f"PAY 日志 {logs} 条")
    res.check("没有 5xx", res.errors_5xx == 0, f"5xx 次数 {res.errors_5xx}")
    return res


async def s_pay_vs_cancel(api: Api, orders: int) -> Result:
    res = Result(f"S6 支付与取消赛跑：{orders} 个订单同时「付款」和「买家取消」")
    prepared: list[tuple[int, int, str]] = []
    for i in range(orders):
        buyer = BUYER_BASE + 10 + i
        oid, no = await guarantee_order(api, buyer, new_goods(1, GUARANTEE))
        prepared.append((buyer, oid, no))
    t0 = time.perf_counter()
    coros = []
    for buyer, oid, no in prepared:
        coros.append(api.call("POST", f"/api/v1/orders/{oid}/cancel", buyer, json={"reason": "压测取消"}))
        coros.append(api.call("POST", "/api/pay/mock/confirm", data={"outTradeNo": no}))
    res.add(await burst(coros))
    res.seconds = time.perf_counter() - t0

    states: Counter[str] = Counter()
    stuck: list[str] = []
    for _, oid, no in prepared:
        o_status = scalar(f"SELECT status FROM trade_order WHERE id = {oid}")
        p_status = scalar(f"SELECT status FROM payment WHERE out_trade_no = '{no}'")
        charged = bool(redis("GET", f"mockpay:paid:{no}"))  # 模拟渠道侧：钱是否真的扣了
        refunded = p_status == PAY_REFUNDED
        states[f"订单 {o_status} / 支付单 {p_status} / 渠道已扣款 {charged}"] += 1
        if o_status == CANCELED and charged and not refunded:
            stuck.append(f"订单 {oid}")
        if o_status == WAIT_DELIVER and p_status != PAY_PAID:
            stuck.append(f"订单 {oid}（已发货流程却未入账）")
    for k, v in states.items():
        res.outcomes[f"终态 {k}"] += v
    res.check("没有「订单已取消、钱扣了却没退」", not stuck, "、".join(stuck[:5]) or "无")
    res.check("没有 5xx", res.errors_5xx == 0, f"5xx 次数 {res.errors_5xx}")
    return res


async def s_confirm_receipt(api: Api, repeats: int) -> Result:
    res = Result(f"S7 重复确认收货：同一订单并发确认 {repeats} 次")
    buyer = BUYER_BASE + 3
    oid, no = await guarantee_order(api, buyer, new_goods(1, GUARANTEE, "88.00"))
    await api.call("POST", "/api/pay/mock/confirm", data={"outTradeNo": no})
    d = await api.call("POST", f"/api/v1/orders/{oid}/deliver", SELLER_ID)
    assert d.code == 0, f"卖家交货失败：{d.message}"
    before = sql(f"SELECT balance FROM `user` WHERE id = {SELLER_ID}")[0][0]
    t0 = time.perf_counter()
    res.add(await burst([api.call("POST", f"/api/v1/orders/{oid}/confirm-receipt", buyer) for _ in range(repeats)]))
    res.seconds = time.perf_counter() - t0
    after = sql(f"SELECT balance FROM `user` WHERE id = {SELLER_ID}")[0][0]
    releases = scalar(f"SELECT COUNT(*) FROM balance_log WHERE order_id = {oid}")
    status = scalar(f"SELECT status FROM trade_order WHERE id = {oid}")
    res.check("订单完成", status == FINISHED, f"status={status}")
    res.check("只放款一次", releases == 1, f"放款流水 {releases} 条")
    res.check("卖家余额只增加一次订单金额", round(float(after) - float(before), 2) == 88.0, f"{before} → {after}")
    return res


# ---------------------------------------------------------------- 主流程

def report(results: list[Result]) -> bool:
    all_ok = True
    for r in results:
        print(f"\n## {r.name}")
        if r.requests:
            print(f"请求 {r.requests} 个，用时 {r.seconds:.2f}s，延迟 p50 {r.p(0.5):.0f}ms / p95 {r.p(0.95):.0f}ms / max {r.p(1):.0f}ms")
        for k, v in r.outcomes.most_common():
            print(f"  {v:>4} × {k}")
        for label, ok, detail in r.checks:
            all_ok &= ok
            print(f"  [{'通过' if ok else '失败'}] {label}：{detail}")
    print("\n结论：" + ("全部不变量通过" if all_ok else "有不变量未通过，见上面的「失败」项"))
    return all_ok


async def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cleanup", action="store_true", help="只清理压测数据后退出")
    ap.add_argument("--buyers", type=int, default=300, help="抢购场景的并发买家数（≤ 400）")
    ap.add_argument("--stock", type=int, default=20, help="多库存抢购的库存")
    ap.add_argument("--repeats", type=int, default=50, help="幂等类场景的重复次数")
    ap.add_argument("--race-orders", type=int, default=30, help="支付与取消赛跑的订单数")
    args = ap.parse_args()

    cleanup()
    if args.cleanup:
        print("压测数据已清理")
        return 0
    env = load_env(Path(__file__).resolve().parents[2] / ".env")
    if env.get("PAY_MODE", "mock") != "mock":
        print("需要 PAY_MODE=mock（模拟收银台）才能压测支付场景")
        return 2
    seed_users()
    api = Api(env["JWT_USER_SECRET"], concurrency=max(args.buyers, args.repeats, args.race_orders * 2))
    try:
        results = [
            await s_flash_single(api, args.buyers),
            await s_flash_multi(api, args.buyers, args.stock),
            await s_idempotent(api, args.repeats),
            await s_pay_start(api, args.repeats),
            await s_pay_notify(api, args.repeats),
            await s_pay_vs_cancel(api, args.race_orders),
            await s_confirm_receipt(api, args.repeats),
        ]
    finally:
        await api.close()
    return 0 if report(results) else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
