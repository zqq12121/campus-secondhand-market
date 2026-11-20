package com.campustrade.infra;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Redis ZSet 滑动窗口限流 */
@Component
@RequiredArgsConstructor
public class RateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>("""
            local key, now, window, limit, member = KEYS[1], tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), ARGV[4]
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            if redis.call('ZCARD', key) >= limit then
              local oldest = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
              return math.ceil((tonumber(oldest[2]) + window - now) / 1000)
            end
            redis.call('ZADD', key, now, member)
            redis.call('PEXPIRE', key, window)
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;

    /** 超限抛 429，data.retryAfter 为秒 */
    public void acquire(String key, int limit, long windowSeconds) {
        Long retryAfter = redis.execute(SCRIPT, List.of("rl:" + key),
                String.valueOf(System.currentTimeMillis()), String.valueOf(windowSeconds * 1000),
                String.valueOf(limit), UUID.randomUUID().toString());
        if (retryAfter != null && retryAfter > 0) {
            throw BizException.of(ErrorCode.TOO_MANY_REQUESTS, Map.of("retryAfter", retryAfter.intValue()));
        }
    }
}
