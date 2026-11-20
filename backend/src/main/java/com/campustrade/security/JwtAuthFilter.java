package com.campustrade.security;

import com.campustrade.mapper.AdminUserMapper;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 解析 Bearer token：/api/admin/** 用后台密钥，其余用用户端密钥。
 * token 无效时不报错，交给 Security 规则决定是否需要登录（支持"可选登录"接口）。
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final AdminUserMapper adminUserMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            TokenType type = request.getRequestURI().startsWith("/api/admin/") ? TokenType.ADMIN : TokenType.USER;
            authenticate(header.substring(7).trim(), type);
        }
        chain.doFilter(request, response);
    }

    private void authenticate(String token, TokenType type) {
        Claims claims = jwtService.parse(token, type);
        if (claims == null || tokenStore.isBlacklisted(claims.getId())) {
            return;
        }
        long id = Long.parseLong(claims.getSubject());
        Number iatMs = claims.get(JwtService.CLAIM_IAT_MS, Number.class);
        long iat = iatMs != null ? iatMs.longValue() : claims.getIssuedAt().getTime();
        if (tokenStore.issuedBeforeInvalidation(type, id, iat)) {
            return;
        }
        Set<String> permissions = new HashSet<>();
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        if (type == TokenType.ADMIN) {
            permissions.addAll(adminUserMapper.selectPermissionCodes(id));
            permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        } else {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }
        LoginUser user = new LoginUser(id, type, claims.getId(),
                claims.getExpiration().toInstant().getEpochSecond(), permissions);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
