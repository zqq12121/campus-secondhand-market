package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.TxUtil;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.user.entity.UserFollow;
import com.campustrade.module.user.mapper.UserFollowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** 关注卖家（P2）：关注 / 取关 / 我的关注，卖家商品上架时异步通知粉丝 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    /** 单个商品最多通知的粉丝数 */
    static final int MAX_NOTIFY = 500;
    private static final Duration DEDUP_TTL = Duration.ofDays(30);

    private final UserFollowMapper followMapper;
    private final UserService userService;
    private final NotificationService notificationService;
    private final StringRedisTemplate redis;

    public record FollowVO(Long userId, String nickname, String avatarUrl, String creditLevel, int onSaleCount,
                           LocalDateTime followedAt) {
    }

    public void follow(long userId, long sellerId) {
        Biz.check(userId != sellerId, ErrorCode.BAD_REQUEST, "不能关注自己");
        userService.require(sellerId);
        UserFollow f = new UserFollow();
        f.setUserId(userId);
        f.setFolloweeId(sellerId);
        try {
            followMapper.insert(f);
        } catch (DuplicateKeyException ignored) {
            // 已关注，幂等
        }
    }

    public void unfollow(long userId, long sellerId) {
        followMapper.delete(new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId).eq(UserFollow::getFolloweeId, sellerId));
    }

    public PageResult<FollowVO> mine(long userId, PageQuery pq) {
        Page<UserFollow> page = followMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<UserFollow>()
                .eq(UserFollow::getUserId, userId).orderByDesc(UserFollow::getId));
        Map<Long, User> users = userService.mapByIds(page.getRecords().stream().map(UserFollow::getFolloweeId).toList());
        return PageResult.of(page, f -> {
            User u = users.get(f.getFolloweeId());
            return new FollowVO(f.getFolloweeId(), u == null ? null : u.getNickname(), u == null ? null : u.getAvatarUrl(),
                    u == null ? null : CreditLevel.of(u.getCreditScore()).name(),
                    u == null ? 0 : userService.onSaleCount(u.getId()), f.getCreatedAt());
        });
    }

    /** 商品进入在售后调用：事务提交后异步通知粉丝，失败只记日志 */
    public void onGoodsOnSale(Goods g) {
        TxUtil.afterCommit(() -> CompletableFuture.runAsync(() -> notifyFollowers(g)).exceptionally(e -> {
            log.error("关注上新通知失败 goodsId={}", g.getId(), e);
            return null;
        }));
    }

    /** 同一商品只通知一次（下架再上架、编辑重审不重复打扰）；返回通知人数 */
    int notifyFollowers(Goods g) {
        Boolean first = redis.opsForValue().setIfAbsent("follow:notify:" + g.getId(), "1", DEDUP_TTL);
        if (!Boolean.TRUE.equals(first)) {
            return 0;
        }
        List<Long> fans = followMapper.selectList(new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getFolloweeId, g.getSellerId()).orderByDesc(UserFollow::getId)
                        .last("LIMIT " + MAX_NOTIFY))
                .stream().map(UserFollow::getUserId).toList();
        String seller = userService.require(g.getSellerId()).getNickname();
        for (Long fan : fans) {
            notificationService.send(fan, NotifyType.FOLLOW, "关注的卖家上新了",
                    "你关注的「" + seller + "」发布了新商品：" + g.getTitle() + "，¥" + g.getPrice().toPlainString(),
                    RefType.GOODS, g.getId());
        }
        return fans.size();
    }
}
