package com.campustrade.module.admin.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.ReportTarget;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.module.admin.goods.AdminGoodsService;
import com.campustrade.module.admin.user.AdminUserService;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.report.entity.Report;
import com.campustrade.module.report.mapper.ReportMapper;
import com.campustrade.module.user.CreditService;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.mapper.WantPostMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 后台举报处理（接口文档 13.8） */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private static final int PENDING = 0, ESTABLISHED = 1, NOT_ESTABLISHED = 2;
    private static final int REASON_FRAUD = 3;
    private static final String OFF_SHELF = "OFF_SHELF", CLOSE_WANT = "CLOSE_WANT",
            DEDUCT_CREDIT = "DEDUCT_CREDIT", BAN = "BAN";
    private static final Set<String> ALL_ACTIONS = Set.of(OFF_SHELF, CLOSE_WANT, DEDUCT_CREDIT, BAN);

    private final ReportMapper reportMapper;
    private final GoodsMapper goodsMapper;
    private final WantPostMapper wantMapper;
    private final UserService userService;
    private final CreditService creditService;
    private final AdminGoodsService adminGoodsService;
    private final AdminUserService adminUserService;
    private final NotificationService notificationService;

    // ---------------------------------------------------------------- DTO

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class ReportQuery extends PageQuery {
        private Integer status = PENDING;
        private Integer targetType;
        private Integer reasonType;
    }

    public record HandleReq(@NotNull Boolean established, List<String> actions, Integer banDays,
                            @NotBlank @Size(max = 200, message = "处理说明不超过 200 字") String result) {
    }

    public record HandleResp(int affected) {
    }

    public record Reporter(Long id, String nickname) {
    }

    public record ReportItem(Long id, Reporter reporter, Integer targetType, Long targetId, String targetTitle,
                             Integer reasonType, String description, List<String> evidenceUrls, Integer status,
                             String result, Long handlerId, LocalDateTime handledAt, int targetReportCount,
                             LocalDateTime createdAt) {
    }

    /** 被举报对象当前快照；对象已删除时 exists=false */
    public record TargetSnapshot(boolean exists, String title, String description, String coverUrl, Integer status,
                                 Long ownerId, String ownerNickname, Integer ownerCreditScore, Integer ownerStatus) {
    }

    public record ReportDetail(ReportItem report, TargetSnapshot target) {
    }

    /** 对象所有者 + 展示用标题 */
    private record Target(Long ownerId, String title, Goods goods, WantPost want) {
    }

    // ---------------------------------------------------------------- 查询

    public PageResult<ReportItem> list(ReportQuery q) {
        Page<Report> page = reportMapper.selectPage(q.toPage(), new LambdaQueryWrapper<Report>()
                .eq(q.getStatus() != null, Report::getStatus, q.getStatus())
                .eq(q.getTargetType() != null, Report::getTargetType, q.getTargetType())
                .eq(q.getReasonType() != null, Report::getReasonType, q.getReasonType())
                .orderByAsc(q.getStatus() != null && q.getStatus() == PENDING, Report::getId)
                .orderByDesc(q.getStatus() == null || q.getStatus() != PENDING, Report::getId));
        return PageResult.of(page, items(page.getRecords()));
    }

    public ReportDetail detail(long id) {
        Report r = Biz.notNull(reportMapper.selectById(id), ErrorCode.NOT_FOUND);
        Target t = target(r.getTargetType(), r.getTargetId());
        User owner = t.ownerId() == null ? null : userService.mapByIds(List.of(t.ownerId())).get(t.ownerId());
        String description = null, coverUrl = null;
        Integer status = null;
        if (t.goods() != null) {
            description = t.goods().getDescription();
            coverUrl = t.goods().getCoverUrl();
            status = t.goods().getStatus();
        } else if (t.want() != null) {
            description = t.want().getDescription();
            status = t.want().getStatus();
        } else if (owner != null) {
            coverUrl = owner.getAvatarUrl();
        }
        TargetSnapshot snap = new TargetSnapshot(t.ownerId() != null, t.title(), description, coverUrl, status,
                t.ownerId(), owner == null ? null : owner.getNickname(),
                owner == null ? null : owner.getCreditScore(), owner == null ? null : owner.getStatus());
        return new ReportDetail(items(List.of(r)).getFirst(), snap);
    }

    // ---------------------------------------------------------------- 处理

    /** 处理一条举报；同一对象的其他待处理举报按同一结果一并处理 */
    @Transactional
    public HandleResp handle(long adminId, long id, HandleReq req) {
        Report r = Biz.notNull(reportMapper.selectById(id), ErrorCode.NOT_FOUND);
        Biz.check(r.getStatus() == PENDING, ErrorCode.CONFLICT);
        boolean established = req.established();
        Set<String> actions = established && req.actions() != null ? new LinkedHashSet<>(req.actions()) : Set.of();
        Target t = target(r.getTargetType(), r.getTargetId());
        validate(r, t, established, actions, req.banDays());

        String result = req.result().trim();
        LocalDateTime now = LocalDateTime.now();
        List<Report> same = reportMapper.selectList(new LambdaQueryWrapper<Report>()
                .eq(Report::getTargetType, r.getTargetType()).eq(Report::getTargetId, r.getTargetId())
                .eq(Report::getStatus, PENDING));
        Biz.check(!same.isEmpty(), ErrorCode.CONFLICT);
        int affected = reportMapper.update(new LambdaUpdateWrapper<Report>()
                .in(Report::getId, same.stream().map(Report::getId).toList()).eq(Report::getStatus, PENDING)
                .set(Report::getStatus, established ? ESTABLISHED : NOT_ESTABLISHED)
                .set(Report::getResult, result).set(Report::getHandlerId, adminId).set(Report::getHandledAt, now));
        Biz.check(affected == same.size(), ErrorCode.CONFLICT);

        if (actions.contains(OFF_SHELF) && t.goods().getStatus() != GoodsStatus.FORCED_OFF) {
            adminGoodsService.forceOff(adminId, t.goods().getId(), "举报成立：" + result);
        }
        if (actions.contains(CLOSE_WANT)) {
            wantMapper.update(new LambdaUpdateWrapper<WantPost>().eq(WantPost::getId, t.want().getId())
                    .set(WantPost::getStatus, WantStatus.CLOSED_BY_ADMIN));
        }
        if (actions.contains(DEDUCT_CREDIT)) {
            int delta = r.getReasonType() == REASON_FRAUD ? -30 : -5;
            creditService.change(t.ownerId(), delta, CreditReason.REPORT_ESTABLISHED, r.getId(), result, adminId);
        }
        if (actions.contains(BAN)) {
            adminUserService.ban(t.ownerId(), req.banDays(), "举报成立：" + result);
        }

        String what = targetName(r.getTargetType()) + (t.title() == null ? "" : "「" + t.title() + "」");
        same.stream().map(Report::getReporterId).distinct().forEach(uid -> notificationService.send(uid,
                NotifyType.REPORT, "你的举报已处理",
                "你举报的" + what + (established ? "经核实举报成立：" : "经核实举报不成立：") + result, null, null));
        if (established && t.ownerId() != null) {
            notificationService.send(t.ownerId(), NotifyType.REPORT, "你被举报的内容已处理",
                    "你的" + what + "被举报，经核实举报成立：" + result, null, null);
        }
        return new HandleResp(affected);
    }

    private void validate(Report r, Target t, boolean established, Set<String> actions, Integer banDays) {
        if (!established) {
            return;
        }
        Biz.check(t.ownerId() != null, ErrorCode.BAD_REQUEST, "被举报对象已不存在，只能判定不成立");
        Biz.check(ALL_ACTIONS.containsAll(actions), ErrorCode.BAD_REQUEST, "未知的处理动作");
        Biz.check(!actions.contains(OFF_SHELF) || r.getTargetType() == ReportTarget.GOODS,
                ErrorCode.BAD_REQUEST, "只有商品可以下架");
        Biz.check(!actions.contains(CLOSE_WANT) || r.getTargetType() == ReportTarget.WANT,
                ErrorCode.BAD_REQUEST, "只有求购帖可以关闭");
        Biz.check(r.getReasonType() != REASON_FRAUD || actions.contains(BAN),
                ErrorCode.BAD_REQUEST, "诈骗举报成立时必须封禁");
        Biz.check(!actions.contains(BAN) || banDays == null || banDays == 7 || banDays == 30,
                ErrorCode.BAD_REQUEST, "封禁期限只能为 7 天、30 天或永久");
    }

    // ---------------------------------------------------------------- 内部

    private List<ReportItem> items(List<Report> reports) {
        Map<Long, User> reporters = userService.mapByIds(reports.stream().map(Report::getReporterId).distinct().toList());
        return reports.stream().map(r -> {
            User u = reporters.get(r.getReporterId());
            long count = reportMapper.selectCount(new LambdaQueryWrapper<Report>()
                    .eq(Report::getTargetType, r.getTargetType()).eq(Report::getTargetId, r.getTargetId()));
            return new ReportItem(r.getId(), new Reporter(r.getReporterId(), u == null ? null : u.getNickname()),
                    r.getTargetType(), r.getTargetId(), target(r.getTargetType(), r.getTargetId()).title(),
                    r.getReasonType(), r.getDescription(), r.getEvidenceUrls() == null ? List.of() : r.getEvidenceUrls(),
                    r.getStatus(), r.getResult(), r.getHandlerId(), r.getHandledAt(), (int) count, r.getCreatedAt());
        }).toList();
    }

    /** 已删除的商品 / 求购帖查不到，返回全 null */
    private Target target(int type, long id) {
        switch (type) {
            case ReportTarget.GOODS -> {
                Goods g = goodsMapper.selectById(id);
                return g == null ? new Target(null, null, null, null) : new Target(g.getSellerId(), g.getTitle(), g, null);
            }
            case ReportTarget.WANT -> {
                WantPost w = wantMapper.selectById(id);
                return w == null ? new Target(null, null, null, null) : new Target(w.getUserId(), w.getTitle(), null, w);
            }
            default -> {
                User u = userService.mapByIds(List.of(id)).get(id);
                return u == null ? new Target(null, null, null, null) : new Target(u.getId(), u.getNickname(), null, null);
            }
        }
    }

    private static String targetName(int type) {
        return switch (type) {
            case ReportTarget.GOODS -> "商品";
            case ReportTarget.WANT -> "求购帖";
            default -> "用户";
        };
    }
}
