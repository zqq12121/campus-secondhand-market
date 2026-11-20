package com.campustrade.module.pay;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 本地模拟支付（app.pay.mode=mock，默认）：收银台由 MockPayController 提供，
 * 点击确认后直接走与支付宝异步通知相同的入账逻辑。仅用于没有沙箱密钥时的开发演示。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pay.mode", havingValue = "mock", matchIfMissing = true)
public class MockPayGateway implements PaymentGateway {

    private final StringRedisTemplate redis;

    @Override
    public String pagePay(String outTradeNo, BigDecimal amount, String subject, LocalDateTime expireAt,
                          String returnUrl) {
        return "<form name=\"punchout_form\" method=\"get\" action=\"/api/pay/mock/cashier\">"
                + "<input type=\"hidden\" name=\"outTradeNo\" value=\"" + HtmlUtils.htmlEscape(outTradeNo) + "\">"
                + "</form><script>document.forms[0].submit();</script>";
    }

    @Override
    public QueryResult query(String outTradeNo) {
        String tradeNo = redis.opsForValue().get(paidKey(outTradeNo));
        return new QueryResult(tradeNo != null, tradeNo, null);
    }

    @Override
    public void close(String outTradeNo) {
        // 模拟渠道无需关单
    }

    @Override
    public boolean refund(String outTradeNo, String outRefundNo, BigDecimal amount) {
        return true;
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        return false;
    }

    /** 模拟收银台确认支付时调用 */
    public String markPaid(String outTradeNo) {
        String tradeNo = "MOCK" + System.currentTimeMillis();
        redis.opsForValue().set(paidKey(outTradeNo), tradeNo, Duration.ofDays(1));
        return tradeNo;
    }

    private static String paidKey(String outTradeNo) {
        return "mockpay:paid:" + outTradeNo;
    }
}
