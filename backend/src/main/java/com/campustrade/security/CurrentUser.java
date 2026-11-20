package com.campustrade.security;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 读取当前登录主体 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static LoginUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
            return u;
        }
        return null;
    }

    /** 可选登录：未登录返回 null */
    public static Long idOrNull() {
        LoginUser u = get();
        return u == null ? null : u.id();
    }

    public static LoginUser require() {
        LoginUser u = get();
        if (u == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return u;
    }

    public static long id() {
        return require().id();
    }
}
