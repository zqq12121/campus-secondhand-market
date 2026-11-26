package com.campustrade.module.pay;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 未接入支付（app.pay.mode=disabled）：担保交易关闭，下单与发布处已拦截；
 * 这里兜底拒绝发起支付，不认任何回调。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.pay.mode", havingValue = "disabled")
public class DisabledPayGateway implements PaymentGateway {

    @Override
    public String pagePay(String outTradeNo, BigDecimal amount, String subject, LocalDateTime expireAt,
                          String returnUrl) {
        throw new BizException(ErrorCode.TRADE_MODE_UNSUPPORTED, "担保交易暂未开放");
    }

    @Override
    public QueryResult query(String outTradeNo) {
        return new QueryResult(false, null, null);
    }

    @Override
    public void close(String outTradeNo) {
        // 未发起过支付，无需关单
    }

    @Override
    public boolean refund(String outTradeNo, String outRefundNo, BigDecimal amount) {
        log.error("支付未开启却收到退款请求 outTradeNo={}，请人工处理", outTradeNo);
        return false;
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        return false;
    }
}
