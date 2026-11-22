package com.campustrade.module.goods.search;

import com.campustrade.entity.Goods;
import com.campustrade.mapper.GoodsMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * MySQL → ES 增量同步：按 (updated_at, id) 游标拉取变更商品，游标存 Redis。
 * 不在业务代码里埋点：商品的上下架、改价、库存、删除都会更新 updated_at，统一由这里补到索引。
 * 索引首次创建（或被删）时游标归零，等同全量重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.search.engine", havingValue = "es")
public class GoodsIndexSync {

    static final String CURSOR_KEY = "es:goods:cursor";
    private static final int BATCH = 500;
    private static final LocalDateTime EPOCH = LocalDateTime.of(2000, 1, 1, 0, 0);

    private final GoodsSearchIndex index;
    private final GoodsMapper goodsMapper;
    private final StringRedisTemplate redis;

    @Scheduled(initialDelayString = "${app.search.sync-initial-delay:5000}",
            fixedDelayString = "${app.search.sync-interval:30000}")
    public void run() {
        try {
            if (index.ensureIndex()) {
                redis.delete(CURSOR_KEY);
                log.info("已创建商品检索索引，开始全量同步");
            }
            int n = syncOnce();
            if (n > 0) {
                log.info("商品检索索引同步 {} 条", n);
            }
        } catch (Exception e) {
            // ES 不可用时搜索自动退回 MySQL，这里只记日志，下次再试
            log.warn("商品检索索引同步失败：{}", e.getMessage());
        }
    }

    /** 同步到没有新变更为止，返回处理条数 */
    int syncOnce() throws java.io.IOException {
        Cursor c = Cursor.parse(redis.opsForValue().get(CURSOR_KEY));
        int total = 0;
        while (true) {
            List<Goods> batch = goodsMapper.selectChangedSince(c.updatedAt(), c.id(), BATCH);
            if (batch.isEmpty()) {
                return total;
            }
            index.sync(batch);
            Goods last = batch.getLast();
            c = new Cursor(last.getUpdatedAt(), last.getId());
            redis.opsForValue().set(CURSOR_KEY, c.format());
            total += batch.size();
            if (batch.size() < BATCH) {
                return total;
            }
        }
    }

    record Cursor(LocalDateTime updatedAt, long id) {

        static Cursor parse(String s) {
            if (s == null) {
                return new Cursor(EPOCH, 0);
            }
            int i = s.lastIndexOf('|');
            return new Cursor(LocalDateTime.parse(s.substring(0, i)), Long.parseLong(s.substring(i + 1)));
        }

        String format() {
            return updatedAt + "|" + id;
        }
    }
}
