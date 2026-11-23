package com.campustrade.module.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.infra.mq.DelayMessage;
import com.campustrade.infra.mq.DelayType;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.module.pay.entity.Payment;
import com.campustrade.module.pay.mapper.PaymentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 超时自动处理：RabbitMQ 延时消息与定时扫描共用。
 * 执行前重新校验状态与截止时间（消息可能早到、重复或已过时），不满足则直接忽略。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimeoutService {

    private final TradeOrderMapper orderMapper;
    private final PaymentMapper paymentMapper;
    private final OrderFlow flow;
    private final PaymentService paymentService;
    private final AfterSaleService afterSaleService;
    private final TransactionTemplate tx;

    public void handle(DelayMessage m) {
        if (m.afterSaleId() != null && DelayType.AUTO_CONFIRM_AFTER_REJECT.name().equals(m.action())) {
            afterSaleService.autoConfirmRejected(m.afterSaleId());
        } else if (m.afterSaleId() != null) {
            afterSaleService.autoAgree(m.afterSaleId());
        } else {
            handleOrder(m.orderId(), m.action());
        }
    }

    public void handleOrder(long orderId, String action) {
        try {
            if (OrderStateMachine.AUTO_CANCEL_UNPAID.equals(action) && compensatePaid(orderId)) {
                return;
            }
            tx.executeWithoutResult(s -> process(orderId, action));
        } catch (BizException e) {
            // 与用户操作并发导致状态已变化，忽略
            if (e.getErrorCode() != ErrorCode.CONFLICT && e.getErrorCode() != ErrorCode.ORDER_STATE_INVALID) {
                throw e;
            }
        }
    }

    private void process(long orderId, String action) {
        TradeOrder o = orderMapper.selectById(orderId);
        if (o == null) {
            return;
        }
        OrderDtos.Deadline d = flow.deadline(o);
        if (d == null || !d.action().equals(action) || LocalDateTime.now().plusSeconds(2).isBefore(d.at())) {
            return;
        }
        switch (action) {
            case OrderStateMachine.AUTO_CANCEL_UNCONFIRMED ->
                    flow.cancel(o, action, Operator.SYSTEM, null, "卖家超时未确认");
            case OrderStateMachine.AUTO_CANCEL_UNPAID -> flow.cancel(o, action, Operator.SYSTEM, null, "超时未付款");
            case OrderStateMachine.AUTO_COMPLETE, OrderStateMachine.AUTO_CONFIRM ->
                    flow.finish(o, action, Operator.SYSTEM, null);
            default -> log.warn("未知的超时动作 {}", action);
        }
    }

    /** 关单前先查一次渠道：支付成功但通知丢失时按已支付处理 */
    private boolean compensatePaid(long orderId) {
        TradeOrder o = orderMapper.selectById(orderId);
        if (o == null || o.getStatus() != OrderStatus.WAIT_PAY) {
            return false;
        }
        Payment p = paymentMapper.selectOne(new LambdaQueryWrapper<Payment>().eq(Payment::getOrderId, orderId)
                .eq(Payment::getStatus, PaymentService.UNPAID).orderByDesc(Payment::getId).last("LIMIT 1"));
        return p != null && paymentService.compensate(p);
    }
}
