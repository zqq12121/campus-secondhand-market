package com.campustrade.module.pay;

import com.alipay.api.AlipayApiException;
import com.alipay.api.AlipayClient;
import com.alipay.api.DefaultAlipayClient;
import com.alipay.api.internal.util.AlipaySignature;
import com.alipay.api.request.AlipayTradeCloseRequest;
import com.alipay.api.request.AlipayTradePagePayRequest;
import com.alipay.api.request.AlipayTradeQueryRequest;
import com.alipay.api.request.AlipayTradeRefundRequest;
import com.alipay.api.response.AlipayTradeQueryResponse;
import com.alipay.api.response.AlipayTradeRefundResponse;
import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 支付宝开放平台（电脑网站支付），密钥全部来自环境变量 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.pay.mode", havingValue = "alipay")
public class AlipayGateway implements PaymentGateway {

    private static final String CHARSET = "UTF-8";
    private static final String SIGN_TYPE = "RSA2";
    private static final Set<String> PAID = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");
    private static final DateTimeFormatter EXPIRE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlipayClient client;
    private final AppProperties.Pay cfg;
    private final ObjectMapper objectMapper;

    public AlipayGateway(AppProperties props, ObjectMapper objectMapper) {
        this.cfg = props.pay();
        if (isBlank(cfg.appId()) || isBlank(cfg.privateKey()) || isBlank(cfg.alipayPublicKey())) {
            throw new IllegalStateException("PAY_MODE=alipay 时必须设置 ALIPAY_APP_ID / ALIPAY_PRIVATE_KEY / ALIPAY_PUBLIC_KEY");
        }
        this.client = new DefaultAlipayClient(cfg.gateway(), cfg.appId(), cfg.privateKey(), "json", CHARSET,
                cfg.alipayPublicKey(), SIGN_TYPE);
        this.objectMapper = objectMapper;
    }

    @Override
    public String pagePay(String outTradeNo, BigDecimal amount, String subject, LocalDateTime expireAt,
                          String returnUrl) {
        AlipayTradePagePayRequest req = new AlipayTradePagePayRequest();
        req.setNotifyUrl(cfg.notifyUrl());
        req.setReturnUrl(returnUrl);
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", outTradeNo);
        biz.put("total_amount", money(amount));
        biz.put("subject", subject);
        biz.put("product_code", "FAST_INSTANT_TRADE_PAY");
        biz.put("time_expire", expireAt.format(EXPIRE));
        req.setBizContent(json(biz));
        try {
            return client.pageExecute(req, "POST").getBody();
        } catch (AlipayApiException e) {
            log.error("支付宝下单失败 outTradeNo={}", outTradeNo, e);
            throw new BizException(ErrorCode.INTERNAL, "支付宝下单失败");
        }
    }

    @Override
    public QueryResult query(String outTradeNo) {
        AlipayTradeQueryRequest req = new AlipayTradeQueryRequest();
        req.setBizContent(json(Map.of("out_trade_no", outTradeNo)));
        try {
            AlipayTradeQueryResponse resp = client.execute(req);
            if (resp.isSuccess() && PAID.contains(resp.getTradeStatus())) {
                return new QueryResult(true, resp.getTradeNo(), new BigDecimal(resp.getTotalAmount()));
            }
        } catch (AlipayApiException e) {
            log.warn("支付宝查询失败 outTradeNo={}", outTradeNo, e);
        }
        return new QueryResult(false, null, null);
    }

    @Override
    public void close(String outTradeNo) {
        AlipayTradeCloseRequest req = new AlipayTradeCloseRequest();
        req.setBizContent(json(Map.of("out_trade_no", outTradeNo)));
        try {
            client.execute(req);
        } catch (AlipayApiException e) {
            log.warn("支付宝关单失败 outTradeNo={}", outTradeNo, e);
        }
    }

    @Override
    public boolean refund(String outTradeNo, String outRefundNo, BigDecimal amount) {
        AlipayTradeRefundRequest req = new AlipayTradeRefundRequest();
        req.setBizContent(json(Map.of("out_trade_no", outTradeNo, "refund_amount", money(amount),
                "out_request_no", outRefundNo)));
        try {
            AlipayTradeRefundResponse resp = client.execute(req);
            if (!resp.isSuccess()) {
                log.warn("支付宝退款失败 outTradeNo={} code={} msg={}", outTradeNo, resp.getSubCode(), resp.getSubMsg());
            }
            return resp.isSuccess();
        } catch (AlipayApiException e) {
            log.warn("支付宝退款异常 outTradeNo={}", outTradeNo, e);
            return false;
        }
    }

    @Override
    public boolean verifyNotify(Map<String, String> params) {
        try {
            return cfg.appId().equals(params.get("app_id"))
                    && AlipaySignature.rsaCheckV1(params, cfg.alipayPublicKey(), CHARSET, SIGN_TYPE);
        } catch (AlipayApiException e) {
            return false;
        }
    }

    private String json(Map<String, Object> m) {
        try {
            return objectMapper.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
