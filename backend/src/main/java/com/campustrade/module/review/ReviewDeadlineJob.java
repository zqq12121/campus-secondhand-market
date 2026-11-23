package com.campustrade.module.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 定时任务：互评到期补默认好评，每批最多处理 200 条 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewDeadlineJob {

    private static final int BATCH = 200;

    private final TradeOrderMapper orderMapper;
    private final ReviewService reviewService;

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
}
