package com.campustrade.module.admin.goods;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.AuditResult;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.common.Consts.ReportTarget;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.module.admin.system.entity.AdminUser;
import com.campustrade.module.admin.system.mapper.AdminUserMapper;
import com.campustrade.module.common.CommonService;
import com.campustrade.module.goods.GoodsAuditService;
import com.campustrade.module.goods.GoodsService;
import com.campustrade.module.goods.entity.AuditRecord;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.AuditRecordMapper;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.order.OrderFlow;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.report.entity.Report;
import com.campustrade.module.report.mapper.ReportMapper;
import com.campustrade.module.user.FollowService;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.want.WantMatchService;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 后台商品审核与管理 */
@Service
@RequiredArgsConstructor
public class AdminGoodsService {

    private static final int AUDITOR_SYSTEM = 1;
    private static final int AUDITOR_ADMIN = 2;

    private final GoodsMapper goodsMapper;
    private final AuditRecordMapper auditRecordMapper;
    private final TradeOrderMapper orderMapper;
    private final ReportMapper reportMapper;
    private final AdminUserMapper adminUserMapper;
    private final GoodsService goodsService;
    private final GoodsAuditService auditService;
    private final OrderFlow orderFlow;
    private final UserService userService;
    private final CommonService commonService;
    private final NotificationService notificationService;
    private final WantMatchService wantMatchService;
    private final FollowService followService;

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class GoodsQuery extends PageQuery {
        private String keyword;
        private Long sellerId;
        private Long categoryId;
        private Long campusId;
        private Integer status;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    public record SellerInfo(Long id, String nickname, Integer creditScore) {
    }

    public record AdminGoodsVO(Long id, String title, String description, String coverUrl, List<String> images,
                               Long categoryId, List<String> categoryPath, String categoryName, BigDecimal price,
                               Integer conditionLevel,
                               Integer tradeMode, String campusName, String location, Integer stock, Integer status,
                               String auditRemark, Integer viewCount, Integer favoriteCount, int reportCount,
                               SellerInfo seller,
                               List<String> hitWords, LocalDateTime submittedAt, LocalDateTime publishedAt,
                               LocalDateTime createdAt) {
    }

    /** auditorName：管理员审核时为管理员姓名，系统审核为 null */
    public record AuditRecordVO(Long id, Integer auditorType, Long auditorId, String auditorName, Integer result,
                                String reason,
                                String hitWords, LocalDateTime createdAt) {
    }

    public record AdminGoodsDetail(AdminGoodsVO goods, List<AuditRecordVO> auditRecords, int orderCount) {
    }

    public record BatchFail(Long id, String message) {
    }

    public record BatchResult(List<Long> success, List<BatchFail> failed) {
    }

    // ------------------------------------------------------------------ 审核

    public PageResult<AdminGoodsVO> pending(Long categoryId, String keyword, PageQuery pq) {
        LambdaQueryWrapper<Goods> w = new LambdaQueryWrapper<Goods>().eq(Goods::getStatus, GoodsStatus.PENDING)
                .in(categoryId != null, Goods::getCategoryId,
                        categoryId == null ? List.of() : commonService.expandCategory(categoryId))
                .like(keyword != null && !keyword.isBlank(), Goods::getTitle, keyword)
                .orderByAsc(Goods::getUpdatedAt);
        Page<Goods> page = goodsMapper.selectPage(pq.toPage(), w);
        return PageResult.of(page, toVOs(page.getRecords()));
    }

    @Transactional
    public void approve(long adminId, long goodsId) {
        Goods g = requirePending(goodsId);
        int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goodsId)
                .eq(Goods::getStatus, GoodsStatus.PENDING).set(Goods::getStatus, GoodsStatus.ON_SALE)
                .set(Goods::getAuditRemark, null).set(Goods::getPublishedAt, LocalDateTime.now()));
        Biz.check(n == 1, ErrorCode.CONFLICT);
        auditService.record(goodsId, AUDITOR_ADMIN, adminId, AuditResult.PASS, null, null);
        notificationService.send(g.getSellerId(), NotifyType.AUDIT, "商品审核通过",
                "「" + g.getTitle() + "」已通过审核并上架", RefType.GOODS, goodsId);
        wantMatchService.onGoodsOnSale(g);
        followService.onGoodsOnSale(g);
    }

    @Transactional
    public void reject(long adminId, long goodsId, String reason) {
        Goods g = requirePending(goodsId);
        int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goodsId)
                .eq(Goods::getStatus, GoodsStatus.PENDING).set(Goods::getStatus, GoodsStatus.REJECTED)
                .set(Goods::getAuditRemark, reason));
        Biz.check(n == 1, ErrorCode.CONFLICT);
        auditService.record(goodsId, AUDITOR_ADMIN, adminId, AuditResult.REJECT, reason, null);
        notificationService.send(g.getSellerId(), NotifyType.AUDIT, "商品审核未通过",
                "「" + g.getTitle() + "」未通过审核：" + reason, RefType.GOODS, goodsId);
    }

    /** 批量审核：逐条独立处理，返回成功与失败明细 */
    public BatchResult batch(long adminId, List<Long> ids, boolean approve, String reason) {
        List<Long> success = new ArrayList<>();
        List<BatchFail> failed = new ArrayList<>();
        for (Long id : ids) {
            try {
                if (approve) {
                    approve(adminId, id);
                } else {
                    reject(adminId, id, reason);
                }
                success.add(id);
            } catch (com.campustrade.common.BizException e) {
                failed.add(new BatchFail(id, e.getMessage()));
            }
        }
        return new BatchResult(success, failed);
    }

    public List<AuditRecordVO> records(long goodsId) {
        List<AuditRecord> records = auditRecordMapper.selectList(new LambdaQueryWrapper<AuditRecord>()
                .eq(AuditRecord::getTargetType, 1).eq(AuditRecord::getTargetId, goodsId)
                .orderByDesc(AuditRecord::getId));
        List<Long> adminIds = records.stream().filter(r -> r.getAuditorType() == AUDITOR_ADMIN)
                .map(AuditRecord::getAuditorId).distinct().toList();
        Map<Long, String> names = adminIds.isEmpty() ? Map.of() : adminUserMapper.selectByIds(adminIds).stream()
                .collect(Collectors.toMap(AdminUser::getId, AdminUser::getRealName));
        return records.stream()
                .map(r -> new AuditRecordVO(r.getId(), r.getAuditorType(), r.getAuditorId(),
                        r.getAuditorType() == AUDITOR_ADMIN ? names.get(r.getAuditorId()) : null, r.getResult(),
                        r.getReason(), r.getHitWords(), r.getCreatedAt())).toList();
    }

    // ------------------------------------------------------------------ 管理

    public PageResult<AdminGoodsVO> list(GoodsQuery q) {
        LambdaQueryWrapper<Goods> w = new LambdaQueryWrapper<Goods>()
                .like(q.getKeyword() != null && !q.getKeyword().isBlank(), Goods::getTitle, q.getKeyword())
                .eq(q.getSellerId() != null, Goods::getSellerId, q.getSellerId())
                .in(q.getCategoryId() != null, Goods::getCategoryId,
                        q.getCategoryId() == null ? List.of() : commonService.expandCategory(q.getCategoryId()))
                .eq(q.getCampusId() != null, Goods::getCampusId, q.getCampusId())
                .eq(q.getStatus() != null, Goods::getStatus, q.getStatus())
                .ge(q.getStartTime() != null, Goods::getCreatedAt, q.getStartTime())
                .le(q.getEndTime() != null, Goods::getCreatedAt, q.getEndTime())
                .orderByDesc(Goods::getId);
        Page<Goods> page = goodsMapper.selectPage(q.toPage(), w);
        return PageResult.of(page, toVOs(page.getRecords()));
    }

    public AdminGoodsDetail detail(long goodsId) {
        Goods g = Biz.notNull(goodsMapper.selectById(goodsId), ErrorCode.GOODS_NOT_FOUND);
        int orders = orderMapper.selectCount(new LambdaQueryWrapper<com.campustrade.module.order.entity.TradeOrder>()
                .eq(com.campustrade.module.order.entity.TradeOrder::getGoodsId, goodsId)).intValue();
        return new AdminGoodsDetail(toVOs(List.of(g)).getFirst(), records(goodsId), orders);
    }

    @Transactional
    public void forceOff(long adminId, long goodsId, String reason) {
        Goods g = Biz.notNull(goodsMapper.selectById(goodsId), ErrorCode.GOODS_NOT_FOUND);
        Biz.check(g.getStatus() != GoodsStatus.SOLD && g.getStatus() != GoodsStatus.FORCED_OFF,
                ErrorCode.GOODS_STATE_INVALID);
        Biz.check(!orderFlow.hasActiveOrder(goodsId), ErrorCode.GOODS_HAS_ORDER);
        int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, goodsId)
                .eq(Goods::getStatus, g.getStatus()).set(Goods::getStatus, GoodsStatus.FORCED_OFF)
                .set(Goods::getAuditRemark, reason));
        Biz.check(n == 1, ErrorCode.CONFLICT);
        auditService.record(goodsId, AUDITOR_ADMIN, adminId, AuditResult.REJECT, "强制下架：" + reason, null);
        notificationService.send(g.getSellerId(), NotifyType.AUDIT, "商品被强制下架",
                "「" + g.getTitle() + "」已被管理员下架：" + reason, RefType.GOODS, goodsId);
    }

    // ------------------------------------------------------------------ 内部

    private Goods requirePending(long goodsId) {
        Goods g = Biz.notNull(goodsMapper.selectById(goodsId), ErrorCode.GOODS_NOT_FOUND);
        Biz.check(g.getStatus() == GoodsStatus.PENDING, ErrorCode.CONFLICT, "状态已变更");
        return g;
    }

    private List<AdminGoodsVO> toVOs(List<Goods> goods) {
        Map<Long, User> sellers = userService.mapByIds(goods.stream().map(Goods::getSellerId).distinct().toList());
        Map<Long, String> campus = commonService.campusNames();
        Map<Long, Integer> reports = reportCounts(goods.stream().map(Goods::getId).toList());
        List<AdminGoodsVO> list = new ArrayList<>();
        for (Goods g : goods) {
            List<String> path = commonService.categoryPath(g.getCategoryId());
            AuditRecord sys = auditRecordMapper.selectOne(new LambdaQueryWrapper<AuditRecord>()
                    .eq(AuditRecord::getTargetType, 1).eq(AuditRecord::getTargetId, g.getId())
                    .eq(AuditRecord::getAuditorType, AUDITOR_SYSTEM).orderByDesc(AuditRecord::getId).last("LIMIT 1"));
            List<String> hits = sys == null || sys.getHitWords() == null ? List.of()
                    : List.of(sys.getHitWords().split(","));
            User s = sellers.get(g.getSellerId());
            list.add(new AdminGoodsVO(g.getId(), g.getTitle(), g.getDescription(), g.getCoverUrl(),
                    goodsService.images(g.getId()), g.getCategoryId(), path, path.isEmpty() ? null : path.getLast(),
                    g.getPrice(), g.getConditionLevel(), g.getTradeMode(), campus.get(g.getCampusId()),
                    g.getLocation(), g.getStock(), g.getStatus(), g.getAuditRemark(), g.getViewCount(),
                    g.getFavoriteCount(), reports.getOrDefault(g.getId(), 0), s == null ? null : new SellerInfo(s.getId(), s.getNickname(), s.getCreditScore()),
                    hits, sys == null ? null : sys.getCreatedAt(), g.getPublishedAt(), g.getCreatedAt()));
        }
        return list;
    }

    /** 商品被举报次数（含已处理的举报） */
    private Map<Long, Integer> reportCounts(List<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> counts = new HashMap<>();
        reportMapper.selectMaps(new QueryWrapper<Report>().select("target_id", "COUNT(*) AS cnt")
                        .eq("target_type", ReportTarget.GOODS).in("target_id", goodsIds).groupBy("target_id"))
                .forEach(m -> counts.put(((Number) m.get("target_id")).longValue(), ((Number) m.get("cnt")).intValue()));
        return counts;
    }
}
