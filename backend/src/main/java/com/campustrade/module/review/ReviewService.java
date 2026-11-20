package com.campustrade.module.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.entity.Review;
import com.campustrade.entity.TradeOrder;
import com.campustrade.entity.User;
import com.campustrade.infra.SensitiveWordService;
import com.campustrade.mapper.ReviewMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.user.CreditService;
import com.campustrade.module.user.UserDtos.UserBrief;
import com.campustrade.module.user.UserService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** 交易互评：双方都评或到期后才公开，公开时结算信用分（PRD 4.7） */
@Service
@RequiredArgsConstructor
public class ReviewService {

    public static final int ROLE_BUYER = 1;
    public static final int ROLE_SELLER = 2;
    private static final int RATING_GOOD = 1;
    private static final int RATING_BAD = 3;

    /** 可选标签，键为评价人身份 */
    public static final Map<String, List<String>> TAGS = Map.of(
            "1", List.of("描述相符", "准时赴约", "沟通愉快", "价格实在"),
            "2", List.of("准时赴约", "付款爽快", "沟通愉快"));

    private final ReviewMapper reviewMapper;
    private final TradeOrderMapper orderMapper;
    private final UserService userService;
    private final CreditService creditService;
    private final SensitiveWordService sensitiveWordService;

    public record SubmitReq(@NotNull @Min(1) @Max(3) Integer rating,
                            @Size(max = 200) String content,
                            @Size(max = 5) List<String> tags) {
    }

    public record ReplyReq(@NotBlank @Size(max = 200) String reply) {
    }

    public record ReviewVO(Long id, Integer role, Integer rating, String content, List<String> tags, String reply,
                           boolean isDefault, boolean visible, LocalDateTime createdAt) {
    }

    public record PublicReviewVO(Long id, Integer role, Integer rating, String content, List<String> tags,
                                 String reply, boolean isDefault, UserBrief reviewer, String goodsTitle,
                                 LocalDateTime createdAt) {
    }

    public record SubmittedFlag(boolean submitted) {
    }

