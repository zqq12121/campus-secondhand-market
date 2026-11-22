package com.campustrade.module.order;

import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 订单状态机：(当前状态, 动作, 操作人) 合法才可流转（接口文档附录 C） */
public final class OrderStateMachine {

    private OrderStateMachine() {
    }

    public static final String CREATE = "CREATE";
    public static final String SELLER_CONFIRM = "SELLER_CONFIRM";
    public static final String COMPLETE = "COMPLETE";
    /** 面交：卖家输入买家出示的核销码完成交易 */
    public static final String VERIFY_MEET_CODE = "VERIFY_MEET_CODE";
    public static final String CANCEL = "CANCEL";
    public static final String PAY = "PAY";
    public static final String DELIVER = "DELIVER";
    public static final String CONFIRM_RECEIPT = "CONFIRM_RECEIPT";
    public static final String APPLY_CANCEL = "APPLY_CANCEL";
    public static final String APPLY_REFUND = "APPLY_REFUND";
    public static final String AGREE_AFTER_SALE = "AGREE_AFTER_SALE";
    public static final String REJECT_AFTER_SALE = "REJECT_AFTER_SALE";
    public static final String APPEAL = "APPEAL";
    public static final String WITHDRAW_AFTER_SALE = "WITHDRAW_AFTER_SALE";
    public static final String ARBITRATE_REFUND = "ARBITRATE_REFUND";
    public static final String ARBITRATE_RELEASE = "ARBITRATE_RELEASE";
    public static final String AUTO_CANCEL_UNCONFIRMED = "AUTO_CANCEL_UNCONFIRMED";
    public static final String AUTO_CANCEL_UNPAID = "AUTO_CANCEL_UNPAID";
    public static final String AUTO_COMPLETE = "AUTO_COMPLETE";
    public static final String AUTO_CONFIRM = "AUTO_CONFIRM";
    public static final String AUTO_CONFIRM_AFTER_REJECT = "AUTO_CONFIRM_AFTER_REJECT";
    public static final String REVIEW = "REVIEW";

    /** to 为可选目标状态集合（多数动作只有一个目标；驳回、撤销售后由调用方指定） */
    private record Rule(Set<Integer> from, Set<Integer> operators, Set<Integer> to) {
    }

    private static final Map<String, Rule> RULES = new HashMap<>();

    private static void rule(String action, Set<Integer> from, Set<Integer> operators, Integer... to) {
        RULES.put(action, new Rule(from, operators, Set.of(to)));
    }

    static {
        Set<Integer> b = Set.of(Operator.BUYER);
        Set<Integer> s = Set.of(Operator.SELLER);
        Set<Integer> sys = Set.of(Operator.SYSTEM);
        rule(SELLER_CONFIRM, Set.of(OrderStatus.WAIT_SELLER_CONFIRM), s, OrderStatus.WAIT_MEET);
        rule(COMPLETE, Set.of(OrderStatus.WAIT_MEET), b, OrderStatus.FINISHED);
        rule(VERIFY_MEET_CODE, Set.of(OrderStatus.WAIT_MEET), s, OrderStatus.FINISHED);
        rule(CANCEL, Set.of(OrderStatus.WAIT_SELLER_CONFIRM, OrderStatus.WAIT_MEET, OrderStatus.WAIT_PAY),
                Set.of(Operator.BUYER, Operator.SELLER), OrderStatus.CANCELED);
        rule(AUTO_CANCEL_UNCONFIRMED, Set.of(OrderStatus.WAIT_SELLER_CONFIRM), sys, OrderStatus.CANCELED);
        rule(AUTO_CANCEL_UNPAID, Set.of(OrderStatus.WAIT_PAY), sys, OrderStatus.CANCELED);
        rule(AUTO_COMPLETE, Set.of(OrderStatus.WAIT_MEET), sys, OrderStatus.FINISHED);
        rule(PAY, Set.of(OrderStatus.WAIT_PAY), sys, OrderStatus.WAIT_DELIVER);
        rule(DELIVER, Set.of(OrderStatus.WAIT_DELIVER), s, OrderStatus.WAIT_RECEIVE);
        rule(CONFIRM_RECEIPT, Set.of(OrderStatus.WAIT_RECEIVE), b, OrderStatus.FINISHED);
        rule(AUTO_CONFIRM, Set.of(OrderStatus.WAIT_RECEIVE), sys, OrderStatus.FINISHED);
        rule(AUTO_CONFIRM_AFTER_REJECT, Set.of(OrderStatus.AFTER_SALE), sys, OrderStatus.FINISHED);
        rule(APPLY_CANCEL, Set.of(OrderStatus.WAIT_DELIVER), b, OrderStatus.AFTER_SALE);
        rule(APPLY_REFUND, Set.of(OrderStatus.WAIT_RECEIVE), b, OrderStatus.AFTER_SALE);
        rule(AGREE_AFTER_SALE, Set.of(OrderStatus.AFTER_SALE), Set.of(Operator.SELLER, Operator.SYSTEM),
                OrderStatus.REFUNDED);
        rule(REJECT_AFTER_SALE, Set.of(OrderStatus.AFTER_SALE), s, OrderStatus.WAIT_DELIVER, OrderStatus.AFTER_SALE);
        rule(APPEAL, Set.of(OrderStatus.AFTER_SALE), b, OrderStatus.AFTER_SALE);
        rule(WITHDRAW_AFTER_SALE, Set.of(OrderStatus.AFTER_SALE), b, OrderStatus.WAIT_DELIVER, OrderStatus.WAIT_RECEIVE);
        rule(ARBITRATE_REFUND, Set.of(OrderStatus.AFTER_SALE), Set.of(Operator.ADMIN), OrderStatus.REFUNDED);
        rule(ARBITRATE_RELEASE, Set.of(OrderStatus.AFTER_SALE), Set.of(Operator.ADMIN), OrderStatus.FINISHED);
    }

    /**
     * 合法返回目标状态，否则返回 null。
     * target 为 null 时取唯一目标；动作有多个目标时必须由调用方指定且在集合内。
     */
    public static Integer next(int status, String action, int operator, Integer target) {
        Rule r = RULES.get(action);
        if (r == null || !r.from().contains(status) || !r.operators().contains(operator)) {
            return null;
        }
        if (target == null) {
            return r.to().size() == 1 ? r.to().iterator().next() : null;
        }
        return r.to().contains(target) ? target : null;
    }

    /** 买卖双方在订单上可点的按钮（售后相关按钮在售后详情的 actions 中） */
    public static List<String> userActions(int status, int operator) {
        return List.of(SELLER_CONFIRM, COMPLETE, VERIFY_MEET_CODE, PAY, CANCEL, DELIVER, CONFIRM_RECEIPT, APPLY_CANCEL, APPLY_REFUND)
                .stream().filter(a -> PAY.equals(a)
                        ? status == OrderStatus.WAIT_PAY && operator == Operator.BUYER
                        : next(status, a, operator, null) != null)
                .toList();
    }
}
