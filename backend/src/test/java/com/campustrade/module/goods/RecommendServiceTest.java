package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.user.entity.UserBlock;
import com.campustrade.module.user.mapper.UserBlockMapper;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 猜你喜欢：兴趣分类优先、同分类内同校区优先，不够时用热门补齐 */
@ExtendWith(MockitoExtension.class)
class RecommendServiceTest {

    @Mock
    private GoodsMapper goodsMapper;
    @Mock
    private UserBlockMapper blockMapper;
    @Mock
    private UserService userService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ZSetOperations<String, String> zset;
    @InjectMocks
    private RecommendService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(Goods.class, UserBlock.class);
    }

    private static Goods g(long id, long category, long campus) {
        Goods g = new Goods();
        g.setId(id);
        g.setCategoryId(category);
        g.setCampusId(campus);
        g.setSellerId(9L);
        return g;
    }

    @Test
    void viewAndFavoriteAddInterest() {
        when(redis.opsForZSet()).thenReturn(zset);
        service.onViewed(1L, g(1, 202, 1));
        service.onFavorited(1L, g(1, 202, 1));
        verify(zset).incrementScore("rec:interest:1", "202", RecommendService.VIEW_WEIGHT);
        verify(zset).incrementScore("rec:interest:1", "202", RecommendService.FAVORITE_WEIGHT);
        verify(redis, times(2)).expire(eq("rec:interest:1"), any(Duration.class));
    }

    @Test
    void anonymousGetsHotGoods() {
        when(goodsMapper.selectList(any(Wrapper.class))).thenReturn(List.of(g(7, 101, 1), g(8, 202, 2)));
        assertThat(service.recommend(null, 5)).extracting(Goods::getId).containsExactly(7L, 8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interestCategoriesFirstThenSameCampus() {
        User me = new User();
        me.setCampusId(1L);
        when(userService.require(1L)).thenReturn(me);
        when(blockMapper.selectList(any(Wrapper.class))).thenReturn(List.of());
        when(redis.opsForZSet()).thenReturn(zset);
        Set<ZSetOperations.TypedTuple<String>> top = new LinkedHashSet<>();
        top.add(new DefaultTypedTuple<>("202", 9.0));
        top.add(new DefaultTypedTuple<>("101", 3.0));
        when(zset.reverseRangeWithScores("rec:interest:1", 0, RecommendService.TOP_CATEGORIES - 1)).thenReturn(top);
        // 第一次查兴趣分类候选，第二次查热门补齐
        when(goodsMapper.selectList(any(Wrapper.class)))
                .thenReturn(List.of(g(1, 101, 1), g(2, 202, 2), g(3, 202, 1)))
                .thenReturn(List.of(g(9, 501, 1)));
        assertThat(service.recommend(1L, 4)).extracting(Goods::getId).containsExactly(3L, 2L, 1L, 9L);
    }
}
