package com.campustrade.security;

import com.campustrade.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    /** 毫秒级签发时间，用于与"全部失效"时间点精确比较 */
    public static final String CLAIM_IAT_MS = "iatMs";

    private final SecretKey userKey;
    private final SecretKey adminKey;
    private final AppProperties.Jwt cfg;

    public JwtService(AppProperties props) {
        this.cfg = props.jwt();
        this.userKey = key(cfg.userSecret(), "JWT_USER_SECRET");
        this.adminKey = key(cfg.adminSecret(), "JWT_ADMIN_SECRET");
    }

    private static SecretKey key(String secret, String envName) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("环境变量 " + envName + " 未设置或少于 32 字节");
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public record Issued(String token, long expiresIn) {
    }

    public Issued issue(long subjectId, TokenType type) {
        long ttl = type == TokenType.USER ? cfg.userTtlSeconds() : cfg.adminTtlSeconds();
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(String.valueOf(subjectId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .claim(CLAIM_IAT_MS, now.toEpochMilli())
                .expiration(Date.from(now.plusSeconds(ttl)))
                .signWith(type == TokenType.USER ? userKey : adminKey)
                .compact();
        return new Issued(token, ttl);
    }

    /** 校验签名与有效期，失败返回 null */
    public Claims parse(String token, TokenType type) {
        try {
            return Jwts.parser().verifyWith(type == TokenType.USER ? userKey : adminKey).build()
                    .parseSignedClaims(token).getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }
}