    @Transactional
    public ReviewVO submit(long userId, long orderId, SubmitReq req) {
        TradeOrder o = orderMapper.selectById(orderId);
        Biz.check(o != null && (o.getBuyerId() == userId || o.getSellerId() == userId), ErrorCode.NOT_FOUND);
        Biz.check(canReview(o), ErrorCode.REVIEW_NOT_ALLOWED);
        int role = o.getBuyerId() == userId ? ROLE_BUYER : ROLE_SELLER;
        List<String> allowed = TAGS.get(String.valueOf(role));
        List<String> tags = req.tags() == null ? List.of() : req.tags();
        Biz.check(allowed.containsAll(tags), ErrorCode.BAD_REQUEST, "标签不合法");

        Review r = new Review();
        r.setOrderId(orderId);
        r.setReviewerId(userId);
        r.setRevieweeId(role == ROLE_BUYER ? o.getSellerId() : o.getBuyerId());
        r.setRole(role);
        r.setRating(req.rating());
        r.setContent(req.content() == null ? null : sensitiveWordService.replace(req.content().trim()));
        r.setTags(tags);
        r.setIsDefault(0);
        r.setVisible(0);
        try {
            reviewMapper.insert(r);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.REVIEW_DUPLICATED);
        }
        List<Review> both = byOrder(orderId);
        if (both.size() == 2) {
            publish(both);
        }
        return toVO(reviewMapper.selectById(r.getId()));
    }

    public void reply(long userId, long reviewId, String reply) {
        Review r = reviewMapper.selectById(reviewId);
        Biz.check(r != null && r.getRevieweeId() == userId && r.getVisible() == 1, ErrorCode.NOT_FOUND);
        Biz.check(r.getReply() == null, ErrorCode.REPLY_DUPLICATED);
        int n = reviewMapper.update(new LambdaUpdateWrapper<Review>().eq(Review::getId, reviewId)
                .isNull(Review::getReply).set(Review::getReply, sensitiveWordService.replace(reply.trim())));
        Biz.check(n == 1, ErrorCode.REPLY_DUPLICATED);
    }

    public PageResult<PublicReviewVO> receivedBy(long userId, Integer rating, PageQuery pq) {
        Page<Review> page = reviewMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<Review>()
                .eq(Review::getRevieweeId, userId).eq(Review::getVisible, 1)
                .eq(rating != null, Review::getRating, rating).orderByDesc(Review::getId));
        Map<Long, User> reviewers = userService.mapByIds(page.getRecords().stream().map(Review::getReviewerId).toList());
        List<Long> orderIds = page.getRecords().stream().map(Review::getOrderId).toList();
        Map<Long, String> titles = orderIds.isEmpty() ? Map.of() : orderMapper.selectByIds(orderIds).stream()
                .collect(java.util.stream.Collectors.toMap(TradeOrder::getId, TradeOrder::getGoodsTitle));
        return PageResult.of(page, r -> new PublicReviewVO(r.getId(), r.getRole(), r.getRating(), r.getContent(),
                r.getTags(), r.getReply(), r.getIsDefault() == 1, UserService.brief(reviewers.get(r.getReviewerId())),
                titles.get(r.getOrderId()), r.getCreatedAt()));
    }

    // ---------------------------------------------------------- 供订单模块使用

    public boolean canReview(TradeOrder o) {
        return o.getStatus() == OrderStatus.FINISHED && o.getReviewDeadline() != null
                && !LocalDateTime.now().isAfter(o.getReviewDeadline());
    }

    public boolean hasReviewed(long orderId, long userId) {
        return reviewMapper.exists(new LambdaQueryWrapper<Review>()
                .eq(Review::getOrderId, orderId).eq(Review::getReviewerId, userId));
    }

    /** 订单详情里的 mine / theirs：对方评价未公开时只返回 submitted 标记 */
    public Object[] mineAndTheirs(long orderId, long userId) {
        Review mine = null;
        Review theirs = null;
        for (Review r : byOrder(orderId)) {
            if (r.getReviewerId() == userId) {
                mine = r;
            } else {
                theirs = r;
            }
        }
        Object theirsView = theirs == null ? null : (theirs.getVisible() == 1 ? toVO(theirs) : new SubmittedFlag(true));
        return new Object[]{mine == null ? null : toVO(mine), theirsView};
    }

    /** 到期未评：补默认好评并公开（定时任务调用） */
    @Transactional
    public void closeExpired(TradeOrder o) {
        List<Review> existing = byOrder(o.getId());
        for (long reviewer : List.of(o.getBuyerId(), o.getSellerId())) {
            if (existing.stream().noneMatch(r -> r.getReviewerId() == reviewer)) {
                Review r = new Review();
                r.setOrderId(o.getId());
                r.setReviewerId(reviewer);
                boolean isBuyer = reviewer == o.getBuyerId();
                r.setRevieweeId(isBuyer ? o.getSellerId() : o.getBuyerId());
                r.setRole(isBuyer ? ROLE_BUYER : ROLE_SELLER);
                r.setRating(RATING_GOOD);
                r.setTags(List.of());
                r.setIsDefault(1);
                r.setVisible(0);
                try {
                    reviewMapper.insert(r);
                } catch (DuplicateKeyException ignored) {
                    // 用户恰好在此刻提交了评价
                }
            }
        }
        publish(byOrder(o.getId()));
    }

    private void publish(List<Review> reviews) {
        for (Review r : reviews) {
            int n = reviewMapper.update(new LambdaUpdateWrapper<Review>().eq(Review::getId, r.getId())
                    .eq(Review::getVisible, 0).set(Review::getVisible, 1));
            if (n == 0) {
                continue;
            }
            if (r.getRating() == RATING_GOOD) {
                creditService.addWithMonthlyCap(r.getRevieweeId(), CreditReason.GOOD_REVIEW, r.getOrderId(), null);
            } else if (r.getRating() == RATING_BAD) {
                creditService.change(r.getRevieweeId(), -2, CreditReason.BAD_REVIEW, r.getOrderId(), null, null);
            }
        }
    }

    private List<Review> byOrder(long orderId) {
        return reviewMapper.selectList(new LambdaQueryWrapper<Review>().eq(Review::getOrderId, orderId));
    }

    private static ReviewVO toVO(Review r) {
        return new ReviewVO(r.getId(), r.getRole(), r.getRating(), r.getContent(), r.getTags(), r.getReply(),
                r.getIsDefault() == 1, r.getVisible() == 1, r.getCreatedAt());
    }
}
