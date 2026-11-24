package com.campustrade.module.admin.trade;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.OpLog;
import com.campustrade.module.aftersale.AfterSaleService.AfterSaleVO;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.aftersale.entity.AfterSale;
import com.campustrade.module.aftersale.mapper.AfterSaleMapper;
import com.campustrade.module.chat.ChatService.MessagePage;
import com.campustrade.module.chat.ChatService.MessageVO;
import com.campustrade.module.chat.ChatService;
import com.campustrade.module.chat.entity.Conversation;
import com.campustrade.module.order.OrderDtos.DetailVO;
import com.campustrade.module.order.OrderService;
import com.campustrade.module.order.OrderStateMachine;
import com.campustrade.module.order.entity.OrderLog;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.OrderLogMapper;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.user.UserDtos.UserBrief;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** 后台订单查询（只读）与纠纷裁决（接口文档 13.6、13.7） */
@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
public class AdminTradeController {

    private static final int DISPUTE_MESSAGES = 50;

    private final TradeOrderMapper orderMapper;
    private final AfterSaleMapper afterSaleMapper;
    private final OrderLogMapper logMapper;
    private final OrderService orderService;
    private final AfterSaleService afterSaleService;
    private final ChatService chatService;
    private final UserService userService;

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class OrderQuery extends PageQuery {
        private String orderNo;
        private Long userId;
        private Integer status;
        private Integer tradeMode;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
    }

    public record AdminOrderVO(Long id, String orderNo, String goodsTitle, String goodsCover, BigDecimal totalAmount,
                               Integer quantity, Integer tradeMode, Integer status, UserBrief buyer,
                               UserBrief seller, LocalDateTime createdAt) {
    }

    public record AdminOrderDetail(DetailVO order, List<AfterSaleVO> afterSales) {
    }

    /** appealedAt：买家申请平台介入的时间（取该售后发起后最近一次「申请介入」日志） */
    public record DisputeItem(AfterSaleVO afterSale, AdminOrderVO order, LocalDateTime appealedAt) {
    }

    public record DisputeDetail(AfterSaleVO afterSale, AdminOrderDetail order, String buyerAppealRemark,
                                List<MessageVO> messages, boolean messagesHasMore) {
    }

    public record ArbitrateReq(@NotBlank @Pattern(regexp = "REFUND|RELEASE") String result,
                               @NotBlank @Size(max = 500) String reason) {
    }

    // ------------------------------------------------------------------ 订单

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('order:view')")
    public R<PageResult<AdminOrderVO>> orders(OrderQuery q) {
        Page<TradeOrder> page = orderMapper.selectPage(q.toPage(), new LambdaQueryWrapper<TradeOrder>()
                .eq(q.getOrderNo() != null && !q.getOrderNo().isBlank(), TradeOrder::getOrderNo,
                        q.getOrderNo() == null ? null : q.getOrderNo().trim())
                .and(q.getUserId() != null, w -> w.eq(TradeOrder::getBuyerId, q.getUserId()).or()
                        .eq(TradeOrder::getSellerId, q.getUserId()))
                .eq(q.getStatus() != null, TradeOrder::getStatus, q.getStatus())
                .eq(q.getTradeMode() != null, TradeOrder::getTradeMode, q.getTradeMode())
                .ge(q.getStartTime() != null, TradeOrder::getCreatedAt, q.getStartTime())
                .le(q.getEndTime() != null, TradeOrder::getCreatedAt, q.getEndTime())
                .orderByDesc(TradeOrder::getId));
        return R.ok(PageResult.of(page, toVOs(page.getRecords())));
    }

    @GetMapping("/orders/{id}")
    @PreAuthorize("hasAuthority('order:view')")
    public R<AdminOrderDetail> order(@PathVariable long id) {
        return R.ok(orderDetail(id));
    }

    // ------------------------------------------------------------------ 纠纷

