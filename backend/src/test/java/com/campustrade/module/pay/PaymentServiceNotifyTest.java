package com.campustrade.module.pay;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.entity.Payment;
import com.campustrade.entity.TradeOrder;
import com.campustrade.mapper.PaymentMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.order.OrderFlow;
import com.campustrade.module.order.OrderStateMachine;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 支付入账：金额校验、重复通知幂等、关单后到账自动退款 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class PaymentServiceNotifyTest {

    private static final String OUT_TRADE_NO = "P202611221200001";

    @Mock
    private PaymentMapper paymentMapper;
    @Mock
    private TradeOrderMapper orderMapper;
    @Mock
    private PaymentGateway gateway;
    @Mock
    private OrderFlow orderFlow;
    @InjectMocks
    private PaymentService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(Payment.class, TradeOrder.class);
    }

    private static Payment payment() {
        Payment p = new Payment();
        p.setId(11L);
        p.setOrderId(9001L);
        p.setOutTradeNo(OUT_TRADE_NO);
        p.setAmount(new BigDecimal("300.00"));
        p.setStatus(PaymentService.UNPAID);
        return p;
    }

    private static TradeOrder order(int status) {
        TradeOrder o = new TradeOrder();
        o.setId(9001L);
        o.setStatus(status);
        return o;
    }

    private void stubPayment(Payment p) {
        when(paymentMapper.selectOne(any(Wrapper.class))).thenReturn(p);
    }

    @Test
    void unknownPaymentIsIgnored() {
        stubPayment(null);
        assertThat(service.handlePaid(OUT_TRADE_NO, "T1", new BigDecimal("300.00"), null)).isFalse();
        verify(paymentMapper, never()).update(any(Wrapper.class));
    }

    @Test
    void amountMismatchIsRejected() {
        stubPayment(payment());
        assertThat(service.handlePaid(OUT_TRADE_NO, "T1", new BigDecimal("0.01"), null)).isFalse();
        verify(paymentMapper, never()).update(any(Wrapper.class));
    }

    @Test
    void firstNotifyMovesOrderToWaitDeliver() {
        stubPayment(payment());
        when(paymentMapper.update(any(Wrapper.class))).thenReturn(1);
        TradeOrder o = order(OrderStatus.WAIT_PAY);
        when(orderMapper.selectById(9001L)).thenReturn(o);

        assertThat(service.handlePaid(OUT_TRADE_NO, "T1", new BigDecimal("300.00"), "{}")).isTrue();

        verify(orderFlow).transit(eq(o), eq(OrderStateMachine.PAY), anyInt(), isNull(), isNull(), isNull(), any());
        verify(gateway, never()).refund(anyString(), anyString(), any());
    }

    @Test
    void duplicateNotifyIsIdempotent() {
        stubPayment(payment());
        // 状态条件更新没命中：之前已经入账
        when(paymentMapper.update(any(Wrapper.class))).thenReturn(0);

        assertThat(service.handlePaid(OUT_TRADE_NO, "T1", new BigDecimal("300.00"), "{}")).isTrue();

        verify(orderMapper, never()).selectById(anyLong());
        verify(orderFlow, never()).transit(any(), anyString(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void latePayAfterCloseIsRefunded() {
        stubPayment(payment());
        when(paymentMapper.update(any(Wrapper.class))).thenReturn(1);
        when(orderMapper.selectById(9001L)).thenReturn(order(OrderStatus.CANCELED));
        when(gateway.refund(OUT_TRADE_NO, "RL11", new BigDecimal("300.00"))).thenReturn(true);

        assertThat(service.handlePaid(OUT_TRADE_NO, "T1", new BigDecimal("300.00"), "{}")).isTrue();

        verify(orderFlow, never()).transit(any(), anyString(), anyInt(), any(), any(), any(), any());
        verify(orderFlow).writeLog(eq(9001L), eq(OrderStatus.CANCELED), eq(OrderStatus.CANCELED),
                eq("LATE_PAY_REFUND"), anyInt(), isNull(), anyString());
        verify(gateway).refund(OUT_TRADE_NO, "RL11", new BigDecimal("300.00"));
        // 入账一次 + 退款成功后标记已退款一次
        verify(paymentMapper, times(2)).update(any(Wrapper.class));
    }
}
