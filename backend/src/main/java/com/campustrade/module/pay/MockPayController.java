package com.campustrade.module.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.config.AppProperties;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.pay.entity.Payment;
import com.campustrade.module.pay.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

import java.math.RoundingMode;

/** 模拟收银台（仅 app.pay.mode=mock 时注册） */
@RestController
@RequestMapping("/api/pay/mock")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.pay.mode", havingValue = "mock", matchIfMissing = true)
public class MockPayController {

    private final PaymentMapper paymentMapper;
    private final TradeOrderMapper orderMapper;
    private final MockPayGateway gateway;
    private final PaymentService paymentService;
    private final AppProperties props;

    @GetMapping(value = "/cashier", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> cashier(@RequestParam String outTradeNo) {
        Payment p = find(outTradeNo);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("<p>支付单不存在</p>");
        }
        TradeOrder o = orderMapper.selectById(p.getOrderId());
        String no = HtmlUtils.htmlEscape(p.getOutTradeNo());
        String html = """
                <!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>模拟收银台</title>
                <style>body{font-family:system-ui,sans-serif;max-width:420px;margin:48px auto;padding:0 16px;color:#1f2937}
                .card{border:1px solid #e5e7eb;border-radius:12px;padding:24px}.amt{font-size:32px;font-weight:600;margin:8px 0 16px}
                button{width:100%%;padding:14px;border:0;border-radius:8px;background:#1677ff;color:#fff;font-size:16px;cursor:pointer}
                .tip{color:#6b7280;font-size:13px;margin-top:16px}</style></head><body><div class="card">
                <div>%s</div><div class="amt">¥ %s</div><div class="tip">支付单号 %s</div>
                <form method="post" action="/api/pay/mock/confirm"><input type="hidden" name="outTradeNo" value="%s">
                <button type="submit">确认支付（模拟）</button></form>
                <p class="tip">本页面仅用于本地开发：未配置支付宝沙箱时代替支付宝收银台。</p></div></body></html>
                """.formatted(HtmlUtils.htmlEscape(o.getGoodsTitle()),
                p.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(), no, no);
        return ResponseEntity.ok(html);
    }

    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@RequestParam String outTradeNo) {
        Payment p = find(outTradeNo);
        if (p == null) {
            return ResponseEntity.notFound().build();
        }
        String tradeNo = gateway.markPaid(outTradeNo);
        paymentService.handlePaid(outTradeNo, tradeNo, p.getAmount(), "{\"mock\":true}");
        String returnUrl = props.pay().returnUrl().replace("{orderId}", String.valueOf(p.getOrderId()));
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, returnUrl).build();
    }

    private Payment find(String outTradeNo) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>().eq(Payment::getOutTradeNo, outTradeNo));
    }
}
