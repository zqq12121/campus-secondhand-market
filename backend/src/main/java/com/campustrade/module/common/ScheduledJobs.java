package com.campustrade.module.common;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.Goods;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.entity.AfterSale;
import com.campustrade.mapper.AfterSaleMapper;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.order.TimeoutService;
import com.campustrade.module.order.OrderStateMachine;
import com.campustrade.module.review.ReviewService;
import com.campustrade.module.want.WantService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 定时任务：超时订单 / 售后的兜底扫描（主路径为 RabbitMQ 延时队列）、退款重试、默认好评、商品过期、求购过期、处罚到期。
 * 每批最多处理 200 条，单条失败不影响其他。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduledJobs {

    private static final int BATCH = 200;

    private final TradeOrderMapper orderMapper;
    private final GoodsMapper goodsMapper;
    private final UserMapper userMapper;
    private final TimeoutService timeoutService;
    private final AfterSaleService afterSaleService;
    private final AfterSaleMapper afterSaleMapper;
    private final ReviewService reviewService;
    private final NotificationService notificationService;
    private final WantService wantService;
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

    /** 互评到期：补默认好评并公开 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void reviewDeadlines() {
        List<TradeOrder> orders = orderMapper.selectPage(Page.of(1, BATCH, false),
                new LambdaQueryWrapper<TradeOrder>().eq(TradeOrder::getStatus, OrderStatus.FINISHED)
                        .lt(TradeOrder::getReviewDeadline, LocalDateTime.now())
                        .notExists("SELECT 1 FROM review r WHERE r.order_id = trade_order.id AND r.visible = 1"))
                .getRecords();
        for (TradeOrder o : orders) {
            try {
                reviewService.closeExpired(o);
            } catch (RuntimeException e) {
                log.error("默认好评处理失败 orderId={}", o.getId(), e);
            }
        }
    }

    /** 在售超 60 天自动下架并通知卖家 */
    @Scheduled(cron = "0 10 * * * *")
    public void expireGoods() {
        LocalDateTime before = LocalDateTime.now().minusDays(props.rules().goodsExpireDays());
        List<Goods> list = goodsMapper.selectPage(Page.of(1, BATCH, false), new LambdaQueryWrapper<Goods>()
                .eq(Goods::getStatus, GoodsStatus.ON_SALE).lt(Goods::getPublishedAt, before)).getRecords();
        for (Goods g : list) {
            int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, g.getId())
                    .eq(Goods::getStatus, GoodsStatus.ON_SALE).set(Goods::getStatus, GoodsStatus.OFF_SHELF));
            if (n == 1) {
                notificationService.send(g.getSellerId(), NotifyType.AUDIT, "商品已自动下架",
                        "「" + g.getTitle() + "」在售已超过 " + props.rules().goodsExpireDays() + " 天，可一键重新上架",
                        RefType.GOODS, g.getId());
            }
        }
    }

    /** 求购帖到期：进行中 → 已过期 */
    @Scheduled(cron = "0 5 * * * *")
    public void expireWants() {
        run("求购帖过期", () -> wantService.expire(null));
    }

    /** 禁言 / 封禁到期自动恢复 */
    @Scheduled(fixedDelay = 300_000, initialDelay = 90_000)
    public void liftPunishments() {
        userMapper.update(new LambdaUpdateWrapper<User>()
                .in(User::getStatus, UserStatus.MUTED, UserStatus.BANNED)
                .isNotNull(User::getBanUntil).lt(User::getBanUntil, LocalDateTime.now())
                .set(User::getStatus, UserStatus.NORMAL).set(User::getBanUntil, null));
    }
}
