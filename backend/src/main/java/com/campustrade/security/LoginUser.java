package com.campustrade.security;

import java.util.Set;

/** 当前登录主体：用户端用户或后台管理员 */
public record LoginUser(long id, TokenType type, String jti, long expiresAtEpochSecond, Set<String> permissions) {
}
