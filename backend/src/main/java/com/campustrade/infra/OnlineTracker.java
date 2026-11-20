package com.campustrade.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.security.Principal;
import java.time.Duration;

/** 在线状态：按用户统计 WebSocket 连接数（多标签页） */
@Component
@RequiredArgsConstructor
public class OnlineTracker {

    private static final Duration TTL = Duration.ofHours(12);
    private final StringRedisTemplate redis;

    @EventListener
    public void onConnect(SessionConnectedEvent e) {
        Principal p = e.getUser();
        if (p != null) {
            redis.opsForValue().increment(key(p.getName()));
            redis.expire(key(p.getName()), TTL);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent e) {
        Principal p = e.getUser();
        if (p != null) {
            Long n = redis.opsForValue().decrement(key(p.getName()));
            if (n != null && n <= 0) {
                redis.delete(key(p.getName()));
            }
        }
    }

    public boolean isOnline(long userId) {
        String v = redis.opsForValue().get(key(String.valueOf(userId)));
        return v != null && Long.parseLong(v) > 0;
    }

    private static String key(String userId) {
        return "online:" + userId;
    }
}
