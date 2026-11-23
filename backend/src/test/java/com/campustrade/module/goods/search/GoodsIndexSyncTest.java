package com.campustrade.module.goods.search;

import com.campustrade.entity.Goods;
import com.campustrade.mapper.GoodsMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 检索索引增量同步：游标读写、分批拉取直到没有新变更 */
class GoodsIndexSyncTest {

    private static final LocalDateTime T = LocalDateTime.of(2026, 11, 22, 10, 0, 5);

    private GoodsSearchIndex index;
    private GoodsMapper goodsMapper;
    private ValueOperations<String, String> ops;
    private GoodsIndexSync sync;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        index = mock(GoodsSearchIndex.class);
        goodsMapper = mock(GoodsMapper.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        sync = new GoodsIndexSync(index, goodsMapper, redis);
    }

    @Test
    void cursorRoundTrip() {
        GoodsIndexSync.Cursor c = new GoodsIndexSync.Cursor(T, 42);
        assertThat(GoodsIndexSync.Cursor.parse(c.format())).isEqualTo(c);
        assertThat(GoodsIndexSync.Cursor.parse(null).id()).isZero();
    }

    private static List<Goods> batch(int n, long firstId) {
        List<Goods> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Goods g = new Goods();
            g.setId(firstId + i);
            g.setUpdatedAt(T);
            list.add(g);
        }
        return list;
    }

    @Test
    void pullsBatchesUntilCaughtUp() throws Exception {
        when(ops.get(GoodsIndexSync.CURSOR_KEY)).thenReturn(null);
        when(goodsMapper.selectChangedSince(any(), anyLong(), anyInt()))
                .thenReturn(batch(500, 1))
                .thenReturn(batch(3, 501));
        assertThat(sync.syncOnce()).isEqualTo(503);
        // 第二批从上一批最后一条之后继续，游标落在最后一条
        verify(goodsMapper).selectChangedSince(eq(T), eq(500L), eq(500));
        verify(ops).set(GoodsIndexSync.CURSOR_KEY, new GoodsIndexSync.Cursor(T, 503).format());
    }

    @Test
    void nothingChanged() throws Exception {
        when(ops.get(GoodsIndexSync.CURSOR_KEY)).thenReturn(new GoodsIndexSync.Cursor(T, 7).format());
        when(goodsMapper.selectChangedSince(T, 7L, 500)).thenReturn(List.of());
        assertThat(sync.syncOnce()).isZero();
        verify(index, never()).sync(any());
    }
}
