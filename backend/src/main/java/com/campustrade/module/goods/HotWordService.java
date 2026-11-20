package com.campustrade.module.goods;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 热搜词：按天一个 ZSet，取近 7 天合并 Top 10 */
@Service
@RequiredArgsConstructor
public class HotWordService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private final StringRedisTemplate redis;

    public void record(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return;
        }
        String key = "hot:" + LocalDate.now().format(DAY);
        redis.opsForZSet().incrementScore(key, keyword.trim().toLowerCase(), 1);
        redis.expire(key, Duration.ofDays(8));
    }

    public List<String> top10() {
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            keys.add("hot:" + LocalDate.now().minusDays(i).format(DAY));
        }
        String dest = "hot:7d";
        redis.opsForZSet().unionAndStore(keys.getFirst(), keys.subList(1, keys.size()), dest);
        redis.expire(dest, Duration.ofMinutes(10));
        Set<String> top = redis.opsForZSet().reverseRange(dest, 0, 9);
        return top == null ? List.of() : List.copyOf(top);
    }
}
