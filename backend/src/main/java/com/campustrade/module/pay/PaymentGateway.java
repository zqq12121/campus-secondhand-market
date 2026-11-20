package com.campustrade.module.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/** 支付渠道抽象：支付宝沙箱（AlipayGateway）或本地模拟（MockPayGateway），由 app.pay.mode 决定 */
public interface PaymentGateway {

    /** 电脑网站支付：返回自动提交到收银台的表单 HTML */
    String pagePay(String outTradeNo, BigDecimal amount, String subject, LocalDateTime expireAt, String returnUrl);

    /** 主动查询支付结果 */
    QueryResult query(String outTradeNo);

    void close(String outTradeNo);

    /** 全额退款；同一 outRefundNo 重复调用幂等。成功返回 true */
    boolean refund(String outTradeNo, String outRefundNo, BigDecimal amount);

    /** 异步通知验签 */
    boolean verifyNotify(Map<String, String> params);

    record QueryResult(boolean paid, String tradeNo, BigDecimal amount) {
    }
}
