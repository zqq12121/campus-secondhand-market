package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.TxUtil;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.module.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** 降价提醒（P2）：在售商品降价后，异步通知收藏了它的用户 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceDropService {

    /** 单个商品最多通知的收藏者数 */
    static final int MAX_NOTIFY = 500;
    /** 同一商品 24 小时内只提醒一次，避免卖家反复小幅改价刷屏 */
    private static final Duration COOL_DOWN = Duration.ofHours(24);

    private final FavoriteMapper favoriteMapper;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    /** 编辑后调用；不是降价或商品不在售直接忽略 */
    public void onPriceChanged(Goods g, BigDecimal oldPrice) {
        if (oldPrice == null || g.getPrice().compareTo(oldPrice) >= 0) {
            return;
        }
        TxUtil.afterCommit(() -> CompletableFuture.runAsync(() -> notifyFavoriters(g, oldPrice)).exceptionally(e -> {
            log.error("降价提醒失败 goodsId={}", g.getId(), e);
            return null;
        }));
    }

    /** 返回通知人数 */
    int notifyFavoriters(Goods g, BigDecimal oldPrice) {
        Boolean first = redis.opsForValue().setIfAbsent("price:drop:" + g.getId(), "1", COOL_DOWN);
        if (!Boolean.TRUE.equals(first)) {
            return 0;
        }
        List<Long> users = favoriteMapper.selectList(new LambdaQueryWrapper<Favorite>()
                        .eq(Favorite::getGoodsId, g.getId()).ne(Favorite::getUserId, g.getSellerId())
                        .orderByDesc(Favorite::getId).last("LIMIT " + MAX_NOTIFY))
                .stream().map(Favorite::getUserId).toList();
        for (Long uid : users) {
            notificationService.send(uid, NotifyType.PRICE_DROP, "收藏的商品降价了",
                    "「" + g.getTitle() + "」从 ¥" + oldPrice.toPlainString() + " 降到 ¥" + g.getPrice().toPlainString(),
                    RefType.GOODS, g.getId());
        }
        return users.size();
    }
}
