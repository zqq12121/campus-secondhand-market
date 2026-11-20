package com.campustrade.module.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.TxUtil;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.AfterSale;
import com.campustrade.entity.BalanceLog;
import com.campustrade.entity.Goods;
import com.campustrade.entity.OrderLog;
import com.campustrade.entity.Payment;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.infra.mq.DelayPublisher;
import com.campustrade.infra.mq.DelayType;
import com.campustrade.mapper.AfterSaleMapper;
import com.campustrade.mapper.BalanceLogMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.OrderLogMapper;
import com.campustrade.mapper.PaymentMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.chat.ChatService;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.pay.PaymentGateway;
import com.campustrade.module.user.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 订单状态流转核心：状态机校验 → 乐观锁更新 → 写 order_log → 通知对方 + 会话系统消息 → 投递延时消息。
 * 所有方法都应在调用方事务中执行。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderFlow {

    private static final int BALANCE_RELEASE = 1;

    /** 动作 → [通知标题, 系统消息文案] */
    private static final Map<String, String[]> TEXTS = Map.ofEntries(
            Map.entry(OrderStateMachine.SELLER_CONFIRM, new String[]{"卖家已确认订单", "卖家已确认订单，请按约定时间地点面交"}),
            Map.entry(OrderStateMachine.COMPLETE, new String[]{"交易已完成", "买家已确认完成，交易结束"}),
            Map.entry(OrderStateMachine.AUTO_COMPLETE, new String[]{"交易已完成", "超时未确认，系统自动完成交易"}),
            Map.entry(OrderStateMachine.CANCEL, new String[]{"订单已取消", "订单已取消"}),
            Map.entry(OrderStateMachine.AUTO_CANCEL_UNCONFIRMED, new String[]{"订单已取消", "卖家超时未确认，订单已自动取消"}),
            Map.entry(OrderStateMachine.AUTO_CANCEL_UNPAID, new String[]{"订单已取消", "买家超时未付款，订单已自动取消"}),
            Map.entry(OrderStateMachine.PAY, new String[]{"买家已付款", "买家已付款到平台，等待卖家交货"}),
            Map.entry(OrderStateMachine.DELIVER, new String[]{"卖家已交货", "卖家已交货，请验货后确认收货"}),
            Map.entry(OrderStateMachine.CONFIRM_RECEIPT, new String[]{"交易已完成", "买家已确认收货，货款已结算给卖家"}),
            Map.entry(OrderStateMachine.AUTO_CONFIRM, new String[]{"交易已完成", "超时未确认收货，系统自动确认，货款已结算给卖家"}),
            Map.entry(OrderStateMachine.AUTO_CONFIRM_AFTER_REJECT, new String[]{"交易已完成", "卖家拒绝退款后买家超时未申请平台介入，系统自动确认收货，货款已结算给卖家"}),
            Map.entry(OrderStateMachine.APPLY_CANCEL, new String[]{"买家申请取消订单", "买家申请取消订单并退款"}),
            Map.entry(OrderStateMachine.APPLY_REFUND, new String[]{"买家申请退款", "买家申请退款"}),
            Map.entry(OrderStateMachine.AGREE_AFTER_SALE, new String[]{"退款已完成", "退款已原路退回买家"}),
            Map.entry(OrderStateMachine.REJECT_AFTER_SALE, new String[]{"卖家拒绝了售后申请", "卖家拒绝了售后申请"}),
            Map.entry(OrderStateMachine.APPEAL, new String[]{"买家申请平台介入", "买家已申请平台介入，等待管理员裁决"}),
            Map.entry(OrderStateMachine.WITHDRAW_AFTER_SALE, new String[]{"买家撤销了售后申请", "买家已撤销售后申请"}),
            Map.entry(OrderStateMachine.ARBITRATE_REFUND, new String[]{"平台裁决：退款", "平台裁决全额退款给买家"}),
            Map.entry(OrderStateMachine.ARBITRATE_RELEASE, new String[]{"平台裁决：放款", "平台裁决货款放给卖家"}));

    private final TradeOrderMapper orderMapper;
    private final OrderLogMapper logMapper;
    private final GoodsMapper goodsMapper;
    private final PaymentMapper paymentMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final UserMapper userMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final CreditService creditService;
    private final NotificationService notificationService;
    private final ChatService chatService;
    private final PaymentGateway paymentGateway;
    private final DelayPublisher delayPublisher;
    private final AppProperties props;

    // ------------------------------------------------------------------ 创建

    public void created(TradeOrder o) {
        writeLog(o.getId(), null, o.getStatus(), OrderStateMachine.CREATE, Operator.BUYER, o.getBuyerId(), null);
        boolean face = o.getTradeMode() == TradeMode.FACE;
        String text = face ? "买家已下单，等待卖家确认" : "买家已下单，等待付款";
        notificationService.send(o.getSellerId(), NotifyType.ORDER, "你有新订单",
                "「" + o.getGoodsTitle() + "」" + text, RefType.ORDER, o.getId());
        chatService.sendSystem(o.getBuyerId(), o.getSellerId(), o.getGoodsId(), prefix(o) + text);
        delayPublisher.schedule(face ? DelayType.AUTO_CANCEL_UNCONFIRMED : DelayType.AUTO_CANCEL_UNPAID,
                o.getId(), null);
    }

    // ------------------------------------------------------------------ 通用流转

    /**
     * 校验并流转。target 只在动作有多个目标状态时传；remark 写入日志并拼到系统消息里。
     * 返回流转后的订单（已刷新）。
     */
    public TradeOrder transit(TradeOrder o, String action, int operator, Long operatorId, Integer target,
                              String remark, Consumer<LambdaUpdateWrapper<TradeOrder>> extra) {
        Integer to = OrderStateMachine.next(o.getStatus(), action, operator, target);
        Biz.notNull(to, ErrorCode.ORDER_STATE_INVALID);
        LambdaUpdateWrapper<TradeOrder> w = new LambdaUpdateWrapper<TradeOrder>()
                .eq(TradeOrder::getId, o.getId()).eq(TradeOrder::getStatus, o.getStatus())
                .eq(TradeOrder::getVersion, o.getVersion())
                .set(TradeOrder::getStatus, to).setSql("version = version + 1");
        if (extra != null) {
            extra.accept(w);
        }
        Biz.check(orderMapper.update(w) == 1, ErrorCode.CONFLICT);
        writeLog(o.getId(), o.getStatus(), to, action, operator, operatorId, remark);
        announce(o, action, operator, operatorId, remark);
        return orderMapper.selectById(o.getId());
    }

    public TradeOrder transit(TradeOrder o, String action, int operator, Long operatorId) {
        return transit(o, action, operator, operatorId, null, null, null);
    }

    /** 完成交易：担保订单放款、商品售罄、双方信用 +1、开放互评 */
    public void finish(TradeOrder o, String action, int operator, Long operatorId) {
        LocalDateTime now = LocalDateTime.now();
        TradeOrder done = transit(o, action, operator, operatorId, null, null, w -> w.set(TradeOrder::getFinishedAt, now)
                .set(TradeOrder::getReviewDeadline, now.plusDays(props.rules().reviewDays())));
        if (done.getTradeMode() == TradeMode.GUARANTEE) {
            releaseToSeller(done);
        }
        Goods g = goodsMapper.selectById(o.getGoodsId());
        if (g != null && g.getStock() == 0 && g.getStatus() == GoodsStatus.TRADING && !hasActiveOrder(g.getId())) {
            goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, g.getId())
                    .eq(Goods::getStatus, GoodsStatus.TRADING).set(Goods::getStatus, GoodsStatus.SOLD));
        }
        creditService.addWithMonthlyCap(o.getBuyerId(), CreditReason.TRADE_DONE, o.getId(), null);
        creditService.addWithMonthlyCap(o.getSellerId(), CreditReason.TRADE_DONE, o.getId(), null);
    }

    /** 取消：回补库存、关闭支付单、确认后取消扣信用 */
    public void cancel(TradeOrder o, String action, int operator, Long operatorId, String reason) {
        int from = o.getStatus();
        transit(o, action, operator, operatorId, null, reason, w -> w.set(TradeOrder::getCancelReason, reason)
                .set(TradeOrder::getCancelBy, operator).set(TradeOrder::getCanceledAt, LocalDateTime.now()));
        restoreStock(o);
        if (from == OrderStatus.WAIT_PAY) {
            closePayment(o.getId());
        }
        if (from == OrderStatus.WAIT_MEET && operatorId != null) {
            creditService.change(operatorId, -3, CreditReason.DEFAULT_CANCEL, o.getId(), "确认后取消订单（爽约）", null);
        }
    }

    /** 退款完成：订单 → 已退款，回补库存 */
    public void refunded(TradeOrder o, String action, int operator, Long operatorId) {
        transit(o, action, operator, operatorId, null, null, w -> w.set(TradeOrder::getCanceledAt, LocalDateTime.now())
                .set(TradeOrder::getCancelBy, operator));
        restoreStock(o);
    }

    // ------------------------------------------------------------------ 截止时间

    /** 当前状态下的自动处理节点；售后中不计时（返回 null） */
    public OrderDtos.Deadline deadline(TradeOrder o) {
        AppProperties.Rules r = props.rules();
        return switch (o.getStatus()) {
            case OrderStatus.WAIT_SELLER_CONFIRM -> new OrderDtos.Deadline(OrderStateMachine.AUTO_CANCEL_UNCONFIRMED,
                    o.getCreatedAt().plus(r.faceConfirmTimeout()));
            case OrderStatus.WAIT_MEET -> new OrderDtos.Deadline(OrderStateMachine.AUTO_COMPLETE,
                    o.getSellerConfirmedAt().plus(r.faceAutoComplete()));
            case OrderStatus.WAIT_PAY -> new OrderDtos.Deadline(OrderStateMachine.AUTO_CANCEL_UNPAID,
                    payExpireAt(o));
            case OrderStatus.WAIT_RECEIVE -> new OrderDtos.Deadline(OrderStateMachine.AUTO_CONFIRM, autoConfirmAt(o));
            default -> null;
        };
    }

    public LocalDateTime payExpireAt(TradeOrder o) {
        return o.getCreatedAt().plus(props.rules().payTimeout());
    }

    /** 自动确认收货时间 = 交货时间 + 7 天 + 历次已撤销的收货前退款持续时长（售后期间暂停计时） */
    public LocalDateTime autoConfirmAt(TradeOrder o) {
        Duration paused = Duration.ZERO;
        for (AfterSale a : afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getOrderId, o.getId()).eq(AfterSale::getType, 2).isNotNull(AfterSale::getClosedAt))) {
            paused = paused.plus(Duration.between(a.getCreatedAt(), a.getClosedAt()));
        }
        return o.getDeliveredAt().plus(props.rules().autoConfirm()).plus(paused);
    }

    public boolean hasActiveOrder(long goodsId) {
        return orderMapper.exists(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getGoodsId, goodsId)
                .lt(TradeOrder::getStatus, OrderStatus.FINISHED));
    }

    // ------------------------------------------------------------------ 内部

    private void releaseToSeller(TradeOrder o) {
        BalanceLog bl = new BalanceLog();
        bl.setUserId(o.getSellerId());
        bl.setAmount(o.getTotalAmount());
        bl.setType(BALANCE_RELEASE);
        bl.setOrderId(o.getId());
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, o.getSellerId())
                .setSql("balance = balance + " + o.getTotalAmount().toPlainString()));
        bl.setBalanceAfter(userMapper.selectById(o.getSellerId()).getBalance());
        try {
            balanceLogMapper.insert(bl);
        } catch (DuplicateKeyException e) {
            // 唯一键 (order_id, type) 保证同一订单只放款一次
            throw new com.campustrade.common.BizException(ErrorCode.CONFLICT, "订单已放款");
        }
    }

    private void restoreStock(TradeOrder o) {
        goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, o.getGoodsId())
                .setSql("stock = stock + " + o.getQuantity() + ", version = version + 1"));
        goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, o.getGoodsId())
                .eq(Goods::getStatus, GoodsStatus.TRADING).set(Goods::getStatus, GoodsStatus.ON_SALE));
    }

    private void closePayment(long orderId) {
        List<Payment> open = paymentMapper.selectList(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getOrderId, orderId).eq(Payment::getStatus, 0));
        for (Payment p : open) {
            paymentMapper.update(new LambdaUpdateWrapper<Payment>().eq(Payment::getId, p.getId())
                    .eq(Payment::getStatus, 0).set(Payment::getStatus, 2));
            TxUtil.afterCommit(() -> {
                try {
                    paymentGateway.close(p.getOutTradeNo());
                } catch (RuntimeException e) {
                    log.warn("关闭支付单失败 outTradeNo={}", p.getOutTradeNo(), e);
                }
            });
        }
    }

    /** 通知对方（系统/管理员操作则通知双方）+ 会话系统消息 */
    private void announce(TradeOrder o, String action, int operator, Long operatorId, String remark) {
        String[] t = TEXTS.get(action);
        if (t == null) {
            return;
        }
        String text = remark == null || remark.isBlank() ? t[1] : t[1] + "：" + remark;
        List<Long> targets = switch (operator) {
            case Operator.BUYER -> List.of(o.getSellerId());
            case Operator.SELLER -> List.of(o.getBuyerId());
            default -> List.of(o.getBuyerId(), o.getSellerId());
        };
        for (long uid : targets) {
            notificationService.send(uid, NotifyType.ORDER, t[0], "「" + o.getGoodsTitle() + "」" + text,
                    RefType.ORDER, o.getId());
        }
        chatService.sendSystem(o.getBuyerId(), o.getSellerId(), null, prefix(o) + text);
    }

    private static String prefix(TradeOrder o) {
        return "订单 " + o.getOrderNo() + "：";
    }

    public void writeLog(long orderId, Integer from, int to, String action, int operator, Long operatorId,
                         String remark) {
        OrderLog log = new OrderLog();
        log.setOrderId(orderId);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setAction(action);
        log.setOperatorType(operator);
        log.setOperatorId(operatorId);
        log.setRemark(remark);
        logMapper.insert(log);
    }
}
