package com.campustrade.common;

/** 业务断言小工具 */
public final class Biz {

    private Biz() {
    }

    public static void check(boolean condition, ErrorCode errorCode) {
        if (!condition) {
            throw new BizException(errorCode);
        }
    }

    public static void check(boolean condition, ErrorCode errorCode, String message) {
        if (!condition) {
            throw new BizException(errorCode, message);
        }
    }

    public static <T> T notNull(T value, ErrorCode errorCode) {
        if (value == null) {
            throw new BizException(errorCode);
        }
        return value;
    }
}
