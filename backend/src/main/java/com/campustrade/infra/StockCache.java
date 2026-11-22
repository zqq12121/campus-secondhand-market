package com.campustrade.infra;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;

/**
 * 下单时的 Redis 库存预扣：库存不足的请求在 Redis 就被挡掉，不再打到数据库。
 * 数据库乐观锁仍是最终依据，Redis 只做前置过滤；库存在下单以外的地方变化（回补、编辑）时直接删缓存，下次下单从库里重新加载。
 */
@Component
@RequiredArgsConstructor
public class StockCache {

    private static final Duration TTL = Duration.ofHours(1);

    /** 返回 -2：缓存不存在；-1：库存不足；否则为扣减后的剩余库存 */
    private static final DefaultRedisScript<Long> DEDUCT = new DefaultRedisScript<>("""
            local v = redis.call('GET', KEYS[1])
            if not v then return -2 end
            v = tonumber(v)
            local n = tonumber(ARGV[1])
            if v < n then return -1 end
            redis.call('DECRBY', KEYS[1], n)
            return v - n
            """, Long.class);

    private static final DefaultRedisScript<Long> GIVE_BACK = new DefaultRedisScript<>("""
            if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCRBY', KEYS[1], ARGV[1]) end
            return -2
            """, Long.class);

    private final StringRedisTemplate redis;

    /**
     * 预扣库存。dbStock 返回 null 表示不走缓存（商品不存在或不在售），由数据库校验给出具体错误。
     *
     * @return false 表示库存不足
     */
    public boolean tryDeduct(long goodsId, int quantity, Supplier<Integer> dbStock) {
        String key = key(goodsId);
        Long r = redis.execute(DEDUCT, List.of(key), String.valueOf(quantity));
        if (r != null && r == -2) {
            Integer stock = dbStock.get();
            if (stock == null) {
                return true;
            }
            redis.opsForValue().setIfAbsent(key, String.valueOf(stock), TTL);
            r = redis.execute(DEDUCT, List.of(key), String.valueOf(quantity));
        }
        return r == null || r != -1;
    }

    /** 下单失败回滚时归还预扣的库存；缓存已被删除则什么都不做 */
    public void giveBack(long goodsId, int quantity) {
        redis.execute(GIVE_BACK, List.of(key(goodsId)), String.valueOf(quantity));
    }

    /** 库存在下单以外的地方变化时调用 */
    public void evict(long goodsId) {
        redis.delete(key(goodsId));
    }

    private static String key(long goodsId) {
        return "goods:stock:" + goodsId;
    }
}
