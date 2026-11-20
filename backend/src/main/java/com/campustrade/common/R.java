package com.campustrade.common;

import org.slf4j.MDC;

/** 统一响应体：{code, message, data, traceId} */
public record R<T>(int code, String message, T data, String traceId) {

    public static <T> R<T> ok(T data) {
        return new R<>(0, "ok", data, MDC.get(TraceIdFilter.MDC_KEY));
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String message, T data) {
        return new R<>(code, message, data, MDC.get(TraceIdFilter.MDC_KEY));
    }
}
