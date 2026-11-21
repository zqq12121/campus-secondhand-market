package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.entity.AfterSale;
import com.campustrade.entity.Category;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Report;
import com.campustrade.entity.StatDaily;
import com.campustrade.mapper.AfterSaleMapper;
import com.campustrade.mapper.CategoryMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReportMapper;
import com.campustrade.mapper.StatDailyMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.aftersale.AfterSaleService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 数据看板（接口文档 13.2）：today/trend/total 读 stat_daily，pending 实时 count */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private static final String SHARE_CACHE = "dashboard:category-share";
    private static final int MAX_DAYS = 90;
    private static final int MAX_BACKFILL_DAYS = 3 * 365;

    private final StatDailyMapper statMapper;
    private final UserMapper userMapper;
    private final GoodsMapper goodsMapper;
    private final CategoryMapper categoryMapper;
    private final ReportMapper reportMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public record Today(int newUsers, int newGoods, int newOrders, BigDecimal gmv) {
    }

    public record Total(int users, int goods, int orders, BigDecimal gmv) {
    }

    public record Pending(int goodsAudit, int reports, int disputes) {
    }

    public record Overview(Today today, Total total, Pending pending, LocalDateTime statUpdatedAt) {
    }

    public record Trend(List<String> dates, List<Integer> newUsers, List<Integer> newGoods, List<Integer> newOrders,
                        List<Integer> finishedOrders, List<BigDecimal> gmv) {
    }

    public record CategoryShare(Long categoryId, String name, int count, String percent) {
    }

    // ---------------------------------------------------------------- 汇总

    /** 每小时汇总今天；同时重算昨天，补上昨天最后一小时的数据 */
    @Scheduled(cron = "0 0 * * * *")
    public void refreshRecent() {
        LocalDate today = LocalDate.now();
        refresh(today.minusDays(1));
        refresh(today);
    }

    /**
     * 启动时补算：stat_daily 为空则从最早注册日回填（否则看板累计值为 0）；
     * 否则从最后一条记录那天补到今天，停机期间漏掉的日期和当天数据不必等到下一个整点。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void backfillOnStartup() {
        try {
            LocalDate today = LocalDate.now();
            // 聚合函数在空表上返回一行 NULL，需先滤掉再 findFirst（否则 NPE）
            Object last = statMapper.selectObjs(new QueryWrapper<StatDaily>().select("MAX(stat_date)"))
                    .stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
            LocalDate from;
            if (last != null) {
                from = last instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) last;
            } else {
                Object first = userMapper.selectObjs(new QueryWrapper<com.campustrade.entity.User>()
                        .select("MIN(created_at)")).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
                from = first instanceof LocalDateTime t ? t.toLocalDate() : today;
            }
            if (from.isBefore(today.minusDays(MAX_BACKFILL_DAYS))) {
                from = today.minusDays(MAX_BACKFILL_DAYS);
            }
            for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(1)) {
                refresh(d);
            }
            log.info("stat_daily 补算完成：{} ~ {}", from, today);
        } catch (RuntimeException e) {
            log.error("stat_daily 补算失败", e);
        }
    }

    public void refresh(LocalDate day) {
        statMapper.refresh(day, day.plusDays(1));
    }

    // ---------------------------------------------------------------- 查询

    public Overview overview() {
        StatDaily t = statMapper.selectById(LocalDate.now());
        Today today = t == null ? new Today(0, 0, 0, BigDecimal.ZERO)
                : new Today(t.getNewUsers(), t.getNewGoods(), t.getNewOrders(), t.getGmv());
        Map<String, Object> sum = statMapper.selectMaps(new QueryWrapper<StatDaily>().select(
                "COALESCE(SUM(new_users), 0) AS users", "COALESCE(SUM(new_goods), 0) AS goods",
                "COALESCE(SUM(new_orders), 0) AS orders", "COALESCE(SUM(gmv), 0) AS gmv",
                "MAX(updated_at) AS updatedAt")).getFirst();
        Total total = new Total(num(sum.get("users")), num(sum.get("goods")), num(sum.get("orders")),
                new BigDecimal(String.valueOf(sum.get("gmv"))));
        Pending pending = new Pending(
                goodsMapper.selectCount(new LambdaQueryWrapper<Goods>().eq(Goods::getStatus, GoodsStatus.PENDING)).intValue(),
                reportMapper.selectCount(new LambdaQueryWrapper<Report>().eq(Report::getStatus, 0)).intValue(),
                afterSaleMapper.selectCount(new LambdaQueryWrapper<AfterSale>()
                        .eq(AfterSale::getStatus, AfterSaleService.ARBITRATING)).intValue());
        Object at = sum.get("updatedAt");
        LocalDateTime updatedAt = at instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : (LocalDateTime) at;
        return new Overview(today, total, pending, updatedAt);
    }

    public Trend trend(int days) {
        int n = Math.max(1, Math.min(days, MAX_DAYS));
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(n - 1L);
        Map<LocalDate, StatDaily> rows = statMapper.selectList(new LambdaQueryWrapper<StatDaily>()
                        .between(StatDaily::getStatDate, start, end)).stream()
                .collect(Collectors.toMap(StatDaily::getStatDate, Function.identity()));
        List<String> dates = new ArrayList<>();
        List<Integer> users = new ArrayList<>(), goods = new ArrayList<>(), orders = new ArrayList<>(),
                finished = new ArrayList<>();
        List<BigDecimal> gmv = new ArrayList<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            StatDaily s = rows.get(d);
            dates.add(d.toString());
            users.add(s == null ? 0 : s.getNewUsers());
            goods.add(s == null ? 0 : s.getNewGoods());
            orders.add(s == null ? 0 : s.getNewOrders());
            finished.add(s == null ? 0 : s.getFinishedOrders());
            gmv.add(s == null ? BigDecimal.ZERO : s.getGmv());
        }
        return new Trend(dates, users, goods, orders, finished, gmv);
    }

    /** 在售商品按一级分类统计占比，结果缓存 1 小时 */
    public List<CategoryShare> categoryShare() {
        String cached = redis.opsForValue().get(SHARE_CACHE);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, new TypeReference<>() {
                });
            } catch (JsonProcessingException e) {
                log.warn("分类占比缓存解析失败，重新计算", e);
            }
        }
        List<CategoryShare> result = computeShare();
        try {
            redis.opsForValue().set(SHARE_CACHE, objectMapper.writeValueAsString(result), Duration.ofHours(1));
        } catch (JsonProcessingException e) {
            log.warn("分类占比写缓存失败", e);
        }
        return result;
    }

    private List<CategoryShare> computeShare() {
        Map<Long, Category> cats = categoryMapper.selectList(null).stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        Map<Long, Integer> byRoot = new HashMap<>();
        goodsMapper.selectMaps(new QueryWrapper<Goods>().select("category_id", "COUNT(*) AS cnt")
                .eq("status", GoodsStatus.ON_SALE).groupBy("category_id")).forEach(m -> {
            Category c = cats.get(((Number) m.get("category_id")).longValue());
            if (c != null) {
                long root = c.getParentId() == 0 ? c.getId() : c.getParentId();
                byRoot.merge(root, num(m.get("cnt")), Integer::sum);
            }
        });
        int total = byRoot.values().stream().mapToInt(Integer::intValue).sum();
        return byRoot.entrySet().stream()
                .map(e -> new CategoryShare(e.getKey(), cats.containsKey(e.getKey()) ? cats.get(e.getKey()).getName() : null,
                        e.getValue(), BigDecimal.valueOf(e.getValue() * 100.0 / total)
                        .setScale(1, RoundingMode.HALF_UP).toPlainString()))
                .sorted(Comparator.comparingInt(CategoryShare::count).reversed()).toList();
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
