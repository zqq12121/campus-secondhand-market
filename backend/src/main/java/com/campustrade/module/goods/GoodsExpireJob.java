package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.config.AppProperties;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/** 定时任务：在售超期的商品自动下架并通知卖家，每批最多处理 200 条 */
@Component
@RequiredArgsConstructor
public class GoodsExpireJob {

    private static final int BATCH = 200;

    private final GoodsMapper goodsMapper;
    private final NotificationService notificationService;
    private final AppProperties props;

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
}
