"""Redis 分布式锁：SET key value NX EX ttl 加锁，value 是每个持有者自己的随机 uuid。

续期和释放都用 Lua 脚本「先比对 value 再操作」，在 Redis 里原子执行，保证只动自己的锁。
如果在客户端分两步做（先 GET 比对，再 DEL），两步之间锁可能刚好过期、被别的实例拿到，
第二步就会把别人的锁删掉。
"""
import asyncio
import logging
import uuid
from collections.abc import Awaitable, Callable

from redis.asyncio import Redis

log = logging.getLogger(__name__)

_RENEW = """
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('expire', KEYS[1], ARGV[2])
end
return 0
"""

_RELEASE = """
if redis.call('get', KEYS[1]) == ARGV[1] then
  return redis.call('del', KEYS[1])
end
return 0
"""


class RedisLock:
    def __init__(self, redis: Redis, key: str, ttl_seconds: int) -> None:
        self._redis = redis
        self.key = key
        self.ttl = ttl_seconds
        self.value = uuid.uuid4().hex

    async def acquire(self) -> bool:
        return bool(await self._redis.set(self.key, self.value, nx=True, ex=self.ttl))

    async def renew(self) -> bool:
        """锁还是自己的才续期；返回 False 表示锁已过期或被别人持有。"""
        return bool(await self._redis.eval(_RENEW, 1, self.key, self.value, self.ttl))

    async def release(self) -> bool:
        """锁还是自己的才删除；不会误删别人的锁。"""
        return bool(await self._redis.eval(_RELEASE, 1, self.key, self.value))


async def run_as_leader(lock: RedisLock, job: Callable[[], Awaitable[None]]) -> None:
    """选主执行：只有持锁实例运行 job（通常是无限循环的同步任务），其他实例每 ttl/3 秒重试抢锁。

    持锁期间每 ttl/3 秒续期一次；续期失败（锁过期被别人拿走、Redis 不可用）就停掉 job，回去重新抢锁，
    避免两个实例同时同步。进程退出（本协程被取消）时主动释放锁，让其他实例尽快接手。
    """
    interval = lock.ttl / 3
    while True:
        try:
            acquired = await lock.acquire()
        except Exception:
            log.exception("抢锁失败 key=%s", lock.key)
            acquired = False
        if not acquired:
            await asyncio.sleep(interval)
            continue
        log.info("成为主实例，开始执行 key=%s", lock.key)
        task = asyncio.create_task(job())
        try:
            while True:
                await asyncio.wait({task}, timeout=interval)
                if task.done():
                    if not task.cancelled() and task.exception():
                        log.error("任务异常退出 key=%s", lock.key, exc_info=task.exception())
                    break
                try:
                    still_mine = await lock.renew()
                except Exception:
                    log.exception("续期失败 key=%s", lock.key)
                    still_mine = False
                if not still_mine:
                    log.warning("锁已丢失，停止执行 key=%s", lock.key)
                    break
        finally:
            task.cancel()
            try:
                await lock.release()
            except Exception:
                log.warning("释放锁失败 key=%s，等待过期", lock.key)
        await asyncio.sleep(interval)
