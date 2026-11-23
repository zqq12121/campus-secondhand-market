package com.campustrade.module.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.config.AppProperties;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.aftersale.entity.AfterSale;
import com.campustrade.module.aftersale.mapper.AfterSaleMapper;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 定时任务：超时订单 / 售后的兜底扫描与退款重试。主路径是 RabbitMQ 延时队列（见 TimeoutListener），
 * 这里每分钟扫一次，补处理延时消息丢失或 MQ 不可用时漏掉的记录。每批最多处理 200 条，单条失败不影响其他。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private static final int BATCH = 200;

    private final TradeOrderMapper orderMapper;
    private final TimeoutService timeoutService;
    private final AfterSaleService afterSaleService;
    private final AfterSaleMapper afterSaleMapper;
    private final AppProperties props;

    /** 兜底扫描：延时消息丢失或 MQ 不可用时，由这里补处理超时订单与售后 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void orderTimeouts() {
        AppProperties.Rules r = props.rules();
        LocalDateTime now = LocalDateTime.now();
        scan(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getStatus, OrderStatus.WAIT_SELLER_CONFIRM)
                .lt(TradeOrder::getCreatedAt, now.minus(r.faceConfirmTimeout())), OrderStateMachine.AUTO_CANCEL_UNCONFIRMED);
        scan(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getStatus, OrderStatus.WAIT_MEET)
                .lt(TradeOrder::getSellerConfirmedAt, now.minus(r.faceAutoComplete())), OrderStateMachine.AUTO_COMPLETE);
        scan(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getStatus, OrderStatus.WAIT_PAY)
                .lt(TradeOrder::getCreatedAt, now.minus(r.payTimeout())), OrderStateMachine.AUTO_CANCEL_UNPAID);
        // 售后暂停计时只会推迟截止时间，按交货时间粗筛后由 TimeoutService 精确判断
        scan(new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getStatus, OrderStatus.WAIT_RECEIVE)
                .lt(TradeOrder::getDeliveredAt, now.minus(r.autoConfirm())), OrderStateMachine.AUTO_CONFIRM);

        for (AfterSale a : afterSaleMapper.selectPage(Page.of(1, BATCH, false), new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getStatus, AfterSaleService.PENDING).lt(AfterSale::getSellerDeadline, now)).getRecords()) {
            run("售后超时自动同意 afterSaleId=" + a.getId(), () -> afterSaleService.autoAgree(a.getId()));
        }
        // 收货前退款被拒后买家超时未处理：拒绝后记录不再更新，updated_at 即拒绝时间
        for (AfterSale a : afterSaleMapper.selectPage(Page.of(1, BATCH, false), new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getType, AfterSaleService.TYPE_REFUND).eq(AfterSale::getStatus, AfterSaleService.REJECTED)
                .isNull(AfterSale::getClosedAt).lt(AfterSale::getUpdatedAt, now.minus(r.afterSaleAppealTimeout())))
                .getRecords()) {
            run("退款被拒超时自动确认收货 afterSaleId=" + a.getId(), () -> afterSaleService.autoConfirmRejected(a.getId()));
        }
        for (AfterSale a : afterSaleMapper.selectPage(Page.of(1, BATCH, false), new LambdaQueryWrapper<AfterSale>()
                .in(AfterSale::getStatus, AfterSaleService.AGREED, AfterSaleService.ARBITRATED_REFUND)
                .isNull(AfterSale::getRefundedAt).lt(AfterSale::getUpdatedAt, now.minusMinutes(1))).getRecords()) {
            run("退款重试 afterSaleId=" + a.getId(), () -> afterSaleService.retryRefund(a));
        }
    }

    private void scan(LambdaQueryWrapper<TradeOrder> q, String action) {
        for (TradeOrder o : orderMapper.selectPage(Page.of(1, BATCH, false), q).getRecords()) {
            run("订单超时处理 orderId=" + o.getId() + " action=" + action,
                    () -> timeoutService.handleOrder(o.getId(), action));
        }
    }

    private static void run(String what, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("{} 失败", what, e);
        }
    }
}
