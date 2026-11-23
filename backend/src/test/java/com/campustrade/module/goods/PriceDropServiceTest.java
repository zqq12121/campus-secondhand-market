package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 降价提醒：只在降价时通知收藏者，同一商品 24 小时内只提醒一次 */
@ExtendWith(MockitoExtension.class)
class PriceDropServiceTest {

    @Mock
    private FavoriteMapper favoriteMapper;
    @Mock
    private NotificationService notificationService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @InjectMocks
    private PriceDropService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(Favorite.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    private static Goods goods(String price) {
        Goods g = new Goods();
        g.setId(301L);
        g.setSellerId(2048L);
        g.setTitle("显示器");
        g.setPrice(new BigDecimal(price));
        return g;
    }

    private static Favorite fav(long userId) {
        Favorite f = new Favorite();
        f.setUserId(userId);
        f.setGoodsId(301L);
        return f;
    }

    @Test
    void priceUpOrSameIsIgnored() {
        service.onPriceChanged(goods("300.00"), new BigDecimal("280.00"));
        service.onPriceChanged(goods("300.00"), new BigDecimal("300.00"));
        service.onPriceChanged(goods("300.00"), null);
        verifyNoInteractions(redis, favoriteMapper, notificationService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dropNotifiesFavoriters() {
        when(ops.setIfAbsent(eq("price:drop:301"), eq("1"), any(Duration.class))).thenReturn(true);
        when(favoriteMapper.selectList(any(Wrapper.class))).thenReturn(List.of(fav(1L), fav(2L)));
        assertThat(service.notifyFavoriters(goods("250.00"), new BigDecimal("300.00"))).isEqualTo(2);
        verify(notificationService).send(eq(1L), eq(NotifyType.PRICE_DROP), anyString(), contains("降到 ¥250.00"),
                eq(RefType.GOODS), eq(301L));
    }

    @Test
    void coolDownSkipsRepeatedDrops() {
        when(ops.setIfAbsent(eq("price:drop:301"), eq("1"), any(Duration.class))).thenReturn(false);
        assertThat(service.notifyFavoriters(goods("250.00"), new BigDecimal("300.00"))).isZero();
        verify(notificationService, never()).send(anyLong(), anyInt(), anyString(), anyString(), any(), any());
    }
}
