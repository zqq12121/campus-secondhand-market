"""按用户的分钟 / 每日次数限流（固定窗口计数）。"""
import time

from redis.asyncio import Redis


async def check_rate(redis: Redis, user_id: str, per_minute: int, per_day: int) -> int | None:
    """未超限返回 None；超限返回建议的重试秒数。"""
    now = int(time.time())
    local = now + 8 * 3600  # 按东八区自然日计
    windows = (
        (f"ai:rl:m:{user_id}:{now // 60}", per_minute, 60 - now % 60),
        (f"ai:rl:d:{user_id}:{local // 86400}", per_day, 86400 - local % 86400),
    )
    for key, limit, retry_after in windows:
        count = await redis.incr(key)
        if count == 1:
            await redis.expire(key, retry_after + 5)
        if count > limit:
            return retry_after
    return None
