package com.campustrade.infra;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.TxUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;

/**
 * 下单时的 Redis 库存预扣，挡住超出库存的并发请求，最终仍以 goods.version 乐观锁为准。
 * 缓存不存在时用数据库库存初始化；事务回滚、库存回补、卖家改库存时删除缓存，下次下单重新从库里加载。
 */
@Component
@RequiredArgsConstructor
public class StockCache {

    static final Duration TTL = Duration.ofMinutes(30);

    /** 返回 -1 表示库存不足，否则返回预扣后的剩余库存 */
    static final DefaultRedisScript<Long> RESERVE = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 0 then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
            end
            local qty = tonumber(ARGV[1])
            if tonumber(redis.call('GET', KEYS[1])) < qty then
              return -1
            end
            return redis.call('DECRBY', KEYS[1], qty)
            """, Long.class);

    private final StringRedisTemplate redis;

    static String key(long goodsId) {
        return "goods:stock:" + goodsId;
    }

    /** 预扣库存；不足时抛 5002。所在事务回滚时删除缓存 */
    public void reserve(long goodsId, int quantity, int dbStock) {
        Long left = redis.execute(RESERVE, List.of(key(goodsId)),
                String.valueOf(quantity), String.valueOf(dbStock), String.valueOf(TTL.toSeconds()));
        if (left == null || left < 0) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        evict(goodsId);
                    }
                }
            });
        }
    }

    /** 数据库库存在本事务内被改动（回补、编辑），提交后删除缓存 */
    public void evictAfterCommit(long goodsId) {
        TxUtil.afterCommit(() -> evict(goodsId));
    }

    private void evict(long goodsId) {
        redis.delete(key(goodsId));
    }
}
