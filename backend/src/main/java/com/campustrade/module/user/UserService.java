package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.entity.BalanceLog;
import com.campustrade.entity.Campus;
import com.campustrade.entity.CreditAppeal;
import com.campustrade.entity.CreditLog;
import com.campustrade.entity.Favorite;
import com.campustrade.entity.Goods;
import com.campustrade.entity.Review;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.entity.UserBlock;
import com.campustrade.entity.UserFollow;
import com.campustrade.infra.SensitiveWordService;
import com.campustrade.mapper.BalanceLogMapper;
import com.campustrade.mapper.CampusMapper;
import com.campustrade.mapper.CreditAppealMapper;
import com.campustrade.mapper.CreditLogMapper;
import com.campustrade.mapper.FavoriteMapper;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.mapper.UserBlockMapper;
import com.campustrade.mapper.UserFollowMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.auth.AuthService;
import com.campustrade.module.user.UserDtos.BalanceLogVO;
import com.campustrade.module.user.UserDtos.BlockVO;
import com.campustrade.module.user.UserDtos.CreditLogVO;
import com.campustrade.module.user.UserDtos.MeVO;
import com.campustrade.module.user.UserDtos.ProfileVO;
import com.campustrade.module.user.UserDtos.Stats;
import com.campustrade.module.user.UserDtos.UpdateMeReq;
import com.campustrade.module.user.UserDtos.UserBrief;
import com.campustrade.security.TokenStore;
import com.campustrade.security.TokenType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final CampusMapper campusMapper;
    private final GoodsMapper goodsMapper;
    private final TradeOrderMapper orderMapper;
    private final FavoriteMapper favoriteMapper;
    private final ReviewMapper reviewMapper;
    private final CreditLogMapper creditLogMapper;
    private final BalanceLogMapper balanceLogMapper;
    private final UserBlockMapper blockMapper;
    private final UserFollowMapper followMapper;
    private final CreditAppealMapper appealMapper;
    private final SensitiveWordService sensitiveWordService;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;
    private final AuthService authService;

    public User require(long id) {
        return Biz.notNull(userMapper.selectById(id), ErrorCode.NOT_FOUND);
    }

    /** 发布商品 / 求购前置校验：非禁言、非封禁、信用 ≥ 40 */
    public User requireCanPublish(long id) {
        User u = require(id);
        Biz.check(!AuthService.isBanned(u), ErrorCode.ACCOUNT_BANNED);
        Biz.check(u.getStatus() != UserStatus.MUTED, ErrorCode.ACCOUNT_MUTED);
        Biz.check(u.getCreditScore() >= 40, ErrorCode.CREDIT_RESTRICTED);
        return u;
    }

    public Map<Long, User> mapByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userMapper.selectByIds(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
    }

    public static UserBrief brief(User u) {
        return u == null ? null : new UserBrief(u.getId(), u.getNickname(), u.getAvatarUrl());
    }

    public String campusName(Long campusId) {
        Campus c = campusId == null ? null : campusMapper.selectById(campusId);
        return c == null ? null : c.getName();
    }

    /** 是否 blocker 拉黑了 blocked */
    public boolean isBlocked(long blockerId, long blockedId) {
        return blockMapper.exists(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getUserId, blockerId).eq(UserBlock::getBlockedUserId, blockedId));
    }

    /** 好评率：公开评价中好评占比，保留 1 位；无评价返回 null */
    public String goodRate(long userId) {
        long total = reviewCount(userId);
        if (total == 0) {
            return null;
        }
        long good = reviewMapper.selectCount(new LambdaQueryWrapper<Review>()
                .eq(Review::getRevieweeId, userId).eq(Review::getVisible, 1).eq(Review::getRating, 1));
        return BigDecimal.valueOf(good * 100).divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP)
                .toPlainString();
    }

    public int onSaleCount(long userId) {
        return goodsMapper.selectCount(new LambdaQueryWrapper<Goods>()
                .eq(Goods::getSellerId, userId).eq(Goods::getStatus, GoodsStatus.ON_SALE)).intValue();
    }

    public MeVO me(long id) {
        User u = require(id);
        Stats stats = new Stats(onSaleCount(id), finishedOrders(id, false), finishedOrders(id, true),
                favoriteMapper.selectCount(new LambdaQueryWrapper<Favorite>().eq(Favorite::getUserId, id)).intValue(),
                goodRate(id));
        return new MeVO(u.getId(), u.getEmail(), true, u.getNickname(), u.getAvatarUrl(), u.getCollege(),
                u.getCampusId(), campusName(u.getCampusId()), u.getBio(), u.getCreditScore(),
                CreditLevel.of(u.getCreditScore()).name(), u.getBalance(), u.getStatus(), u.getBanUntil(), stats,
                u.getCreatedAt(), Integer.valueOf(1).equals(u.getEmailNotify()));
    }

    public MeVO updateMe(long id, UpdateMeReq req) {
        User u = require(id);
        LambdaUpdateWrapper<User> w = new LambdaUpdateWrapper<User>().eq(User::getId, id);
        boolean changed = false;
        if (req.nickname() != null) {
            w.set(User::getNickname, req.nickname().trim());
            changed = true;
        }
        if (req.avatarUrl() != null) {
            w.set(User::getAvatarUrl, req.avatarUrl());
            changed = true;
        }
        if (req.college() != null) {
            w.set(User::getCollege, req.college().trim());
            changed = true;
        }
        if (req.campusId() != null) {
            Campus c = campusMapper.selectById(req.campusId());
            Biz.check(c != null && c.getSchoolId().equals(u.getSchoolId()), ErrorCode.BAD_REQUEST, "校区不存在");
            w.set(User::getCampusId, req.campusId());
            changed = true;
        }
        if (req.emailNotify() != null) {
            w.set(User::getEmailNotify, req.emailNotify() ? 1 : 0);
            changed = true;
        }
        if (req.bio() != null) {
            w.set(User::getBio, sensitiveWordService.replace(req.bio().trim()));
            changed = true;
        }
        if (changed) {
            userMapper.update(w);
        }
        return me(id);
    }

    public com.campustrade.module.auth.AuthDtos.LoginResp changePassword(long id, String oldPwd, String newPwd) {
        User u = require(id);
        Biz.check(passwordEncoder.matches(oldPwd, u.getPasswordHash()), ErrorCode.BAD_CREDENTIALS, "原密码错误");
        AuthService.checkPassword(newPwd);
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, id)
                .set(User::getPasswordHash, passwordEncoder.encode(newPwd)));
        tokenStore.invalidateAll(TokenType.USER, id);
        return authService.loginResp(u);
    }

    public PageResult<CreditLogVO> creditLogs(long userId, PageQuery pq) {
        Page<CreditLog> page = creditLogMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<CreditLog>()
                .eq(CreditLog::getUserId, userId).orderByDesc(CreditLog::getId));
        List<Long> ids = page.getRecords().stream().map(CreditLog::getId).toList();
        Map<Long, Integer> appeals = ids.isEmpty() ? Map.of() : appealMapper.selectList(
                        new LambdaQueryWrapper<CreditAppeal>().in(CreditAppeal::getCreditLogId, ids))
                .stream().collect(Collectors.toMap(CreditAppeal::getCreditLogId, CreditAppeal::getStatus));
        LocalDateTime appealSince = LocalDateTime.now().minusDays(CreditAppealService.APPEAL_DAYS);
        return PageResult.of(page, c -> new CreditLogVO(c.getId(), c.getDelta(), c.getScoreAfter(), c.getReasonType(),
                CreditService.reasonText(c.getReasonType()), c.getRefId(), c.getRemark(), c.getCreatedAt(),
                // 与 CreditAppealService.apply 的校验一致
                c.getDelta() < 0 && c.getReasonType() != CreditReason.APPEAL_PASSED
                        && c.getCreatedAt().isAfter(appealSince) && !appeals.containsKey(c.getId()),
                appeals.get(c.getId())));
    }

    public PageResult<BalanceLogVO> balanceLogs(long userId, PageQuery pq) {
        Page<BalanceLog> page = balanceLogMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<BalanceLog>()
                .eq(BalanceLog::getUserId, userId).orderByDesc(BalanceLog::getId));
        List<Long> orderIds = page.getRecords().stream().map(BalanceLog::getOrderId).distinct().toList();
        Map<Long, String> orderNos = orderIds.isEmpty() ? Map.of() : orderMapper.selectByIds(orderIds).stream()
                .collect(Collectors.toMap(TradeOrder::getId, TradeOrder::getOrderNo));
        return PageResult.of(page, b -> new BalanceLogVO(b.getId(),
                (b.getAmount().signum() > 0 ? "+" : "") + b.getAmount().setScale(2, RoundingMode.HALF_UP).toPlainString(),
                b.getBalanceAfter(), b.getType(), b.getOrderId(), orderNos.get(b.getOrderId()), b.getCreatedAt()));
    }

    public PageResult<BlockVO> blocks(long userId, PageQuery pq) {
        Page<UserBlock> page = blockMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getUserId, userId).orderByDesc(UserBlock::getId));
        Map<Long, User> users = mapByIds(page.getRecords().stream().map(UserBlock::getBlockedUserId).toList());
        return PageResult.of(page, b -> {
            User u = users.get(b.getBlockedUserId());
            return new BlockVO(b.getBlockedUserId(), u == null ? null : u.getNickname(),
                    u == null ? null : u.getAvatarUrl(), b.getCreatedAt());
        });
    }

    public void block(long userId, long targetId) {
        Biz.check(userId != targetId, ErrorCode.BAD_REQUEST, "不能拉黑自己");
        require(targetId);
        if (isBlocked(userId, targetId)) {
            return;
        }
        UserBlock b = new UserBlock();
        b.setUserId(userId);
        b.setBlockedUserId(targetId);
        try {
            blockMapper.insert(b);
        } catch (DuplicateKeyException ignored) {
            // 并发重复拉黑，幂等
        }
    }

    public void unblock(long userId, long targetId) {
        blockMapper.delete(new LambdaQueryWrapper<UserBlock>()
                .eq(UserBlock::getUserId, userId).eq(UserBlock::getBlockedUserId, targetId));
    }

    public ProfileVO profile(long id, Long viewerId) {
        User u = Biz.notNull(userMapper.selectById(id), ErrorCode.NOT_FOUND);
        int score = u.getCreditScore();
        return new ProfileVO(u.getId(), u.getNickname(), u.getAvatarUrl(), u.getCollege(), campusName(u.getCampusId()),
                u.getBio(), score, CreditLevel.of(score).name(), score >= 90, goodRate(id), (int) reviewCount(id),
                onSaleCount(id), finishedOrders(id, false), viewerId != null && isBlocked(viewerId, id),
                u.getStatus(), u.getCreatedAt().toLocalDate(),
                viewerId != null && followMapper.exists(new LambdaQueryWrapper<UserFollow>()
                        .eq(UserFollow::getUserId, viewerId).eq(UserFollow::getFolloweeId, id)),
                followMapper.selectCount(new LambdaQueryWrapper<UserFollow>().eq(UserFollow::getFolloweeId, id)).intValue());
    }

    private long reviewCount(long userId) {
        return reviewMapper.selectCount(new LambdaQueryWrapper<Review>()
                .eq(Review::getRevieweeId, userId).eq(Review::getVisible, 1));
    }

    private int finishedOrders(long userId, boolean asBuyer) {
        return orderMapper.selectCount(new LambdaQueryWrapper<TradeOrder>()
                .eq(asBuyer ? TradeOrder::getBuyerId : TradeOrder::getSellerId, userId)
                .eq(TradeOrder::getStatus, OrderStatus.FINISHED)).intValue();
    }
}
