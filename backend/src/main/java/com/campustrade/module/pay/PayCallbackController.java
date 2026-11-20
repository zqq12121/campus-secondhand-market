package com.campustrade.module.pay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 支付宝异步通知（接口文档 8.9）：验签 → 入账 → 返回 success / failure */
@Slf4j
@RestController
@RequestMapping("/api/pay/alipay")
@RequiredArgsConstructor
public class PayCallbackController {

    private static final Set<String> PAID = Set.of("TRADE_SUCCESS", "TRADE_FINISHED");

    private final PaymentGateway gateway;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/notify", produces = MediaType.TEXT_PLAIN_VALUE)
    public String notify(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, String.join(",", v)));
        if (!gateway.verifyNotify(params)) {
            log.warn("支付宝通知验签失败 outTradeNo={}", params.get("out_trade_no"));
            return "failure";
        }
        if (!PAID.contains(params.get("trade_status"))) {
            return "success";
        }
        try {
            boolean ok = paymentService.handlePaid(params.get("out_trade_no"), params.get("trade_no"),
                    new BigDecimal(params.get("total_amount")), toJson(params));
            return ok ? "success" : "failure";
        } catch (RuntimeException e) {
            log.error("支付通知处理失败 outTradeNo={}", params.get("out_trade_no"), e);
            return "failure";
        }
    }

    private String toJson(Map<String, String> params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
