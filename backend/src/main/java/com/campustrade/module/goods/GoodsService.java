package com.campustrade.module.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.TxUtil;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.Category;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.entity.GoodsImage;
import com.campustrade.entity.User;
import com.campustrade.entity.UserBlock;
import com.campustrade.infra.FileStorageService;
import com.campustrade.infra.StockCache;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsImageMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.UserBlockMapper;
import com.campustrade.module.common.CommonService;
import com.campustrade.module.goods.GoodsDtos.DetailVO;
import com.campustrade.module.goods.GoodsDtos.FavoriteCard;
import com.campustrade.module.goods.GoodsDtos.FavoriteResp;
import com.campustrade.module.goods.GoodsDtos.GoodsCard;
import com.campustrade.module.goods.GoodsDtos.MyGoodsCard;
import com.campustrade.module.goods.GoodsDtos.MyGoodsPage;
import com.campustrade.module.goods.GoodsDtos.SaveReq;
import com.campustrade.module.goods.GoodsDtos.SaveResp;
import com.campustrade.module.goods.GoodsDtos.SearchQuery;
import com.campustrade.module.goods.GoodsDtos.SellerBrief;
import com.campustrade.module.goods.GoodsDtos.SellerCard;
import com.campustrade.module.goods.search.GoodsSearchIndex;
import com.campustrade.module.user.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsService {

    private static final BigDecimal MIN_PRICE = new BigDecimal("0.01");
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999");
    /** 卖家/用户在列表中可见的商品状态 */
    private static final Set<Integer> PUBLIC_STATUS = Set.of(GoodsStatus.ON_SALE, GoodsStatus.TRADING, GoodsStatus.SOLD);

    private final GoodsMapper goodsMapper;
    private final GoodsImageMapper imageMapper;
    private final FavoriteMapper favoriteMapper;
    private final UserService userService;
    private final CommonService commonService;
    private final GoodsAuditService auditService;
    private final HotWordService hotWordService;
    private final FileStorageService fileStorageService;
    private final StringRedisTemplate redis;
    private final StockCache stockCache;
    private final UserBlockMapper blockMapper;
    private final PriceDropService priceDropService;
    private final RecommendService recommendService;
    /** SEARCH_ENGINE=es 时才有 */
    private final ObjectProvider<GoodsSearchIndex> searchIndex;
    private final AppProperties props;

    // ------------------------------------------------------------------ 浏览

    public PageResult<GoodsCard> search(SearchQuery q, Long viewerId) {
        QueryWrapper<Goods> w = new QueryWrapper<Goods>().eq("status", GoodsStatus.ON_SALE);
        String kw = q.getKeyword() == null ? null : q.getKeyword().trim();
        boolean hasKw = kw != null && !kw.isEmpty();
        if (hasKw) {
            hotWordService.record(kw);
            PageResult<GoodsCard> es = searchByIndex(q, kw, viewerId);
            if (es != null) {
                return es;
            }
            w.apply("MATCH(title, description) AGAINST({0})", kw);
        }
        if (q.getCategoryId() != null) {
            w.in("category_id", commonService.expandCategory(q.getCategoryId()));
        }
        w.eq(q.getCampusId() != null, "campus_id", q.getCampusId())
                .ge(q.getPriceMin() != null, "price", q.getPriceMin())
                .le(q.getPriceMax() != null, "price", q.getPriceMax())
                .in(q.getConditionLevel() != null && !q.getConditionLevel().isEmpty(), "condition_level",
                        q.getConditionLevel())
                .eq(Boolean.TRUE.equals(q.getFree()), "price", BigDecimal.ZERO);
        if (q.getTradeMode() != null) {
            w.in("trade_mode", q.getTradeMode(), TradeMode.BOTH);
        }
        if (viewerId != null) {
            // 过滤拉黑了我的卖家
            w.notInSql("seller_id", "SELECT user_id FROM user_block WHERE blocked_user_id = " + viewerId);
        }
        String sort = q.getSort() == null ? "DEFAULT" : q.getSort();
        switch (sort) {
            case "PRICE_ASC" -> w.orderByAsc("price").orderByDesc("id");
            case "PRICE_DESC" -> w.orderByDesc("price").orderByDesc("id");
            // 有关键词时 MATCH 在 WHERE 中，不加 ORDER BY 即按相关度排序
            case "DEFAULT" -> {
                if (!hasKw) {
                    w.orderByDesc("published_at");
                }
            }
            default -> w.orderByDesc("published_at");
        }
        Page<Goods> page = goodsMapper.selectPage(q.toPage(), w);
        return PageResult.of(page, cards(page.getRecords()));
    }

    /** ES 检索；未启用或出错返回 null，由调用方走 MySQL 全文索引 */
    private PageResult<GoodsCard> searchByIndex(SearchQuery q, String kw, Long viewerId) {
        GoodsSearchIndex index = searchIndex.getIfAvailable();
        if (index == null) {
            return null;
        }
        try {
            List<Long> blockedBy = viewerId == null ? List.of() : blockMapper.selectList(
                            new LambdaQueryWrapper<UserBlock>().eq(UserBlock::getBlockedUserId, viewerId))
                    .stream().map(UserBlock::getUserId).toList();
            GoodsSearchIndex.Hits hits = index.search(q, kw,
                    q.getCategoryId() == null ? null : commonService.expandCategory(q.getCategoryId()), blockedBy);
            Map<Long, Goods> byId = hits.ids().isEmpty() ? Map.of() : goodsMapper.selectByIds(hits.ids()).stream()
                    .collect(Collectors.toMap(Goods::getId, Function.identity()));
            // 按 ES 排序；索引滞后时已下架的商品直接跳过
            List<Goods> ordered = hits.ids().stream().map(byId::get)
                    .filter(g -> g != null && g.getStatus() == GoodsStatus.ON_SALE).toList();
            Page<Goods> page = q.toPage();
            return new PageResult<>(cards(ordered), hits.total(), (int) page.getCurrent(), (int) page.getSize());
        } catch (Exception e) {
            log.warn("ES 检索失败，退回 MySQL：{}", e.getMessage());
            return null;
        }
    }

    /** 猜你喜欢（P2） */
    public List<GoodsCard> recommend(Long viewerId, Integer size) {
        return cards(recommendService.recommend(viewerId, size == null ? 10 : size));
    }

    public PageResult<GoodsCard> onSaleOf(long sellerId, PageQuery pq) {
        Page<Goods> page = goodsMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Goods>()
                .eq(Goods::getSellerId, sellerId).eq(Goods::getStatus, GoodsStatus.ON_SALE)
                .orderByDesc(Goods::getPublishedAt));
        return PageResult.of(page, cards(page.getRecords()));
    }

    public List<String> hotWords() {
        return hotWordService.top10();
    }

    public DetailVO detail(long id, Long viewerId, String clientIp) {
        Goods g = goodsMapper.selectById(id);
        boolean mine = g != null && viewerId != null && g.getSellerId().equals(viewerId);
        Biz.check(g != null && (mine || PUBLIC_STATUS.contains(g.getStatus())), ErrorCode.GOODS_NOT_FOUND);
        countView(g, viewerId == null ? "ip:" + clientIp : "u:" + viewerId);
        if (viewerId != null && !mine) {
            recommendService.onViewed(viewerId, g);
        }

        User seller = userService.require(g.getSellerId());
        int score = seller.getCreditScore();
        SellerCard sellerCard = new SellerCard(seller.getId(), seller.getNickname(), seller.getAvatarUrl(), score,
                CreditLevel.of(score).name(), score >= 90, userService.goodRate(seller.getId()),
                userService.onSaleCount(seller.getId()));
        boolean favorited = viewerId != null && favoriteMapper.exists(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, viewerId).eq(Favorite::getGoodsId, id));
        String reason = cannotBuyReason(g, viewerId);
        return new DetailVO(g.getId(), g.getTitle(), g.getDescription(), images(id), g.getCategoryId(),
                commonService.categoryPath(g.getCategoryId()), g.getPrice(), g.getOriginalPrice(),
                g.getConditionLevel(), g.getTradeMode(), g.getCampusId(), userService.campusName(g.getCampusId()),
                g.getLocation(), g.getStock(), g.getStatus(), mine ? g.getAuditRemark() : null, g.getViewCount(),
                g.getFavoriteCount(), g.getPublishedAt(), sellerCard, favorited, mine, reason == null, reason);
    }

    private String cannotBuyReason(Goods g, Long viewerId) {
        if (viewerId == null) {
            return "NOT_LOGIN";
        }
        if (g.getSellerId().equals(viewerId)) {
            return "SELF";
        }
        if (g.getStatus() != GoodsStatus.ON_SALE) {
            return "NOT_ON_SALE";
        }
        if (g.getStock() <= 0) {
            return "OUT_OF_STOCK";
        }
        if (userService.isBlocked(g.getSellerId(), viewerId)) {
            return "BLOCKED";
        }
        return null;
    }

    /** 同一访客 30 分钟内只计一次浏览 */
    private void countView(Goods g, String visitor) {
        Boolean first = redis.opsForValue().setIfAbsent("goods:view:" + g.getId() + ":" + visitor, "1",
                Duration.ofMinutes(30));
        if (Boolean.TRUE.equals(first)) {
            goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, g.getId())
                    .setSql("view_count = view_count + 1"));
            g.setViewCount(g.getViewCount() + 1);
        }
    }

    // ------------------------------------------------------------------ 发布与管理

    @Transactional
    public SaveResp create(long userId, SaveReq req) {
        boolean draft = Boolean.TRUE.equals(req.draft());
        User seller = userService.requireCanPublish(userId);
        validate(req, seller);
        Goods g = new Goods();
        fill(g, req, seller);
        g.setSellerId(userId);
        g.setSchoolId(seller.getSchoolId());
        g.setStatus(GoodsStatus.DRAFT);
        goodsMapper.insert(g);
        saveImages(g.getId(), req.images());
        if (draft) {
            return new SaveResp(g.getId(), GoodsStatus.DRAFT, List.of());
        }
        checkPublishQuota(seller, g.getId());
        List<String> hits = auditService.submit(g, seller);
        return new SaveResp(g.getId(), g.getStatus(), hits);
    }

    @Transactional
    public SaveResp update(long userId, long id, SaveReq req) {
        Goods g = requireMine(userId, id);
        int st = g.getStatus();
        Biz.check(st != GoodsStatus.TRADING && st != GoodsStatus.SOLD && st != GoodsStatus.FORCED_OFF,
                ErrorCode.GOODS_STATE_INVALID, "当前状态不可编辑");
        User seller = userService.requireCanPublish(userId);
        validate(req, seller);
        List<String> oldImages = images(id);
        boolean contentChanged = !Objects.equals(g.getTitle(), req.title().trim())
                || !Objects.equals(g.getDescription(), req.description().trim())
                || !Objects.equals(g.getCategoryId(), req.categoryId())
                || !oldImages.equals(req.images());
        BigDecimal oldPrice = g.getPrice();
        fill(g, req, seller);
        goodsMapper.updateById(g);
        TxUtil.afterCommit(() -> stockCache.evict(id));
        if (!oldImages.equals(req.images())) {
            imageMapper.delete(new LambdaQueryWrapper<GoodsImage>().eq(GoodsImage::getGoodsId, id));
            saveImages(id, req.images());
        }
        boolean keepDraft = Boolean.TRUE.equals(req.draft()) && st == GoodsStatus.DRAFT;
        if (keepDraft || (st == GoodsStatus.ON_SALE && !contentChanged)) {
            if (st == GoodsStatus.ON_SALE) {
                priceDropService.onPriceChanged(g, oldPrice);
            }
            return new SaveResp(id, st, List.of());
        }
        if (st == GoodsStatus.DRAFT || st == GoodsStatus.REJECTED || st == GoodsStatus.OFF_SHELF) {
            checkPublishQuota(seller, id);
        }
        List<String> hits = auditService.submit(g, seller);
        return new SaveResp(id, g.getStatus(), hits);
    }

    public void offShelf(long userId, long id) {
        Goods g = requireMine(userId, id);
        Biz.check(g.getStatus() == GoodsStatus.ON_SALE || g.getStatus() == GoodsStatus.PENDING,
                ErrorCode.GOODS_STATE_INVALID);
        changeStatus(id, g.getStatus(), GoodsStatus.OFF_SHELF);
    }

    @Transactional
    public SaveResp onShelf(long userId, long id) {
        Goods g = requireMine(userId, id);
        Biz.check(g.getStatus() == GoodsStatus.OFF_SHELF, ErrorCode.GOODS_STATE_INVALID);
        User seller = userService.requireCanPublish(userId);
        checkPublishQuota(seller, id);
        List<String> hits = auditService.submit(g, seller);
        return new SaveResp(id, g.getStatus(), hits);
    }

    public void delete(long userId, long id) {
        Goods g = requireMine(userId, id);
        Biz.check(g.getStatus() == GoodsStatus.DRAFT || g.getStatus() == GoodsStatus.REJECTED
                || g.getStatus() == GoodsStatus.OFF_SHELF, ErrorCode.GOODS_STATE_INVALID);
        goodsMapper.deleteById(id);
    }

    public MyGoodsPage mine(long userId, Integer status, PageQuery pq) {
        Page<Goods> page = goodsMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Goods>()
                .eq(Goods::getSellerId, userId).eq(status != null, Goods::getStatus, status)
                .orderByDesc(Goods::getUpdatedAt));
        Map<Long, String> campus = commonService.campusNames();
        List<MyGoodsCard> list = page.getRecords().stream().map(g -> new MyGoodsCard(g.getId(), g.getTitle(),
                g.getCoverUrl(), g.getPrice(), g.getOriginalPrice(), g.getConditionLevel(), g.getTradeMode(),
                g.getCampusId(), campus.get(g.getCampusId()), g.getFavoriteCount(), g.getStatus(),
                g.getAuditRemark(), g.getViewCount(), g.getStock(), g.getPublishedAt(), g.getUpdatedAt())).toList();
        Map<String, Integer> counts = new HashMap<>();
        for (int s = GoodsStatus.DRAFT; s <= GoodsStatus.FORCED_OFF; s++) {
            counts.put(String.valueOf(s), 0);
        }
        goodsMapper.selectMaps(new QueryWrapper<Goods>().select("status", "COUNT(*) AS cnt")
                        .eq("seller_id", userId).groupBy("status"))
                .forEach(m -> counts.put(String.valueOf(m.get("status")), ((Number) m.get("cnt")).intValue()));
        return new MyGoodsPage(list, (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize(), counts);
    }

    // ------------------------------------------------------------------ 收藏

    @Transactional
    public FavoriteResp favorite(long userId, long goodsId) {
        Goods g = Biz.notNull(goodsMapper.selectById(goodsId), ErrorCode.GOODS_NOT_FOUND);
        Biz.check(!g.getSellerId().equals(userId), ErrorCode.FAVORITE_SELF);
        Favorite f = new Favorite();
        f.setUserId(userId);
        f.setGoodsId(goodsId);
        try {
            favoriteMapper.insert(f);
            goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goodsId)
                    .setSql("favorite_count = favorite_count + 1"));
            recommendService.onFavorited(userId, g);
        } catch (DuplicateKeyException ignored) {
            // 已收藏，幂等
        }
        return new FavoriteResp(true, goodsMapper.selectById(goodsId).getFavoriteCount());
    }

    @Transactional
    public FavoriteResp unfavorite(long userId, long goodsId) {
        int n = favoriteMapper.delete(new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId).eq(Favorite::getGoodsId, goodsId));
        if (n > 0) {
            goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goodsId).gt(Goods::getFavoriteCount, 0)
                    .setSql("favorite_count = favorite_count - 1"));
        }
        Goods g = goodsMapper.selectById(goodsId);
        return new FavoriteResp(false, g == null ? 0 : g.getFavoriteCount());
    }

    public PageResult<FavoriteCard> favorites(long userId, PageQuery pq) {
        Page<Favorite> page = favoriteMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Favorite>()
                .eq(Favorite::getUserId, userId).orderByDesc(Favorite::getId));
        List<Long> ids = page.getRecords().stream().map(Favorite::getGoodsId).toList();
        // 收藏列表包含已删除商品之外的全部状态
        Map<Long, Goods> goods = ids.isEmpty() ? Map.of() : goodsMapper.selectByIds(ids).stream()
                .collect(Collectors.toMap(Goods::getId, Function.identity()));
        Map<Long, User> sellers = userService.mapByIds(goods.values().stream().map(Goods::getSellerId).toList());
        Map<Long, String> campus = commonService.campusNames();
        List<FavoriteCard> list = page.getRecords().stream().filter(f -> goods.containsKey(f.getGoodsId())).map(f -> {
            Goods g = goods.get(f.getGoodsId());
            return new FavoriteCard(g.getId(), g.getTitle(), g.getCoverUrl(), g.getPrice(), g.getOriginalPrice(),
                    g.getConditionLevel(), g.getTradeMode(), g.getCampusId(), campus.get(g.getCampusId()),
                    g.getFavoriteCount(), sellerBrief(sellers.get(g.getSellerId())), g.getPublishedAt(),
                    g.getStatus(), f.getCreatedAt());
        }).toList();
        return PageResult.of(page, list);
    }

    // ------------------------------------------------------------------ 内部

    public List<GoodsCard> cards(List<Goods> goods) {
        Map<Long, User> sellers = userService.mapByIds(goods.stream().map(Goods::getSellerId).distinct().toList());
        Map<Long, String> campus = commonService.campusNames();
        return goods.stream().map(g -> new GoodsCard(g.getId(), g.getTitle(), g.getCoverUrl(), g.getPrice(),
                g.getOriginalPrice(), g.getConditionLevel(), g.getTradeMode(), g.getCampusId(),
                campus.get(g.getCampusId()), g.getFavoriteCount(), sellerBrief(sellers.get(g.getSellerId())),
                g.getPublishedAt())).toList();
    }

    private static SellerBrief sellerBrief(User u) {
        return u == null ? null : new SellerBrief(u.getId(), u.getNickname(), u.getAvatarUrl(),
                CreditLevel.of(u.getCreditScore()).name());
    }

    public List<String> images(long goodsId) {
        return imageMapper.selectList(new LambdaQueryWrapper<GoodsImage>().eq(GoodsImage::getGoodsId, goodsId)
                .orderByAsc(GoodsImage::getSort)).stream().map(GoodsImage::getUrl).toList();
    }

    private Goods requireMine(long userId, long id) {
        Goods g = goodsMapper.selectById(id);
        Biz.check(g != null && g.getSellerId().equals(userId), ErrorCode.GOODS_NOT_FOUND);
        return g;
    }

    private void changeStatus(long id, int from, int to) {
        int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, id).eq(Goods::getStatus, from)
                .set(Goods::getStatus, to));
        Biz.check(n == 1, ErrorCode.CONFLICT);
    }

    private void validate(SaveReq req, User seller) {
        BigDecimal price = req.price();
        Biz.check(price.scale() <= 2 && (price.signum() == 0
                || (price.compareTo(MIN_PRICE) >= 0 && price.compareTo(MAX_PRICE) <= 0)), ErrorCode.PRICE_INVALID);
        if (req.originalPrice() != null) {
            Biz.check(req.originalPrice().scale() <= 2 && req.originalPrice().compareTo(price) >= 0
                    && req.originalPrice().compareTo(MAX_PRICE) <= 0, ErrorCode.PRICE_INVALID, "原价须不低于售价");
        }
        Biz.check(price.signum() != 0 || req.tradeMode() == TradeMode.FACE, ErrorCode.FREE_MUST_FACE);
        Biz.check(req.tradeMode() == TradeMode.FACE || seller.getCreditScore() >= 60, ErrorCode.GUARANTEE_CREDIT_LOW);
        Category c = commonService.enabledLeaf(req.categoryId());
        Biz.notNull(c, ErrorCode.CATEGORY_INVALID);
        for (String url : req.images()) {
            Biz.check(fileStorageService.isOwnUrl(url), ErrorCode.BAD_REQUEST, "图片必须通过本平台上传");
        }
        if (req.campusId() != null) {
            Biz.check(commonService.campusNames().containsKey(req.campusId()), ErrorCode.BAD_REQUEST, "校区不存在");
        }
    }

    private void fill(Goods g, SaveReq req, User seller) {
        g.setTitle(req.title().trim());
        g.setDescription(req.description().trim());
        g.setCoverUrl(req.images().getFirst());
        g.setCategoryId(req.categoryId());
        g.setPrice(req.price());
        g.setOriginalPrice(req.originalPrice());
        g.setConditionLevel(req.conditionLevel());
        g.setTradeMode(req.tradeMode());
        g.setCampusId(req.campusId() != null ? req.campusId() : seller.getCampusId());
        g.setLocation(req.location() == null ? null : req.location().trim());
        g.setStock(req.stock() == null ? 1 : req.stock());
    }

    private void saveImages(long goodsId, List<String> urls) {
        for (int i = 0; i < urls.size(); i++) {
            GoodsImage img = new GoodsImage();
            img.setGoodsId(goodsId);
            img.setUrl(urls.get(i));
            img.setSort(i);
            imageMapper.insert(img);
        }
    }

    /** 在售+待审 ≤ 50；当日提交审核 ≤ 20（信用 40–59 为 3） */
    private void checkPublishQuota(User seller, long goodsId) {
        AppProperties.Rules r = props.rules();
        long active = goodsMapper.selectCount(new LambdaQueryWrapper<Goods>().eq(Goods::getSellerId, seller.getId())
                .in(Goods::getStatus, GoodsStatus.ON_SALE, GoodsStatus.PENDING).ne(Goods::getId, goodsId));
        Biz.check(active < r.maxOnSale(), ErrorCode.ON_SALE_LIMIT);
        int dailyLimit = seller.getCreditScore() < 60 ? r.dailyPublishPoorCredit() : r.dailyPublish();
        String key = "publish:daily:" + seller.getId() + ":" + LocalDate.now();
        Long n = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofDays(1));
        if (n != null && n > dailyLimit) {
            throw new BizException(ErrorCode.DAILY_PUBLISH_LIMIT);
        }
    }
}
