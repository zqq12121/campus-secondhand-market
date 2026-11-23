package com.campustrade.infra;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StockCacheTest {

    private StringRedisTemplate redis;
    private StockCache cache;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        cache = new StockCache(redis);
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    @SuppressWarnings("unchecked")
    private void reserveReturns(Long left) {
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString())).thenReturn(left);
    }

    private static void complete(int status) {
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            if (status == TransactionSynchronization.STATUS_COMMITTED) {
                s.afterCommit();
            }
            s.afterCompletion(status);
        }
    }

    @Test
    void passesQuantityAndDbStockToScript() {
        reserveReturns(1L);
        cache.reserve(5001L, 2, 3);
        verify(redis).execute(eq(StockCache.RESERVE), eq(List.of("goods:stock:5001")), eq("2"), eq("3"),
                eq(String.valueOf(StockCache.TTL.toSeconds())));
    }

    @Test
    void insufficientThrowsStockNotEnough() {
        reserveReturns(-1L);
        assertThatThrownBy(() -> cache.reserve(5001L, 2, 1)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void rollbackEvictsCache() {
        reserveReturns(0L);
        cache.reserve(5001L, 1, 1);
        complete(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(redis).delete("goods:stock:5001");
    }

    @Test
    void commitKeepsCache() {
        reserveReturns(0L);
        cache.reserve(5001L, 1, 1);
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(redis, never()).delete(anyString());
    }

    @Test
    void evictAfterCommitWaitsForCommit() {
        cache.evictAfterCommit(5001L);
        verify(redis, never()).delete(anyString());
        complete(TransactionSynchronization.STATUS_COMMITTED);
        verify(redis).delete("goods:stock:5001");
    }
}
