package com.campustrade.module.order;

import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import org.junit.jupiter.api.Test;

import static com.campustrade.module.order.OrderStateMachine.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStateMachineTest {

    @Test
    void meetFlow() {
        assertThat(next(OrderStatus.WAIT_SELLER_CONFIRM, SELLER_CONFIRM, Operator.SELLER, null)).isEqualTo(OrderStatus.WAIT_MEET);
        assertThat(next(OrderStatus.WAIT_MEET, COMPLETE, Operator.BUYER, null)).isEqualTo(OrderStatus.FINISHED);
    }

    @Test
    void escrowFlow() {
        assertThat(next(OrderStatus.WAIT_PAY, PAY, Operator.SYSTEM, null)).isEqualTo(OrderStatus.WAIT_DELIVER);
        assertThat(next(OrderStatus.WAIT_DELIVER, DELIVER, Operator.SELLER, null)).isEqualTo(OrderStatus.WAIT_RECEIVE);
        assertThat(next(OrderStatus.WAIT_RECEIVE, CONFIRM_RECEIPT, Operator.BUYER, null)).isEqualTo(OrderStatus.FINISHED);
    }

    @Test
    void wrongOperatorOrStateRejected() {
        assertThat(next(OrderStatus.WAIT_SELLER_CONFIRM, SELLER_CONFIRM, Operator.BUYER, null)).isNull();
        assertThat(next(OrderStatus.WAIT_PAY, PAY, Operator.BUYER, null)).isNull();
        assertThat(next(OrderStatus.FINISHED, CANCEL, Operator.BUYER, null)).isNull();
        assertThat(next(OrderStatus.WAIT_MEET, "NO_SUCH_ACTION", Operator.BUYER, null)).isNull();
    }

    @Test
    void multiTargetActionNeedsExplicitTarget() {
        assertThat(next(OrderStatus.AFTER_SALE, REJECT_AFTER_SALE, Operator.SELLER, null)).isNull();
        assertThat(next(OrderStatus.AFTER_SALE, REJECT_AFTER_SALE, Operator.SELLER, OrderStatus.WAIT_DELIVER))
                .isEqualTo(OrderStatus.WAIT_DELIVER);
        assertThat(next(OrderStatus.AFTER_SALE, WITHDRAW_AFTER_SALE, Operator.BUYER, OrderStatus.FINISHED)).isNull();
    }

    @Test
    void arbitrationOnlyByAdmin() {
        assertThat(next(OrderStatus.AFTER_SALE, ARBITRATE_REFUND, Operator.ADMIN, null)).isEqualTo(OrderStatus.REFUNDED);
        assertThat(next(OrderStatus.AFTER_SALE, ARBITRATE_REFUND, Operator.SELLER, null)).isNull();
    }

    @Test
    void sellerVerifiesMeetCode() {
        assertThat(next(OrderStatus.WAIT_MEET, VERIFY_MEET_CODE, Operator.SELLER, null)).isEqualTo(OrderStatus.FINISHED);
        assertThat(next(OrderStatus.WAIT_MEET, VERIFY_MEET_CODE, Operator.BUYER, null)).isNull();
        assertThat(next(OrderStatus.WAIT_SELLER_CONFIRM, VERIFY_MEET_CODE, Operator.SELLER, null)).isNull();
        assertThat(userActions(OrderStatus.WAIT_MEET, Operator.SELLER)).contains(VERIFY_MEET_CODE);
        assertThat(userActions(OrderStatus.WAIT_MEET, Operator.BUYER)).doesNotContain(VERIFY_MEET_CODE);
    }
}
