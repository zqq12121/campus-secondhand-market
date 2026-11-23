package com.campustrade.module.want;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.infra.SensitiveWordService;
import com.campustrade.module.common.CommonService;
import com.campustrade.module.common.entity.Category;
import com.campustrade.module.common.mapper.CategoryMapper;
import com.campustrade.module.goods.GoodsDtos.SellerBrief;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.entity.WantResponse;
import com.campustrade.module.want.mapper.WantPostMapper;
import com.campustrade.module.want.mapper.WantResponseMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WantService {

    private static final Set<Integer> VALID_DAYS = Set.of(3, 7, 15, 30);
    private static final int MAX_ACTIVE = 10;
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999");

    private final WantPostMapper wantMapper;
    private final WantResponseMapper responseMapper;
    private final CategoryMapper categoryMapper;
    private final UserService userService;
    private final CommonService commonService;
    private final SensitiveWordService sensitiveWordService;

    // ---------------------------------------------------------------- DTO

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ListQuery extends PageQuery {
        @Size(max = 30)
        private String keyword;
        private Long categoryId;
        private Long campusId;
    }

    public record CreateReq(@NotBlank @Size(max = 30, message = "标题不超过 30 字") String title,
                            @Size(max = 500, message = "描述不超过 500 字") String description,
                            @NotNull Long categoryId,
                            Long campusId,
                            BigDecimal priceMin,
                            BigDecimal priceMax,
                            @NotNull Integer validDays) {
    }

    public record IdResp(Long id) {
    }

    public record WantVO(Long id, String title, String description, Long categoryId, String categoryName,
                         BigDecimal priceMin, BigDecimal priceMax, String campusName, Integer status,
                         LocalDateTime expireAt, SellerBrief user, LocalDateTime createdAt, Boolean isMine,
                         Integer responseCount) {
    }

    // ---------------------------------------------------------------- 查询

    /** 只返回进行中且未到期的帖子 */
    public PageResult<WantVO> list(ListQuery q) {
        String kw = q.getKeyword() == null ? null : q.getKeyword().trim();
        LambdaQueryWrapper<WantPost> w = new LambdaQueryWrapper<WantPost>()
                .eq(WantPost::getStatus, WantStatus.ACTIVE).gt(WantPost::getExpireAt, LocalDateTime.now())
                .eq(q.getCampusId() != null, WantPost::getCampusId, q.getCampusId())
                .like(kw != null && !kw.isEmpty(), WantPost::getTitle, kw)
                .orderByDesc(WantPost::getCreatedAt);
        if (q.getCategoryId() != null) {
            w.in(WantPost::getCategoryId, commonService.expandCategory(q.getCategoryId()));
        }
        Page<WantPost> page = wantMapper.selectPage(q.toPage(), w);
        return PageResult.of(page, vos(page.getRecords(), null));
    }

    /** 非进行中（含已到期）的帖子只有发布者能看 */
    public WantVO detail(long id, Long viewerId) {
        WantPost p = wantMapper.selectById(id);
        boolean mine = p != null && viewerId != null && p.getUserId().equals(viewerId);
        Biz.check(p != null && (mine || isActive(p)), ErrorCode.WANT_NOT_FOUND);
        // 未登录时传 0（不存在的用户 ID），使 isMine 为 false 而不是 null
        return vos(List.of(p), viewerId == null ? 0L : viewerId).getFirst();
    }

    /** 我的求购：全部状态 */
    public PageResult<WantVO> mine(long userId, Integer status, PageQuery pq) {
        expire(userId);
        Page<WantPost> page = wantMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<WantPost>()
                .eq(WantPost::getUserId, userId).eq(status != null, WantPost::getStatus, status)
                .orderByDesc(WantPost::getCreatedAt));
        return PageResult.of(page, vos(page.getRecords(), userId));
    }

    // ---------------------------------------------------------------- 发布与管理

    @Transactional
    public IdResp create(long userId, CreateReq req) {
        User u = userService.requireCanPublish(userId);
        Biz.check(VALID_DAYS.contains(req.validDays()), ErrorCode.BAD_REQUEST, "有效期只能是 3/7/15/30 天");
        Biz.notNull(commonService.enabledLeaf(req.categoryId()), ErrorCode.CATEGORY_INVALID);
        checkPrice(req.priceMin());
        checkPrice(req.priceMax());
        Biz.check(req.priceMin() == null || req.priceMax() == null || req.priceMin().compareTo(req.priceMax()) <= 0,
                ErrorCode.PRICE_INVALID, "最低价不能高于最高价");
        if (req.campusId() != null) {
            Biz.check(commonService.campusNames().containsKey(req.campusId()), ErrorCode.BAD_REQUEST, "校区不存在");
        }
        // 已到期但定时任务还没处理的帖子不占名额
        expire(userId);
        long active = wantMapper.selectCount(new LambdaQueryWrapper<WantPost>()
                .eq(WantPost::getUserId, userId).eq(WantPost::getStatus, WantStatus.ACTIVE));
        Biz.check(active < MAX_ACTIVE, ErrorCode.WANT_LIMIT);

        WantPost p = new WantPost();
        p.setUserId(userId);
        p.setSchoolId(u.getSchoolId());
        p.setCampusId(req.campusId() != null ? req.campusId() : u.getCampusId());
        p.setCategoryId(req.categoryId());
        p.setTitle(sensitiveWordService.replace(req.title().trim()));
        String desc = req.description() == null ? "" : req.description().trim();
        p.setDescription(desc.isEmpty() ? null : sensitiveWordService.replace(desc));
        p.setPriceMin(req.priceMin());
        p.setPriceMax(req.priceMax());
        p.setStatus(WantStatus.ACTIVE);
        p.setExpireAt(LocalDateTime.now().plusDays(req.validDays()));
        wantMapper.insert(p);
        return new IdResp(p.getId());
    }

    /** 标记已解决：仅发布者，0 → 1 */
    public void close(long userId, long id) {
        WantPost p = requireMine(userId, id);
        Biz.check(isActive(p), ErrorCode.WANT_NOT_FOUND);
        int n = wantMapper.update(new LambdaUpdateWrapper<WantPost>().eq(WantPost::getId, id)
                .eq(WantPost::getStatus, WantStatus.ACTIVE).set(WantPost::getStatus, WantStatus.SOLVED));
        Biz.check(n == 1, ErrorCode.CONFLICT);
    }

    /** 逻辑删除，任意状态可删 */
    public void delete(long userId, long id) {
        requireMine(userId, id);
        wantMapper.deleteById(id);
    }

    /** 到期的进行中帖子置为已过期；userId 为 null 时处理全部 */
    public int expire(Long userId) {
        return wantMapper.update(new LambdaUpdateWrapper<WantPost>()
                .eq(userId != null, WantPost::getUserId, userId)
                .eq(WantPost::getStatus, WantStatus.ACTIVE).le(WantPost::getExpireAt, LocalDateTime.now())
                .set(WantPost::getStatus, WantStatus.EXPIRED));
    }

    // ---------------------------------------------------------------- 内部

    private static boolean isActive(WantPost p) {
        return p.getStatus() == WantStatus.ACTIVE && p.getExpireAt().isAfter(LocalDateTime.now());
    }

    private WantPost requireMine(long userId, long id) {
        WantPost p = wantMapper.selectById(id);
        Biz.check(p != null && p.getUserId().equals(userId), ErrorCode.WANT_NOT_FOUND);
        return p;
    }

    private static void checkPrice(BigDecimal price) {
        Biz.check(price == null || (price.signum() >= 0 && price.scale() <= 2 && price.compareTo(MAX_PRICE) <= 0),
                ErrorCode.PRICE_INVALID);
    }

    private List<WantVO> vos(List<WantPost> posts, Long viewerId) {
        if (posts.isEmpty()) {
            return List.of();
        }
        Map<Long, User> users = userService.mapByIds(posts.stream().map(WantPost::getUserId).distinct().toList());
        Map<Long, String> categories = categoryMapper.selectByIds(posts.stream().map(WantPost::getCategoryId)
                .distinct().toList()).stream().collect(Collectors.toMap(Category::getId, Category::getName));
        Map<Long, String> campus = commonService.campusNames();
        Map<Long, Integer> responses = responseCounts(posts.stream()
                .filter(p -> p.getUserId().equals(viewerId)).map(WantPost::getId).toList());
        // 已到期但定时任务还没处理的帖子，按已过期展示
        return posts.stream().map(p -> new WantVO(p.getId(), p.getTitle(), p.getDescription(), p.getCategoryId(),
                categories.get(p.getCategoryId()), p.getPriceMin(), p.getPriceMax(), campus.get(p.getCampusId()),
                p.getStatus() == WantStatus.ACTIVE && !isActive(p) ? WantStatus.EXPIRED : p.getStatus(),
                p.getExpireAt(), brief(users.get(p.getUserId())), p.getCreatedAt(),
                viewerId == null ? null : p.getUserId().equals(viewerId),
                p.getUserId().equals(viewerId) ? responses.getOrDefault(p.getId(), 0) : null)).toList();
    }

    /** 收到几次「我有」；只统计给发布者看 */
    private Map<Long, Integer> responseCounts(List<Long> wantIds) {
        if (wantIds.isEmpty()) {
            return Map.of();
        }
        return responseMapper.selectMaps(new QueryWrapper<WantResponse>().select("want_id", "COUNT(*) AS cnt")
                        .in("want_id", wantIds).groupBy("want_id")).stream()
                .collect(Collectors.toMap(m -> ((Number) m.get("want_id")).longValue(),
                        m -> ((Number) m.get("cnt")).intValue()));
    }

    private static SellerBrief brief(User u) {
        return u == null ? null : new SellerBrief(u.getId(), u.getNickname(), u.getAvatarUrl(),
                CreditLevel.of(u.getCreditScore()).name());
    }
}
