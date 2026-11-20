package com.campustrade.module.aftersale;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.AfterSale;
import com.campustrade.entity.TradeOrder;
import com.campustrade.infra.FileStorageService;
import com.campustrade.infra.mq.DelayPublisher;
import com.campustrade.infra.mq.DelayType;
import com.campustrade.mapper.AfterSaleMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.order.OrderFlow;
import com.campustrade.module.order.OrderStateMachine;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.module.user.CreditService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 售后与纠纷（接口文档第 9 章、13.7）。
 * 退款调用支付渠道放在事务之外：先提交"同意/裁决退款"状态，再调用渠道；渠道失败时保持状态，由定时任务重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AfterSaleService {

    public static final int TYPE_CANCEL = 1;
    public static final int TYPE_REFUND = 2;
    public static final int PENDING = 0;
    public static final int AGREED = 1;
    public static final int REJECTED = 2;
    public static final int ARBITRATING = 3;
    public static final int ARBITRATED_REFUND = 4;
    public static final int ARBITRATED_RELEASE = 5;
    public static final int WITHDRAWN = 6;
    private static final int EVIDENCE_MAX = 6;

    private final AfterSaleMapper afterSaleMapper;
    private final TradeOrderMapper orderMapper;
    private final OrderFlow orderFlow;
    private final PaymentService paymentService;
    private final CreditService creditService;
    private final FileStorageService fileStorageService;
    private final DelayPublisher delayPublisher;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public record ApplyReq(@NotNull @Min(1) @Max(2) Integer type, @NotBlank @Size(max = 200) String reason,
                           @Size(max = EVIDENCE_MAX) List<String> evidenceUrls) {
    }

    public record AfterSaleVO(Long id, Long orderId, Integer type, String reason, List<String> evidenceUrls,
                              BigDecimal refundAmount, Integer status, String sellerReply,
                              LocalDateTime sellerDeadline, LocalDateTime appealDeadline, String arbitrationReason, LocalDateTime refundedAt,
                              LocalDateTime closedAt, List<String> actions, LocalDateTime createdAt) {
    }

    // ------------------------------------------------------------------ 买家

    public AfterSaleVO apply(long buyerId, long orderId, ApplyReq req) {
        AfterSale saved = tx.execute(s -> {
            TradeOrder o = orderMapper.selectById(orderId);
            Biz.check(o != null && o.getBuyerId() == buyerId, ErrorCode.NOT_FOUND);
            Biz.check(o.getTradeMode() == TradeMode.GUARANTEE, ErrorCode.ORDER_STATE_INVALID, "面交订单请通过举报处理纠纷");
            Biz.check(!hasOpen(orderId), ErrorCode.AFTER_SALE_EXISTS);
            boolean cancel = req.type() == TYPE_CANCEL;
            Biz.check(o.getStatus() == (cancel ? OrderStatus.WAIT_DELIVER : OrderStatus.WAIT_RECEIVE),
                    ErrorCode.ORDER_STATE_INVALID);
            List<String> evidence = req.evidenceUrls() == null ? List.of() : req.evidenceUrls();
            Biz.check(cancel || !evidence.isEmpty(), ErrorCode.BAD_REQUEST, "申请退款须上传凭证图片");
            evidence.forEach(u -> Biz.check(fileStorageService.isOwnUrl(u), ErrorCode.BAD_REQUEST, "凭证须通过本平台上传"));

            AfterSale a = new AfterSale();
            a.setOrderId(orderId);
            a.setType(req.type());
            a.setBuyerId(o.getBuyerId());
            a.setSellerId(o.getSellerId());
            a.setReason(req.reason().trim());
            a.setEvidenceUrls(evidence);
            a.setRefundAmount(o.getTotalAmount());
            a.setStatus(PENDING);
            a.setSellerDeadline(LocalDateTime.now().plus(cancel ? props.rules().afterSaleCancelTimeout()
                    : props.rules().afterSaleRefundTimeout()));
            a.setCreatedAt(LocalDateTime.now());
            afterSaleMapper.insert(a);
            orderFlow.transit(o, cancel ? OrderStateMachine.APPLY_CANCEL : OrderStateMachine.APPLY_REFUND,
                    Operator.BUYER, buyerId, null, a.getReason(), null);
            delayPublisher.schedule(cancel ? DelayType.AUTO_AGREE_CANCEL : DelayType.AUTO_AGREE_REFUND, orderId, a.getId());
            return a;
        });
        return view(saved.getId(), Operator.BUYER);
    }

    public void appeal(long buyerId, long id, String remark) {
        tx.executeWithoutResult(s -> {
            AfterSale a = requireParty(id, buyerId, Operator.BUYER);
            Biz.check(a.getType() == TYPE_REFUND && a.getStatus() == REJECTED && a.getClosedAt() == null,
                    ErrorCode.AFTER_SALE_STATE_INVALID);
            moveStatus(a, REJECTED, ARBITRATING, null);
            orderFlow.transit(orderMapper.selectById(a.getOrderId()), OrderStateMachine.APPEAL, Operator.BUYER,
                    buyerId, null, remark == null || remark.isBlank() ? null : remark.trim(), null);
        });
    }

    public void withdraw(long buyerId, long id) {
        tx.executeWithoutResult(s -> {
            AfterSale a = requireParty(id, buyerId, Operator.BUYER);
            Biz.check((a.getStatus() == PENDING || a.getStatus() == REJECTED) && a.getClosedAt() == null,
                    ErrorCode.AFTER_SALE_STATE_INVALID);
            moveStatus(a, a.getStatus(), WITHDRAWN, w -> w.set(AfterSale::getClosedAt, LocalDateTime.now()));
            int back = a.getType() == TYPE_CANCEL ? OrderStatus.WAIT_DELIVER : OrderStatus.WAIT_RECEIVE;
            orderFlow.transit(orderMapper.selectById(a.getOrderId()), OrderStateMachine.WITHDRAW_AFTER_SALE,
                    Operator.BUYER, buyerId, back, null, null);
        });
    }

    // ------------------------------------------------------------------ 卖家

    public void agree(long sellerId, long id) {
        tx.executeWithoutResult(s -> {
            AfterSale a = requireParty(id, sellerId, Operator.SELLER);
            Biz.check(a.getStatus() == PENDING, ErrorCode.AFTER_SALE_STATE_INVALID);
            moveStatus(a, PENDING, AGREED, null);
        });
        if (!refundAndClose(id, OrderStateMachine.AGREE_AFTER_SALE, Operator.SELLER, sellerId)) {
            throw new BizException(ErrorCode.REFUND_FAILED);
        }
    }

    public void reject(long sellerId, long id, String reply) {
        tx.executeWithoutResult(s -> {
            AfterSale a = requireParty(id, sellerId, Operator.SELLER);
            Biz.check(a.getStatus() == PENDING, ErrorCode.AFTER_SALE_STATE_INVALID);
            boolean cancel = a.getType() == TYPE_CANCEL;
            moveStatus(a, PENDING, REJECTED, w -> {
                w.set(AfterSale::getSellerReply, reply.trim());
                if (cancel) {
                    w.set(AfterSale::getClosedAt, LocalDateTime.now());
                }
            });
            // 交货前取消被拒：订单回到待交货；收货前退款被拒：保持售后中，买家可申请介入或撤销
            orderFlow.transit(orderMapper.selectById(a.getOrderId()), OrderStateMachine.REJECT_AFTER_SALE,
                    Operator.SELLER, sellerId, cancel ? OrderStatus.WAIT_DELIVER : OrderStatus.AFTER_SALE,
                    reply.trim(), null);
            if (!cancel) {
                delayPublisher.schedule(DelayType.AUTO_CONFIRM_AFTER_REJECT, a.getOrderId(), a.getId());
            }
        });
    }

    // ------------------------------------------------------------------ 系统与管理员

    /** 卖家超时未处理：自动同意（截止时间未到或状态已变则忽略） */
    public void autoAgree(long id) {
        Boolean go = tx.execute(s -> {
            AfterSale a = afterSaleMapper.selectById(id);
            if (a == null || a.getStatus() != PENDING || LocalDateTime.now().plusSeconds(2).isBefore(a.getSellerDeadline())) {
                return false;
            }
            return afterSaleMapper.update(new LambdaUpdateWrapper<AfterSale>().eq(AfterSale::getId, id)
                    .eq(AfterSale::getStatus, PENDING).set(AfterSale::getStatus, AGREED)) == 1;
        });
        if (Boolean.TRUE.equals(go)) {
            refundAndClose(id, OrderStateMachine.AGREE_AFTER_SALE, Operator.SYSTEM, null);
        }
    }

    /**
     * 收货前退款被拒后买家超时未申请介入也未撤销：售后关闭（状态保持「卖家拒绝」），订单自动确认收货并放款。
     * 截止时间未到或买家已处理则忽略。
     */
    public void autoConfirmRejected(long id) {
        tx.executeWithoutResult(s -> {
            AfterSale a = afterSaleMapper.selectById(id);
            LocalDateTime deadline = a == null ? null : appealDeadline(a);
            if (deadline == null || LocalDateTime.now().plusSeconds(2).isBefore(deadline)) {
                return;
            }
            int n = afterSaleMapper.update(new LambdaUpdateWrapper<AfterSale>().eq(AfterSale::getId, id)
                    .eq(AfterSale::getStatus, REJECTED).isNull(AfterSale::getClosedAt)
                    .set(AfterSale::getClosedAt, LocalDateTime.now()));
            if (n == 1) {
                orderFlow.finish(orderMapper.selectById(a.getOrderId()), OrderStateMachine.AUTO_CONFIRM_AFTER_REJECT,
                        Operator.SYSTEM, null);
            }
        });
    }

    /** 已同意 / 已裁决退款但渠道退款未成功的，重试（定时任务调用） */
    public void retryRefund(AfterSale a) {
        String action = a.getStatus() == ARBITRATED_REFUND ? OrderStateMachine.ARBITRATE_REFUND
                : OrderStateMachine.AGREE_AFTER_SALE;
        int operator = a.getStatus() == ARBITRATED_REFUND ? Operator.ADMIN : Operator.SYSTEM;
        refundAndClose(a.getId(), action, operator, a.getArbitratorId());
    }

    public void arbitrate(long adminId, long id, boolean refund, String reason) {
        tx.executeWithoutResult(s -> {
            AfterSale a = Biz.notNull(afterSaleMapper.selectById(id), ErrorCode.NOT_FOUND);
            Biz.check(a.getStatus() == ARBITRATING, ErrorCode.AFTER_SALE_STATE_INVALID);
            moveStatus(a, ARBITRATING, refund ? ARBITRATED_REFUND : ARBITRATED_RELEASE, w -> {
                w.set(AfterSale::getArbitratorId, adminId).set(AfterSale::getArbitrationReason, reason.trim());
                if (!refund) {
                    w.set(AfterSale::getClosedAt, LocalDateTime.now());
                }
            });
            TradeOrder o = orderMapper.selectById(a.getOrderId());
            if (!refund) {
                orderFlow.finish(o, OrderStateMachine.ARBITRATE_RELEASE, Operator.ADMIN, adminId);
            }
            long loser = refund ? a.getSellerId() : a.getBuyerId();
            creditService.change(loser, -5, CreditReason.DISPUTE_LOST, o.getId(), "纠纷裁决败诉", adminId);
        });
        if (refund && !refundAndClose(id, OrderStateMachine.ARBITRATE_REFUND, Operator.ADMIN, adminId)) {
            throw new BizException(ErrorCode.REFUND_FAILED);
        }
    }

    // ------------------------------------------------------------------ 查询

    public AfterSaleVO detail(long userId, long id) {
        AfterSale a = Biz.notNull(afterSaleMapper.selectById(id), ErrorCode.NOT_FOUND);
        Biz.check(a.getBuyerId() == userId || a.getSellerId() == userId, ErrorCode.FORBIDDEN);
        return toVO(a, a.getBuyerId() == userId ? Operator.BUYER : Operator.SELLER);
    }

    /** 订单详情里展示最近一次售后 */
    public AfterSaleVO latestOf(long orderId, int role) {
        AfterSale a = afterSaleMapper.selectOne(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getOrderId, orderId)
                .orderByDesc(AfterSale::getId).last("LIMIT 1"));
        return a == null ? null : toVO(a, role);
    }

    public List<AfterSaleVO> listOf(long orderId) {
        return afterSaleMapper.selectList(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getOrderId, orderId)
                .orderByDesc(AfterSale::getId)).stream().map(a -> toVO(a, Operator.ADMIN)).toList();
    }

    public AfterSaleVO view(long id, int role) {
        return toVO(afterSaleMapper.selectById(id), role);
    }

    public AfterSaleVO toVO(AfterSale a, int role) {
        List<String> actions = new ArrayList<>();
        boolean open = a.getClosedAt() == null;
        if (role == Operator.SELLER && a.getStatus() == PENDING) {
            actions.addAll(List.of("AGREE", "REJECT"));
        }
        if (role == Operator.BUYER) {
            if (open && a.getType() == TYPE_REFUND && a.getStatus() == REJECTED) {
                actions.add("APPEAL");
            }
            if (open && (a.getStatus() == PENDING || a.getStatus() == REJECTED)) {
                actions.add("WITHDRAW");
            }
        }
        return new AfterSaleVO(a.getId(), a.getOrderId(), a.getType(), a.getReason(), a.getEvidenceUrls(),
                a.getRefundAmount(), a.getStatus(), a.getSellerReply(), a.getSellerDeadline(), appealDeadline(a),
                a.getArbitrationReason(), a.getRefundedAt(), a.getClosedAt(), actions, a.getCreatedAt());
    }

    // ------------------------------------------------------------------ 内部

    /** 调用渠道退款，成功后在事务内关闭售后、订单 → 已退款；失败返回 false（状态保持，等待重试） */
    private boolean refundAndClose(long id, String action, int operator, Long operatorId) {
        AfterSale a = afterSaleMapper.selectById(id);
        String outRefundNo = a.getOutRefundNo() != null ? a.getOutRefundNo() : "R" + a.getId();
        if (a.getOutRefundNo() == null) {
            afterSaleMapper.update(new LambdaUpdateWrapper<AfterSale>().eq(AfterSale::getId, id)
                    .set(AfterSale::getOutRefundNo, outRefundNo));
        }
        if (!paymentService.refund(a.getOrderId(), outRefundNo)) {
            log.warn("售后退款失败，等待重试 afterSaleId={}", id);
            return false;
        }
        tx.executeWithoutResult(s -> {
            LocalDateTime now = LocalDateTime.now();
            int n = afterSaleMapper.update(new LambdaUpdateWrapper<AfterSale>().eq(AfterSale::getId, id)
                    .isNull(AfterSale::getRefundedAt).set(AfterSale::getRefundedAt, now).set(AfterSale::getClosedAt, now));
            if (n == 1) {
                orderFlow.refunded(orderMapper.selectById(a.getOrderId()), action, operator, operatorId);
            }
        });
        return true;
    }

    /**
     * 收货前退款被拒后，买家申请介入 / 撤销的截止时间；不在该状态返回 null。
     * 拒绝后售后记录不再更新，updated_at 即拒绝时间。
     */
    public LocalDateTime appealDeadline(AfterSale a) {
        if (a.getType() != TYPE_REFUND || a.getStatus() != REJECTED || a.getClosedAt() != null) {
            return null;
        }
        return a.getUpdatedAt().plus(props.rules().afterSaleAppealTimeout());
    }

    /** 所有终态都会写 closed_at，未关闭即处理中 */
    private boolean hasOpen(long orderId) {
        return afterSaleMapper.exists(new LambdaQueryWrapper<AfterSale>().eq(AfterSale::getOrderId, orderId)
                .isNull(AfterSale::getClosedAt));
    }

    private AfterSale requireParty(long id, long userId, int role) {
        AfterSale a = Biz.notNull(afterSaleMapper.selectById(id), ErrorCode.NOT_FOUND);
        Biz.check(role == Operator.BUYER ? a.getBuyerId() == userId : a.getSellerId() == userId, ErrorCode.FORBIDDEN);
        return a;
    }

    private void moveStatus(AfterSale a, int from, int to,
                            java.util.function.Consumer<LambdaUpdateWrapper<AfterSale>> extra) {
        LambdaUpdateWrapper<AfterSale> w = new LambdaUpdateWrapper<AfterSale>().eq(AfterSale::getId, a.getId())
                .eq(AfterSale::getStatus, from).set(AfterSale::getStatus, to);
        if (extra != null) {
            extra.accept(w);
        }
        Biz.check(afterSaleMapper.update(w) == 1, ErrorCode.CONFLICT);
    }
}