    @GetMapping("/disputes")
    @PreAuthorize("hasAuthority('dispute:handle')")
    public R<PageResult<DisputeItem>> disputes(@RequestParam(defaultValue = "3") Integer status, PageQuery pq) {
        Page<AfterSale> page = afterSaleMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<AfterSale>()
                .eq(AfterSale::getStatus, status).orderByAsc(AfterSale::getUpdatedAt));
        List<Long> orderIds = page.getRecords().stream().map(AfterSale::getOrderId).distinct().toList();
        Map<Long, AdminOrderVO> orders = orderIds.isEmpty() ? Map.of()
                : toVOs(orderMapper.selectByIds(orderIds)).stream()
                .collect(java.util.stream.Collectors.toMap(AdminOrderVO::id, v -> v));
        Map<Long, List<OrderLog>> appeals = orderIds.isEmpty() ? Map.of()
                : logMapper.selectList(new LambdaQueryWrapper<OrderLog>().in(OrderLog::getOrderId, orderIds)
                        .eq(OrderLog::getAction, OrderStateMachine.APPEAL)).stream()
                .collect(java.util.stream.Collectors.groupingBy(OrderLog::getOrderId));
        return R.ok(PageResult.of(page, a -> new DisputeItem(afterSaleService.toVO(a, Operator.ADMIN),
                orders.get(a.getOrderId()), appeals.getOrDefault(a.getOrderId(), List.of()).stream()
                .map(OrderLog::getCreatedAt).filter(t -> !t.isBefore(a.getCreatedAt()))
                .max(LocalDateTime::compareTo).orElse(null))));
    }

    @GetMapping("/disputes/{id}")
    @PreAuthorize("hasAuthority('dispute:handle')")
    public R<DisputeDetail> dispute(@PathVariable long id) {
        AfterSale a = Biz.notNull(afterSaleMapper.selectById(id), ErrorCode.NOT_FOUND);
        String appealRemark = a.getAppealRemark();
        if (appealRemark == null) {
            // v1.3 之前的数据：介入说明只存在 order_log.remark
            OrderLog appeal = logMapper.selectOne(new LambdaQueryWrapper<OrderLog>()
                    .eq(OrderLog::getOrderId, a.getOrderId()).eq(OrderLog::getAction, OrderStateMachine.APPEAL)
                    .orderByDesc(OrderLog::getId).last("LIMIT 1"));
            appealRemark = appeal == null ? null : appeal.getRemark();
        }
        MessagePage messages = disputeMessages(a, null, DISPUTE_MESSAGES);
        return R.ok(new DisputeDetail(afterSaleService.toVO(a, Operator.ADMIN), orderDetail(a.getOrderId()),
                appealRemark, messages.list(), messages.hasMore()));
    }

    @GetMapping("/disputes/{id}/messages")
    @PreAuthorize("hasAuthority('dispute:handle')")
    public R<MessagePage> disputeMessages(@PathVariable long id, @RequestParam(required = false) Long beforeId,
                                          @RequestParam(required = false) Integer size) {
        AfterSale a = Biz.notNull(afterSaleMapper.selectById(id), ErrorCode.NOT_FOUND);
        return R.ok(disputeMessages(a, beforeId, size));
    }

    @PostMapping("/disputes/{id}/arbitrate")
    @PreAuthorize("hasAuthority('dispute:handle')")
    @OpLog(module = "纠纷裁决", action = "裁决")
    public R<Void> arbitrate(@PathVariable long id, @Valid @RequestBody ArbitrateReq req) {
        afterSaleService.arbitrate(CurrentUser.id(), id, "REFUND".equals(req.result()), req.reason());
        return R.ok();
    }

    // ------------------------------------------------------------------ 内部

    private AdminOrderDetail orderDetail(long orderId) {
        TradeOrder o = Biz.notNull(orderMapper.selectById(orderId), ErrorCode.NOT_FOUND);
        return new AdminOrderDetail(orderService.detailOf(o, Operator.ADMIN, null), afterSaleService.listOf(orderId));
    }

    /** 买卖双方会话中订单创建以来的消息 */
    private MessagePage disputeMessages(AfterSale a, Long beforeId, Integer size) {
        TradeOrder o = orderMapper.selectById(a.getOrderId());
        Conversation c = chatService.findPair(o.getBuyerId(), o.getSellerId());
        if (c == null) {
            return new MessagePage(List.of(), false);
        }
        return chatService.history(c.getId(), beforeId, size, o.getCreatedAt());
    }

    private List<AdminOrderVO> toVOs(List<TradeOrder> orders) {
        Map<Long, User> users = userService.mapByIds(orders.stream()
                .flatMap(o -> Stream.of(o.getBuyerId(), o.getSellerId())).distinct().toList());
        return orders.stream().map(o -> new AdminOrderVO(o.getId(), o.getOrderNo(), o.getGoodsTitle(),
                o.getGoodsCover(), o.getTotalAmount(), o.getQuantity(), o.getTradeMode(), o.getStatus(),
                UserService.brief(users.get(o.getBuyerId())), UserService.brief(users.get(o.getSellerId())),
                o.getCreatedAt())).toList();
    }
}
