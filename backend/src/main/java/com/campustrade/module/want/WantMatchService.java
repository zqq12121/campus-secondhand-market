package com.campustrade.module.want;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.TxUtil;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.user.entity.UserBlock;
import com.campustrade.module.user.mapper.UserBlockMapper;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.mapper.WantPostMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 求购匹配（接口文档 5.4 第 4 条、13.3，P1 异步）：商品上架后通知同校、同二级分类、价格落在预算内的进行中求购。
 * 同一求购帖对同一商品只通知一次（商品下架再上架、编辑重审不重复打扰）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WantMatchService {

    /** 单个商品最多通知的求购帖数 */
    static final int MAX_NOTIFY = 100;
    private static final Duration DEDUP_TTL = Duration.ofDays(30);

    private final WantPostMapper wantMapper;
    private final UserBlockMapper blockMapper;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    /** 商品进入在售后调用：事务提交后异步执行，失败只记日志 */
    public void onGoodsOnSale(Goods g) {
        TxUtil.afterCommit(() -> CompletableFuture.runAsync(() -> match(g)).exceptionally(e -> {
            log.error("求购匹配失败 goodsId={}", g.getId(), e);
            return null;
        }));
    }

    /** 返回本次通知的人数 */
    int match(Goods g) {
        List<WantPost> wants = wantMapper.selectList(new LambdaQueryWrapper<WantPost>()
                .eq(WantPost::getStatus, WantStatus.ACTIVE)
                .gt(WantPost::getExpireAt, LocalDateTime.now())
                .eq(WantPost::getSchoolId, g.getSchoolId())
                .eq(WantPost::getCategoryId, g.getCategoryId())
                .ne(WantPost::getUserId, g.getSellerId())
                .and(w -> w.isNull(WantPost::getPriceMin).or().le(WantPost::getPriceMin, g.getPrice()))
                .and(w -> w.isNull(WantPost::getPriceMax).or().ge(WantPost::getPriceMax, g.getPrice()))
                .orderByDesc(WantPost::getId)
                .last("LIMIT " + MAX_NOTIFY));
        if (wants.isEmpty()) {
            return 0;
        }
        // 求购者拉黑了卖家的不通知
        Set<Long> blockers = blockMapper.selectList(new LambdaQueryWrapper<UserBlock>()
                        .eq(UserBlock::getBlockedUserId, g.getSellerId())
                        .in(UserBlock::getUserId, wants.stream().map(WantPost::getUserId).toList()))
                .stream().map(UserBlock::getUserId).collect(Collectors.toSet());
        int sent = 0;
        for (WantPost w : wants) {
            if (blockers.contains(w.getUserId())) {
                continue;
            }
            Boolean first = redis.opsForValue().setIfAbsent("want:match:" + w.getId() + ":" + g.getId(), "1", DEDUP_TTL);
            if (!Boolean.TRUE.equals(first)) {
                continue;
            }
            notificationService.send(w.getUserId(), NotifyType.WANT_MATCH, "有商品匹配你的求购",
                    "「" + g.getTitle() + "」¥" + g.getPrice().toPlainString() + " 符合你的求购「" + w.getTitle() + "」",
                    RefType.GOODS, g.getId());
            sent++;
        }
        log.info("求购匹配 goodsId={} 通知 {} 人", g.getId(), sent);
        return sent;
    }
}
