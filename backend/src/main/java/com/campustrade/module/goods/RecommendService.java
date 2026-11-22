package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.entity.Goods;
import com.campustrade.entity.UserBlock;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.UserBlockMapper;
import com.campustrade.module.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 猜你喜欢（P2）：按用户近 30 天对各二级分类的兴趣分（浏览 +1、收藏 +3）推荐在售商品，同校区优先；
 * 未登录或没有行为数据时退化为近 30 天热门（浏览量）。
 */
@Service
@RequiredArgsConstructor
public class RecommendService {

    static final int VIEW_WEIGHT = 1;
    static final int FAVORITE_WEIGHT = 3;
    static final int TOP_CATEGORIES = 3;
    static final int MAX_SIZE = 20;
    private static final Duration PREF_TTL = Duration.ofDays(30);

    private final GoodsMapper goodsMapper;
    private final UserBlockMapper blockMapper;
    private final UserService userService;
    private final StringRedisTemplate redis;

    public void onViewed(long userId, Goods g) {
        addInterest(userId, g.getCategoryId(), VIEW_WEIGHT);
    }

    public void onFavorited(long userId, Goods g) {
        addInterest(userId, g.getCategoryId(), FAVORITE_WEIGHT);
    }

    /** 返回推荐的商品（已过滤自己发布的、拉黑了我的卖家），最多 size 条 */
    public List<Goods> recommend(Long userId, int size) {
        int n = Math.max(1, Math.min(size, MAX_SIZE));
        Map<Long, Goods> picked = new LinkedHashMap<>();
        Set<Long> blockedBy = Set.of();
        if (userId != null) {
            blockedBy = Set.copyOf(blockMapper.selectList(new LambdaQueryWrapper<UserBlock>()
                    .eq(UserBlock::getBlockedUserId, userId)).stream().map(UserBlock::getUserId).toList());
            Long myCampus = userService.require(userId).getCampusId();
            List<Long> categories = topCategories(userId);
            if (!categories.isEmpty()) {
                List<Goods> candidates = goodsMapper.selectList(onSale(userId, blockedBy)
                        .in(Goods::getCategoryId, categories).orderByDesc(Goods::getPublishedAt)
                        .last("LIMIT " + n * 3));
                // 兴趣越高的分类越靠前；同分类内同校区优先，再按上架时间
                candidates.stream().sorted(Comparator
                                .comparingInt((Goods g) -> categories.indexOf(g.getCategoryId()))
                                .thenComparing(g -> !g.getCampusId().equals(myCampus)))
                        .limit(n).forEach(g -> picked.put(g.getId(), g));
            }
        }
        if (picked.size() < n) {
            LambdaQueryWrapper<Goods> hot = onSale(userId, blockedBy)
                    .ge(Goods::getPublishedAt, LocalDateTime.now().minusDays(30))
                    .notIn(!picked.isEmpty(), Goods::getId, picked.keySet())
                    .orderByDesc(Goods::getViewCount).orderByDesc(Goods::getId)
                    .last("LIMIT " + (n - picked.size()));
            goodsMapper.selectList(hot).forEach(g -> picked.put(g.getId(), g));
        }
        return new ArrayList<>(picked.values());
    }

    /** 兴趣分最高的几个分类，按分数降序 */
    List<Long> topCategories(long userId) {
        Set<ZSetOperations.TypedTuple<String>> top = redis.opsForZSet()
                .reverseRangeWithScores(key(userId), 0, TOP_CATEGORIES - 1);
        if (top == null) {
            return List.of();
        }
        return top.stream().map(ZSetOperations.TypedTuple::getValue).filter(v -> v != null).map(Long::valueOf).toList();
    }

    private LambdaQueryWrapper<Goods> onSale(Long userId, Set<Long> blockedBy) {
        return new LambdaQueryWrapper<Goods>().eq(Goods::getStatus, GoodsStatus.ON_SALE)
                .ne(userId != null, Goods::getSellerId, userId)
                .notIn(!blockedBy.isEmpty(), Goods::getSellerId, blockedBy);
    }

    private void addInterest(long userId, Long categoryId, int weight) {
        if (categoryId == null) {
            return;
        }
        String key = key(userId);
        redis.opsForZSet().incrementScore(key, String.valueOf(categoryId), weight);
        redis.expire(key, PREF_TTL);
    }

    private static String key(long userId) {
        return "rec:interest:" + userId;
    }
}
