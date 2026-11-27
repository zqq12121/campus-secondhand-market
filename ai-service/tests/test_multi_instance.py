"""多实例部署：Redis 分布式锁、选主执行、规则库蓝绿重建。"""
import asyncio
import pathlib

import pytest
from fakeredis import FakeAsyncRedis

from app.rag.rules_index import POINTER_KEY
from app.redis_lock import RedisLock, run_as_leader
from tests.fakes import FakeJava, InMemoryRulesIndex


@pytest.fixture
def redis() -> FakeAsyncRedis:
    return FakeAsyncRedis()


# ---------- 锁 ----------

async def test_only_one_instance_gets_the_lock(redis: FakeAsyncRedis) -> None:
    a, b = RedisLock(redis, "k", 30), RedisLock(redis, "k", 30)
    assert await a.acquire() is True
    assert await b.acquire() is False
    assert await b.renew() is False  # 不是自己的锁不能续期


async def test_other_instance_gets_lock_after_release(redis: FakeAsyncRedis) -> None:
    a, b = RedisLock(redis, "k", 30), RedisLock(redis, "k", 30)
    await a.acquire()
    assert await a.release() is True
    assert await b.acquire() is True


async def test_other_instance_gets_lock_after_expiry(redis: FakeAsyncRedis) -> None:
    a, b = RedisLock(redis, "k", 30), RedisLock(redis, "k", 30)
    await a.acquire()
    await redis.pexpire("k", 10)  # 模拟持有者崩溃、没再续期
    await asyncio.sleep(0.05)
    assert await b.acquire() is True
    assert await a.renew() is False  # 原持有者回来后发现锁已不是自己的


async def test_non_holder_cannot_release_others_lock(redis: FakeAsyncRedis) -> None:
    a, b = RedisLock(redis, "k", 30), RedisLock(redis, "k", 30)
    await a.acquire()
    assert await b.release() is False
    assert (await redis.get("k")).decode() == a.value


async def test_renew_extends_ttl(redis: FakeAsyncRedis) -> None:
    a = RedisLock(redis, "k", 30)
    await a.acquire()
    await redis.expire("k", 1)
    assert await a.renew() is True
    assert await redis.ttl("k") > 1


# ---------- 选主执行 ----------

async def test_only_leader_runs_job_and_follower_takes_over(redis: FakeAsyncRedis) -> None:
    running: list[str] = []

    def job(name: str):  # noqa: ANN202
        async def forever() -> None:
            running.append(name)
            await asyncio.Event().wait()
        return forever

    # ttl=1：每 1/3 秒续期 / 重试一次
    leader = asyncio.create_task(run_as_leader(RedisLock(redis, "sync", 1), job("a")))
    await asyncio.sleep(0.05)
    follower = asyncio.create_task(run_as_leader(RedisLock(redis, "sync", 1), job("b")))
    await asyncio.sleep(0.8)  # 跨过几个续期周期，锁一直在 a 手里
    assert running == ["a"]

    leader.cancel()  # a 退出时主动释放锁
    await asyncio.gather(leader, return_exceptions=True)
    await asyncio.sleep(0.5)
    assert running == ["a", "b"]
    follower.cancel()
    await asyncio.gather(follower, return_exceptions=True)
    assert await redis.get("sync") is None


async def test_leader_stops_job_when_lock_is_lost(redis: FakeAsyncRedis) -> None:
    stopped = asyncio.Event()

    async def forever() -> None:
        try:
            await asyncio.Event().wait()
        finally:
            stopped.set()

    lock = RedisLock(redis, "sync", 1)
    leader = asyncio.create_task(run_as_leader(lock, forever))
    await asyncio.sleep(0.05)
    await redis.set("sync", "someone-else")  # 锁过期后被别的实例拿走
    await asyncio.wait_for(stopped.wait(), timeout=1)
    assert (await redis.get("sync")).decode() == "someone-else"  # 释放时没删别人的锁
    leader.cancel()
    await asyncio.gather(leader, return_exceptions=True)


# ---------- 规则库蓝绿重建 ----------

class _Embedder:
    dims = 4

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        return [[0.1] * 4 for _ in texts]

    async def embed_query(self, text: str) -> list[float]:
        return [0.1] * 4


def _write_rules(d: pathlib.Path, body: str) -> None:
    (d / "售后.md").write_text(f"# 售后\n\n## 退款\n\n{body}\n\n## 撤销\n\n{body}，可以撤销。", encoding="utf-8")


async def test_search_reads_old_version_during_rebuild_then_switches(redis: FakeAsyncRedis,
                                                                     tmp_path: pathlib.Path) -> None:
    _write_rules(tmp_path, "旧规则")
    rules = InMemoryRulesIndex(redis, _Embedder(), FakeJava(), tmp_path)
    assert await rules.refresh() is True
    old = await rules.current_version()
    assert {r["content"] for r in await rules.search("退款")} == {"旧规则", "旧规则，可以撤销。"}
    assert await rules.refresh() is False  # 内容没变不重建

    _write_rules(tmp_path, "新规则")
    rules.load_gate = asyncio.Event()
    rebuild = asyncio.create_task(rules.refresh())
    await rules.loading.wait()  # 新索引已创建、只写了一半
    assert f"ai_rules_{old}" in rules.store and len(rules.store) == 2
    assert {r["content"] for r in await rules.search("退款")} == {"旧规则", "旧规则，可以撤销。"}

    rules.load_gate.set()
    assert await rebuild is True
    new = await rules.current_version()
    assert new != old and (await redis.get(POINTER_KEY)).decode() == new
    assert {r["content"] for r in await rules.search("退款")} == {"新规则", "新规则，可以撤销。"}
    assert list(rules.store) == [f"ai_rules_{new}"]  # 旧索引已删除


async def test_search_before_first_build_returns_empty(redis: FakeAsyncRedis, tmp_path: pathlib.Path) -> None:
    rules = InMemoryRulesIndex(redis, _Embedder(), FakeJava(), tmp_path)
    assert await rules.search("退款") == []


async def test_search_retries_when_pointer_switches_mid_query(redis: FakeAsyncRedis,
                                                              tmp_path: pathlib.Path) -> None:
    _write_rules(tmp_path, "旧规则")
    rules = InMemoryRulesIndex(redis, _Embedder(), FakeJava(), tmp_path)
    await rules.refresh()
    old = await rules.current_version()
    # 模拟：读到旧指针后，另一实例已切到新版本并删掉旧索引
    rules.store["ai_rules_new00000"] = [{"title": "售后 > 退款", "content": "新规则"}]
    rules.store.pop(f"ai_rules_{old}")
    real_get = redis.get
    calls = 0

    async def get_once_stale(key: str) -> bytes | None:
        nonlocal calls
        calls += 1
        return old.encode() if calls == 1 else b"new00000"

    redis.get = get_once_stale  # type: ignore[method-assign]
    try:
        assert [r["content"] for r in await rules.search("退款")] == ["新规则"]
    finally:
        redis.get = real_get  # type: ignore[method-assign]
