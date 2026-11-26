package com.campustrade.module.order;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.TxUtil;
import com.campustrade.config.AppProperties;
import com.campustrade.infra.StockCache;
import com.campustrade.infra.mq.DelayPublisher;
import com.campustrade.infra.mq.DelayType;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.auth.AuthService;
import com.campustrade.module.chat.ChatService;
import com.campustrade.module.chat.entity.Conversation;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.order.OrderDtos.CreateReq;
import com.campustrade.module.order.OrderDtos.CreateResp;
import com.campustrade.module.order.OrderDtos.DetailVO;
import com.campustrade.module.order.OrderDtos.GoodsSnapshot;
import com.campustrade.module.order.OrderDtos.LogVO;
import com.campustrade.module.order.OrderDtos.OrderCard;
import com.campustrade.module.order.OrderDtos.OrderPage;
import com.campustrade.module.order.OrderDtos.ReviewBlock;
import com.campustrade.module.order.OrderDtos.Timeline;
import com.campustrade.module.order.entity.OrderLog;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.OrderLogMapper;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.module.review.ReviewService;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 下单、买卖双方操作与订单查询；状态流转统一走 OrderFlow */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final DateTimeFormatter NO_PREFIX = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final int MEET_CODE_MAX_FAIL = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TradeOrderMapper orderMapper;
    private final OrderLogMapper logMapper;
    private final GoodsMapper goodsMapper;
    private final OrderFlow flow;
    private final UserService userService;
    private final ReviewService reviewService;
    private final PaymentService paymentService;
    private final AfterSaleService afterSaleService;
    private final ChatService chatService;
    private final DelayPublisher delayPublisher;
    private final StringRedisTemplate redis;
    private final StockCache stockCache;
    private final AppProperties props;

    // ------------------------------------------------------------------ 下单

    @Transactional
    public CreateResp create(long buyerId, CreateReq req) {
        String reqKey = "order:req:" + buyerId + ":" + req.requestId();
        Boolean first = redis.opsForValue().setIfAbsent(reqKey, "PENDING", Duration.ofMinutes(10));
        if (!Boolean.TRUE.equals(first)) {
            String existing = redis.opsForValue().get(reqKey);
            Biz.check(existing != null && !"PENDING".equals(existing), ErrorCode.CONFLICT, "订单正在创建，请稍候");
            TradeOrder done = orderMapper.selectById(Long.parseLong(existing));
            Biz.check(done != null, ErrorCode.CONFLICT, "订单正在创建，请稍候");
            return resp(done);
        }
        try {
            TradeOrder o = doCreate(buyerId, req);
            // 提交后再写订单 id：否则重复请求可能拿到 id 却查不到尚未提交的订单
            TxUtil.afterCommit(() -> redis.opsForValue().set(reqKey, String.valueOf(o.getId()), Duration.ofMinutes(10)));
            return resp(o);
        } catch (RuntimeException e) {
            redis.delete(reqKey);
            throw e;
        }
    }

    private CreateResp resp(TradeOrder o) {
        LocalDateTime payExpireAt = o.getTradeMode() == TradeMode.GUARANTEE ? flow.payExpireAt(o) : null;
        return new CreateResp(o.getId(), o.getOrderNo(), o.getStatus(), payExpireAt);
    }

    private TradeOrder doCreate(long buyerId, CreateReq req) {
        Biz.check(req.tradeMode() != TradeMode.GUARANTEE || props.pay().guaranteeEnabled(),
                ErrorCode.TRADE_MODE_UNSUPPORTED, "担保交易暂未开放，请选择面交");
        User buyer = userService.require(buyerId);
        Biz.check(!AuthService.isBanned(buyer), ErrorCode.ACCOUNT_BANNED);
        String cancels = redis.opsForValue().get(cancelCountKey(buyerId));
        Biz.check(cancels == null || Integer.parseInt(cancels) < props.rules().dailyCancelLimit(),
                ErrorCode.CANCEL_TOO_MANY);

        Biz.check(stockCache.tryDeduct(req.goodsId(), req.quantity(), () -> onSaleStock(req.goodsId())),
                ErrorCode.STOCK_NOT_ENOUGH);
        TxUtil.afterRollback(() -> stockCache.giveBack(req.goodsId(), req.quantity()));
        Goods g = deductStock(buyerId, req);
        boolean guarantee = req.tradeMode() == TradeMode.GUARANTEE;
        if (guarantee) {
            Biz.check(userService.require(g.getSellerId()).getCreditScore() >= 60, ErrorCode.TRADE_MODE_UNSUPPORTED,
                    "卖家信用分低于 60，暂不支持担保交易");
        }
        TradeOrder o = new TradeOrder();
        o.setOrderNo(nextOrderNo());
        o.setBuyerId(buyerId);
        o.setSellerId(g.getSellerId());
        o.setGoodsId(g.getId());
        o.setGoodsTitle(g.getTitle());
        o.setGoodsCover(g.getCoverUrl());
        o.setUnitPrice(g.getPrice());
        o.setQuantity(req.quantity());
        o.setTotalAmount(g.getPrice().multiply(BigDecimal.valueOf(req.quantity())));
        o.setTradeMode(req.tradeMode());
        o.setStatus(guarantee ? OrderStatus.WAIT_PAY : OrderStatus.WAIT_SELLER_CONFIRM);
        // created_at 为 DATETIME（秒），MySQL 写入时会把小数秒四舍五入；先截断，保证不晚于随后写入的消息时间
        o.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
        orderMapper.insert(o);
        flow.created(o);
        return o;
    }

    /** 预扣缓存的初始值：只缓存在售商品，其余情况交给数据库校验报具体错误 */
    private Integer onSaleStock(long goodsId) {
        Goods g = goodsMapper.selectById(goodsId);
        return g != null && g.getStatus() == GoodsStatus.ON_SALE ? g.getStock() : null;
    }

    /**
     * 条件更新扣库存（stock >= 购买数量且在售才扣），库存归 0 时商品进入"交易中"。
     * 能走到这里的请求数已被 Redis 预扣限制在库存以内，用单条条件更新而不是「读版本号 + 重试」，
     * 高并发下不会因版本冲突耗尽重试次数而少卖。
     */
    private Goods deductStock(long buyerId, CreateReq req) {
        Goods g = Biz.notNull(goodsMapper.selectById(req.goodsId()), ErrorCode.GOODS_NOT_FOUND);
        Biz.check(!g.getSellerId().equals(buyerId), ErrorCode.BUY_SELF);
        Biz.check(g.getStatus() == GoodsStatus.ON_SALE, ErrorCode.GOODS_NOT_BUYABLE);
        Biz.check(g.getTradeMode() == TradeMode.BOTH || g.getTradeMode().equals(req.tradeMode()),
                ErrorCode.TRADE_MODE_UNSUPPORTED);
        Biz.check(!userService.isBlocked(g.getSellerId(), buyerId), ErrorCode.BLOCKED_BY_SELLER);
        int q = req.quantity();
        // status 写在 stock 前面：MySQL 按从左到右赋值，IF 里比较的是扣减前的库存
        int n = goodsMapper.update(new LambdaUpdateWrapper<Goods>().eq(Goods::getId, g.getId())
                .eq(Goods::getStatus, GoodsStatus.ON_SALE).ge(Goods::getStock, q)
                .setSql("status = IF(stock = " + q + ", " + GoodsStatus.TRADING + ", status), stock = stock - " + q
                        + ", version = version + 1"));
        if (n == 1) {
            return g;
        }
        Goods now = goodsMapper.selectById(g.getId());
        Biz.check(now != null && now.getStatus() == GoodsStatus.ON_SALE, ErrorCode.GOODS_NOT_BUYABLE);
        throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
    }

    private String nextOrderNo() {
        String prefix = LocalDateTime.now().format(NO_PREFIX);
        Long seq = redis.opsForValue().increment("order:no:" + prefix);
        redis.expire("order:no:" + prefix, Duration.ofMinutes(2));
        return prefix + String.format("%03d", seq);
    }

    // ------------------------------------------------------------------ 买卖双方操作

    @Transactional
    public void sellerConfirm(long userId, long orderId) {
        TradeOrder o = requireParty(userId, orderId);
        String meetCode = String.format("%06d", RANDOM.nextInt(1_000_000));
        flow.transit(o, OrderStateMachine.SELLER_CONFIRM, roleOf(o, userId), userId, null, null,
                w -> w.set(TradeOrder::getSellerConfirmedAt, LocalDateTime.now()).set(TradeOrder::getMeetCode, meetCode));
        delayPublisher.schedule(DelayType.AUTO_COMPLETE, orderId, null);
    }

    @Transactional
    public void complete(long userId, long orderId) {
        TradeOrder o = requireParty(userId, orderId);
        flow.finish(o, OrderStateMachine.COMPLETE, roleOf(o, userId), userId);
    }

    /** 面交核销：卖家输入买家出示的 6 位码完成交易；同一订单错 5 次后锁定，只能由买家确认完成 */
    @Transactional
    public void verifyMeetCode(long userId, long orderId, String code) {
        TradeOrder o = requireParty(userId, orderId);
        Biz.check(roleOf(o, userId) == Operator.SELLER && o.getStatus() == OrderStatus.WAIT_MEET,
                ErrorCode.ORDER_STATE_INVALID);
        String failKey = "order:meet:fail:" + orderId;
        String fails = redis.opsForValue().get(failKey);
        Biz.check(fails == null || Integer.parseInt(fails) < MEET_CODE_MAX_FAIL, ErrorCode.MEET_CODE_LOCKED);
        if (o.getMeetCode() == null || !o.getMeetCode().equals(code.trim())) {
            redis.opsForValue().increment(failKey);
            redis.expire(failKey, Duration.ofDays(8));
            throw new BizException(ErrorCode.MEET_CODE_WRONG);
        }
        flow.finish(o, OrderStateMachine.VERIFY_MEET_CODE, Operator.SELLER, userId);
    }

    @Transactional
    public void cancel(long userId, long orderId, String reason) {
        TradeOrder o = requireParty(userId, orderId);
        int role = roleOf(o, userId);
        flow.cancel(o, OrderStateMachine.CANCEL, role, userId, reason.trim());
        if (role == Operator.BUYER && o.getStatus() != OrderStatus.WAIT_MEET) {
            String key = cancelCountKey(userId);
            redis.opsForValue().increment(key);
            redis.expire(key, Duration.ofDays(1));
        }
    }

    @Transactional
    public void deliver(long userId, long orderId) {
        TradeOrder o = requireParty(userId, orderId);
        flow.transit(o, OrderStateMachine.DELIVER, roleOf(o, userId), userId, null, null,
                w -> w.set(TradeOrder::getDeliveredAt, LocalDateTime.now()));
        delayPublisher.schedule(DelayType.AUTO_CONFIRM, orderId, null);
    }

    @Transactional
    public void confirmReceipt(long userId, long orderId) {
        TradeOrder o = requireParty(userId, orderId);
        flow.finish(o, OrderStateMachine.CONFIRM_RECEIPT, roleOf(o, userId), userId);
    }

    // ------------------------------------------------------------------ 查询

    public OrderPage list(long userId, String role, List<Integer> statuses, PageQuery pq) {
        boolean asBuyer = !"SELLER".equals(role);
        Page<TradeOrder> page = orderMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<TradeOrder>()
                .eq(asBuyer ? TradeOrder::getBuyerId : TradeOrder::getSellerId, userId)
                .in(statuses != null && !statuses.isEmpty(), TradeOrder::getStatus, statuses)
                .orderByDesc(TradeOrder::getId));
        Map<Long, User> users = userService.mapByIds(page.getRecords().stream()
                .map(o -> asBuyer ? o.getSellerId() : o.getBuyerId()).distinct().toList());
        List<OrderCard> list = new ArrayList<>();
        for (TradeOrder o : page.getRecords()) {
            boolean reviewed = reviewService.hasReviewed(o.getId(), userId);
            list.add(new OrderCard(o.getId(), o.getOrderNo(), o.getGoodsId(), o.getGoodsTitle(), o.getGoodsCover(),
                    o.getUnitPrice(), o.getQuantity(), o.getTotalAmount(), o.getTradeMode(), o.getStatus(),
                    UserService.brief(users.get(asBuyer ? o.getSellerId() : o.getBuyerId())),
                    actions(o, roleOf(o, userId), reviewed), reviewed, o.getCreatedAt()));
        }
        Map<String, Integer> counts = new HashMap<>();
        orderMapper.selectMaps(new QueryWrapper<TradeOrder>().select("status", "COUNT(*) AS cnt")
                        .eq(asBuyer ? "buyer_id" : "seller_id", userId).groupBy("status"))
                .forEach(m -> counts.put(String.valueOf(m.get("status")), ((Number) m.get("cnt")).intValue()));
        return new OrderPage(list, (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize(), counts);
    }

    public DetailVO detail(long userId, long orderId) {
        TradeOrder o = requireParty(userId, orderId);
        return detailOf(o, roleOf(o, userId), userId);
    }

    /** 买卖双方与后台共用；后台查看时 role = ADMIN、viewerId = null */
    public DetailVO detailOf(TradeOrder o, int role, Long viewerId) {
        Goods g = goodsMapper.selectById(o.getGoodsId());
        GoodsSnapshot goods = new GoodsSnapshot(o.getGoodsId(), o.getGoodsTitle(), o.getGoodsCover(),
                o.getUnitPrice(), g == null ? null : g.getLocation(),
                g == null ? null : userService.campusName(g.getCampusId()));
        Map<Long, User> users = userService.mapByIds(List.of(o.getBuyerId(), o.getSellerId()));
        boolean reviewed = viewerId != null && reviewService.hasReviewed(o.getId(), viewerId);
        ReviewBlock review = new ReviewBlock(null, null, null);
        if (o.getStatus() == OrderStatus.FINISHED && viewerId != null) {
            Object[] mt = reviewService.mineAndTheirs(o.getId(), viewerId);
            review = new ReviewBlock(o.getReviewDeadline(), mt[0], mt[1]);
        }
        List<LogVO> logs = logMapper.selectList(new LambdaQueryWrapper<OrderLog>()
                        .eq(OrderLog::getOrderId, o.getId()).orderByAsc(OrderLog::getId)).stream()
                .map(l -> new LogVO(l.getFromStatus(), l.getToStatus(), l.getAction(), l.getOperatorType(),
                        l.getRemark(), l.getCreatedAt())).toList();
        Conversation conv = chatService.findPair(o.getBuyerId(), o.getSellerId());
        String myRole = role == Operator.BUYER ? "BUYER" : role == Operator.SELLER ? "SELLER" : "ADMIN";
        List<String> actions = role == Operator.ADMIN ? List.of() : actions(o, role, reviewed);
        OrderDtos.Deadline deadline = flow.deadline(o);
        if (deadline == null && o.getStatus() == OrderStatus.AFTER_SALE) {
            AfterSaleService.AfterSaleVO open = afterSaleService.latestOf(o.getId(), role);
            if (open != null && open.status() == AfterSaleService.PENDING) {
                deadline = new OrderDtos.Deadline("AUTO_AGREE_AFTER_SALE", open.sellerDeadline());
            } else if (open != null && open.appealDeadline() != null) {
                deadline = new OrderDtos.Deadline(OrderStateMachine.AUTO_CONFIRM_AFTER_REJECT, open.appealDeadline());
            }
        }
        return new DetailVO(o.getId(), o.getOrderNo(), myRole, goods, o.getQuantity(), o.getTotalAmount(),
                o.getTradeMode(), o.getStatus(), UserService.brief(users.get(o.getBuyerId())),
                UserService.brief(users.get(o.getSellerId())), conv == null ? null : conv.getId(),
                new Timeline(o.getCreatedAt(), o.getSellerConfirmedAt(), o.getPaidAt(), o.getDeliveredAt(),
                        o.getFinishedAt(), o.getCanceledAt()),
                deadline, o.getCancelReason(), o.getCancelBy(), paymentService.view(o.getId()),
                afterSaleService.latestOf(o.getId(), role), review, actions, logs,
                role == Operator.BUYER && o.getStatus() == OrderStatus.WAIT_MEET ? o.getMeetCode() : null);
    }

    private List<String> actions(TradeOrder o, int role, boolean reviewed) {
        List<String> actions = new ArrayList<>(OrderStateMachine.userActions(o.getStatus(), role));
        if (!reviewed && reviewService.canReview(o)) {
            actions.add(OrderStateMachine.REVIEW);
        }
        return actions;
    }

    private TradeOrder requireParty(long userId, long orderId) {
        TradeOrder o = orderMapper.selectById(orderId);
        Biz.notNull(o, ErrorCode.NOT_FOUND);
        Biz.check(o.getBuyerId() == userId || o.getSellerId() == userId, ErrorCode.FORBIDDEN);
        return o;
    }

    private static int roleOf(TradeOrder o, long userId) {
        return o.getBuyerId() == userId ? Operator.BUYER : Operator.SELLER;
    }

    private static String cancelCountKey(long userId) {
        return "order:cancel:" + userId + ":" + LocalDate.now();
    }
}
