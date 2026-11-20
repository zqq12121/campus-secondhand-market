package com.campustrade.security;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * token 失效管理（Redis）：
 * 1. 登出：jti 进黑名单，TTL = token 剩余有效期
 * 2. 改密码 / 封禁 / 停用：记录 validAfter（毫秒），签发早于它的 token 全部失效
 */
@Component
@RequiredArgsConstructor
public class TokenStore {

    private static final Duration VALID_AFTER_TTL = Duration.ofDays(8);

    private final StringRedisTemplate redis;

    public void blacklist(LoginUser user) {
        long remain = user.expiresAtEpochSecond() - Instant.now().getEpochSecond();
        if (remain > 0) {
            redis.opsForValue().set("jwt:black:" + user.jti(), "1", Duration.ofSeconds(remain));
        }
    }

    public boolean isBlacklisted(String jti) {
        return Boolean.TRUE.equals(redis.hasKey("jwt:black:" + jti));
    }

    public void invalidateAll(TokenType type, long subjectId) {
        redis.opsForValue().set(validAfterKey(type, subjectId),
                String.valueOf(Instant.now().toEpochMilli()), VALID_AFTER_TTL);
    }

    public boolean issuedBeforeInvalidation(TokenType type, long subjectId, long issuedAtMillis) {
        String v = redis.opsForValue().get(validAfterKey(type, subjectId));
        return v != null && issuedAtMillis < Long.parseLong(v);
    }

    private static String validAfterKey(TokenType type, long id) {
        return "jwt:validAfter:" + type.name().toLowerCase() + ":" + id;
    }
}
