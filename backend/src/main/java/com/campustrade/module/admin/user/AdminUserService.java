package com.campustrade.module.admin.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.ReportTarget;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.module.common.CommonService;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.report.entity.Report;
import com.campustrade.module.report.mapper.ReportMapper;
import com.campustrade.module.user.CreditService;
import com.campustrade.module.user.UserDtos.CreditLogVO;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.user.mapper.UserMapper;
import com.campustrade.security.TokenStore;
import com.campustrade.security.TokenType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 后台用户管理：查询、禁言、封禁、解封、调整信用分（接口文档 13.5） */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserMapper userMapper;
    private final GoodsMapper goodsMapper;
    private final TradeOrderMapper orderMapper;
    private final ReportMapper reportMapper;
    private final UserService userService;
    private final CreditService creditService;
    private final CommonService commonService;
    private final NotificationService notificationService;
    private final TokenStore tokenStore;

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class UserQuery extends PageQuery {
        private String keyword;
        private Long campusId;
        private Integer status;
        private Integer creditMin;
        private Integer creditMax;
    }

    public record AdminUserVO(Long id, String email, String nickname, String avatarUrl, String college,
                              String campusName, Integer creditScore, String creditLevel, BigDecimal balance,
                              Integer status, LocalDateTime banUntil, LocalDateTime lastLoginAt,
                              LocalDateTime createdAt, int onSaleCount, int soldCount, int boughtCount,
                              int reportedCount) {
    }

    public record TradeStats(int onSaleCount, int soldCount, int boughtCount, int activeOrderCount,
                             String goodRate) {
    }

    public record AdminUserDetail(AdminUserVO user, String bio, TradeStats stats) {
    }

    public PageResult<AdminUserVO> list(UserQuery q) {
        String kw = q.getKeyword() == null ? null : q.getKeyword().trim();
        LambdaQueryWrapper<User> w = new LambdaQueryWrapper<User>()
                .and(kw != null && !kw.isEmpty(), x -> x.like(User::getNickname, kw).or().like(User::getEmail, kw))
                .eq(q.getCampusId() != null, User::getCampusId, q.getCampusId())
                .eq(q.getStatus() != null, User::getStatus, q.getStatus())
                .ge(q.getCreditMin() != null, User::getCreditScore, q.getCreditMin())
                .le(q.getCreditMax() != null, User::getCreditScore, q.getCreditMax())
                .orderByDesc(User::getId);
        Page<User> page = userMapper.selectPage(q.toPage(), w);
        return PageResult.of(page, toVOs(page.getRecords()));
    }

    public AdminUserDetail detail(long id) {
        User u = userService.require(id);
        int sold = countOrders(TradeOrder::getSellerId, id, OrderStatus.FINISHED);
        int bought = countOrders(TradeOrder::getBuyerId, id, OrderStatus.FINISHED);
        int active = orderMapper.selectCount(new LambdaQueryWrapper<TradeOrder>()
                .and(x -> x.eq(TradeOrder::getBuyerId, id).or().eq(TradeOrder::getSellerId, id))
                .lt(TradeOrder::getStatus, OrderStatus.FINISHED)).intValue();
        TradeStats stats = new TradeStats(userService.onSaleCount(id), sold, bought, active, userService.goodRate(id));
        return new AdminUserDetail(toVOs(List.of(u)).getFirst(), u.getBio(), stats);
    }

    public PageResult<CreditLogVO> creditLogs(long id, PageQuery pq) {
        return userService.creditLogs(id, pq);
    }

    /** 禁言：不能发消息、发布商品与求购；days 为 null 表示永久 */
    public void mute(long id, Integer days, String reason) {
        User u = userService.require(id);
        Biz.check(u.getStatus() != UserStatus.BANNED, ErrorCode.BAD_REQUEST, "用户已封禁");
        punish(id, UserStatus.MUTED, days);
        notificationService.send(id, NotifyType.SYSTEM, "你已被禁言",
                "原因：" + reason + "；期限：" + period(days), null, null);
    }

    /** 封禁：token 全部失效，在售/待审商品下架；进行中订单不自动处理 */
    @Transactional
    public void ban(long id, Integer days, String reason) {
        userService.require(id);
        punish(id, UserStatus.BANNED, days);
        tokenStore.invalidateAll(TokenType.USER, id);
        goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getSellerId, id)
                .in(Goods::getStatus, GoodsStatus.ON_SALE, GoodsStatus.PENDING)
                .set(Goods::getStatus, GoodsStatus.OFF_SHELF));
        notificationService.send(id, NotifyType.SYSTEM, "你已被封禁",
                "原因：" + reason + "；期限：" + period(days), null, null);
    }

    public void unban(long id) {
        userService.require(id);
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, id)
                .set(User::getStatus, UserStatus.NORMAL).set(User::getBanUntil, null));
        notificationService.send(id, NotifyType.SYSTEM, "处罚已解除", "你的账号已恢复正常", null, null);
    }

    public void adjustCredit(long adminId, long id, int delta, String reason) {
        userService.require(id);
        Biz.check(delta != 0, ErrorCode.BAD_REQUEST, "调整分值不能为 0");
        creditService.change(id, delta, CreditReason.ADMIN_ADJUST, null, reason, adminId);
    }

    private void punish(long id, int status, Integer days) {
        Biz.check(days == null || days == 7 || days == 30, ErrorCode.BAD_REQUEST, "期限只能为 7 天、30 天或永久");
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, id).set(User::getStatus, status)
                .set(User::getBanUntil, days == null ? null : LocalDateTime.now().plusDays(days)));
    }

    private static String period(Integer days) {
        return days == null ? "永久" : days + " 天";
    }

    private int countOrders(com.baomidou.mybatisplus.core.toolkit.support.SFunction<TradeOrder, ?> col, long id,
                            int status) {
        return orderMapper.selectCount(new LambdaQueryWrapper<TradeOrder>().eq(col, id)
                .eq(TradeOrder::getStatus, status)).intValue();
    }

    /** 列表里的在售 / 卖出 / 买入 / 被举报次数按本页用户分组统计，不逐个查询 */
    private List<AdminUserVO> toVOs(List<User> users) {
        if (users.isEmpty()) {
            return List.of();
        }
        Map<Long, String> campus = commonService.campusNames();
        List<Long> ids = users.stream().map(User::getId).toList();
        Map<Long, Integer> onSale = countBy(goodsMapper, new QueryWrapper<Goods>()
                .eq("status", GoodsStatus.ON_SALE), "seller_id", ids);
        Map<Long, Integer> sold = countBy(orderMapper, new QueryWrapper<TradeOrder>()
                .eq("status", OrderStatus.FINISHED), "seller_id", ids);
        Map<Long, Integer> bought = countBy(orderMapper, new QueryWrapper<TradeOrder>()
                .eq("status", OrderStatus.FINISHED), "buyer_id", ids);
        Map<Long, Integer> reported = countBy(reportMapper, new QueryWrapper<Report>()
                .eq("target_type", ReportTarget.USER), "target_id", ids);
        return users.stream().map(u -> new AdminUserVO(u.getId(), u.getEmail(), u.getNickname(), u.getAvatarUrl(),
                u.getCollege(), campus.get(u.getCampusId()), u.getCreditScore(),
                CreditLevel.of(u.getCreditScore()).name(), u.getBalance(), u.getStatus(), u.getBanUntil(),
                u.getLastLoginAt(), u.getCreatedAt(), onSale.getOrDefault(u.getId(), 0),
                sold.getOrDefault(u.getId(), 0), bought.getOrDefault(u.getId(), 0),
                reported.getOrDefault(u.getId(), 0))).toList();
    }

    /** SELECT col, COUNT(*) ... WHERE col IN ids GROUP BY col，返回 id → 数量 */
    private static <T> Map<Long, Integer> countBy(BaseMapper<T> mapper, QueryWrapper<T> w, String col, List<Long> ids) {
        Map<Long, Integer> counts = new HashMap<>();
        mapper.selectMaps(w.select(col, "COUNT(*) AS cnt").in(col, ids).groupBy(col))
                .forEach(m -> counts.put(((Number) m.get(col)).longValue(), ((Number) m.get("cnt")).intValue()));
        return counts;
    }
}
