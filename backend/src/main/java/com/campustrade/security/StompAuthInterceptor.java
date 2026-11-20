package com.campustrade.security;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/** STOMP CONNECT 帧校验 Authorization: Bearer <用户端 token>，Principal 名为用户 ID */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {

    private final JwtService jwtService;
    private final TokenStore tokenStore;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(acc.getCommand())) {
            String header = acc.getFirstNativeHeader("Authorization");
            LoginUser user = header != null && header.startsWith("Bearer ") ? parse(header.substring(7).trim()) : null;
            if (user == null) {
                throw new IllegalArgumentException("未登录或登录已失效");
            }
            acc.setUser(new UsernamePasswordAuthenticationToken(user, null,
                    List.of(new SimpleGrantedAuthority("ROLE_USER"))) {
                @Override
                public String getName() {
                    return String.valueOf(user.id());
                }
            });
        } else if (acc.getUser() == null && acc.getCommand() != null
                && acc.getCommand() != StompCommand.DISCONNECT) {
            throw new IllegalArgumentException("未登录");
        }
        return message;
    }

    private LoginUser parse(String token) {
        Claims c = jwtService.parse(token, TokenType.USER);
        if (c == null || tokenStore.isBlacklisted(c.getId())) {
            return null;
        }
        long id = Long.parseLong(c.getSubject());
        Number iatMs = c.get(JwtService.CLAIM_IAT_MS, Number.class);
        long iat = iatMs != null ? iatMs.longValue() : c.getIssuedAt().getTime();
        if (tokenStore.issuedBeforeInvalidation(TokenType.USER, id, iat)) {
            return null;
        }
        return new LoginUser(id, TokenType.USER, c.getId(), c.getExpiration().toInstant().getEpochSecond(), Set.of());
    }
}
