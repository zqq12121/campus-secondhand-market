package com.campustrade.module.pay;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.TxUtil;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.Payment;
import com.campustrade.entity.TradeOrder;
import com.campustrade.mapper.PaymentMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.order.OrderFlow;
import com.campustrade.module.order.OrderStateMachine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 担保交易支付（接口文档 8.7–8.9）：发起支付、查询补偿、异步通知入账（幂等） */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    public static final int UNPAID = 0;
    public static final int PAID = 1;
    public static final int CLOSED = 2;
    public static final int REFUNDED = 3;

    private final PaymentMapper paymentMapper;
    private final TradeOrderMapper orderMapper;
    private final PaymentGateway gateway;
    private final OrderFlow orderFlow;
    private final AppProperties props;

    public record PayResult(String outTradeNo, String payForm, LocalDateTime expireAt) {
    }

    public record PayStatus(Integer orderStatus, Integer paymentStatus, LocalDateTime paidAt) {
    }

    public record PaymentVO(Integer status, BigDecimal amount, LocalDateTime paidAt) {
    }

    @Transactional
    public PayResult startPay(long buyerId, long orderId) {
        TradeOrder o = orderMapper.selectById(orderId);
        Biz.check(o != null && o.getBuyerId() == buyerId, ErrorCode.NOT_FOUND);
        Biz.check(o.getStatus() == OrderStatus.WAIT_PAY, ErrorCode.ORDER_STATE_INVALID, "订单状态不允许支付");
        LocalDateTime expireAt = orderFlow.payExpireAt(o);
        Biz.check(LocalDateTime.now().isBefore(expireAt), ErrorCode.ORDER_STATE_INVALID, "订单已超时关闭");
        Payment p = latest(orderId);
        if (p == null || p.getStatus() != UNPAID) {
            p = new Payment();
            p.setOrderId(orderId);
            p.setOutTradeNo("P" + o.getOrderNo());
            p.setAmount(o.getTotalAmount());
            p.setStatus(UNPAID);
            paymentMapper.insert(p);
        }
        String returnUrl = props.pay().returnUrl().replace("{orderId}", String.valueOf(orderId));
        String form = gateway.pagePay(p.getOutTradeNo(), p.getAmount(), o.getGoodsTitle(), expireAt, returnUrl);
        return new PayResult(p.getOutTradeNo(), form, expireAt);
    }

    /** 前端从收银台返回后轮询；本地仍未支付时主动查询一次渠道做补偿 */
    public PayStatus payStatus(long buyerId, long orderId) {
        TradeOrder o = orderMapper.selectById(orderId);
        Biz.check(o != null && o.getBuyerId() == buyerId, ErrorCode.NOT_FOUND);
        Payment p = latest(orderId);
        if (o.getStatus() == OrderStatus.WAIT_PAY && p != null && p.getStatus() == UNPAID) {
            compensate(p);
            o = orderMapper.selectById(orderId);
            p = paymentMapper.selectById(p.getId());
        }
        return new PayStatus(o.getStatus(), p == null ? null : p.getStatus(), p == null ? null : p.getPaidAt());
    }

    /** 查询渠道，已支付则入账；返回是否已支付 */
    public boolean compensate(Payment p) {
        PaymentGateway.QueryResult r = gateway.query(p.getOutTradeNo());
        if (r.paid()) {
            handlePaid(p.getOutTradeNo(), r.tradeNo(), r.amount(), null);
        }
        return r.paid();
    }

    /**
     * 支付成功入账（异步通知 / 查询补偿 / 模拟收银台共用），以 out_trade_no + 状态条件更新保证幂等。
     * 订单已被取消后才到账：自动全额退款。
     */
    @Transactional
    public boolean handlePaid(String outTradeNo, String tradeNo, BigDecimal amount, String payload) {
        Payment p = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>().eq(Payment::getOutTradeNo, outTradeNo));
        if (p == null) {
            log.warn("支付通知找不到支付单 outTradeNo={}", outTradeNo);
            return false;
        }
        if (amount != null && amount.compareTo(p.getAmount()) != 0) {
            log.error("支付金额不一致 outTradeNo={} expect={} actual={}", outTradeNo, p.getAmount(), amount);
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int n = paymentMapper.update(new LambdaUpdateWrapper<Payment>().eq(Payment::getId, p.getId())
                .in(Payment::getStatus, UNPAID, CLOSED).set(Payment::getStatus, PAID)
                .set(Payment::getAlipayTradeNo, tradeNo).set(Payment::getPaidAt, now)
                .set(payload != null, Payment::getNotifyPayload, payload));
        if (n == 0) {
            return true;
        }
        TradeOrder o = orderMapper.selectById(p.getOrderId());
        if (o.getStatus() == OrderStatus.WAIT_PAY) {
            orderFlow.transit(o, OrderStateMachine.PAY, Operator.SYSTEM, null, null, null,
                    w -> w.set(TradeOrder::getPaidAt, now));
        } else {
            log.warn("订单已关闭后到账，自动退款 orderId={} outTradeNo={}", o.getId(), outTradeNo);
            orderFlow.writeLog(o.getId(), o.getStatus(), o.getStatus(), "LATE_PAY_REFUND", Operator.SYSTEM, null,
                    "订单关闭后到账，自动原路退款");
            refundLatePay(p);
        }
        return true;
    }

    /** 售后 / 裁决退款，成功后支付单标记为已全额退款 */
    public boolean refund(long orderId, String outRefundNo) {
        Payment p = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId, orderId)
                .in(Payment::getStatus, PAID, REFUNDED).orderByDesc(Payment::getId).last("LIMIT 1"));
        Biz.notNull(p, ErrorCode.NOT_FOUND);
        if (p.getStatus() == REFUNDED) {
            return true;
        }
        boolean ok = gateway.refund(p.getOutTradeNo(), outRefundNo, p.getAmount());
        if (ok) {
            paymentMapper.update(new LambdaUpdateWrapper<Payment>().eq(Payment::getId, p.getId())
                    .set(Payment::getStatus, REFUNDED));
        }
        return ok;
    }

    public PaymentVO view(long orderId) {
        Payment p = latest(orderId);
        return p == null ? null : new PaymentVO(p.getStatus(), p.getAmount(), p.getPaidAt());
    }

    private void refundLatePay(Payment p) {
        TxUtil.afterCommit(() -> {
            if (gateway.refund(p.getOutTradeNo(), "RL" + p.getId(), p.getAmount())) {
                paymentMapper.update(new LambdaUpdateWrapper<Payment>().eq(Payment::getId, p.getId())
                        .set(Payment::getStatus, REFUNDED));
            } else {
                log.error("迟到支付退款失败，需人工处理 outTradeNo={}", p.getOutTradeNo());
            }
        });
    }

    private Payment latest(long orderId) {
        return paymentMapper.selectOne(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId, orderId)
                .orderByDesc(Payment::getId).last("LIMIT 1"));
    }
}
