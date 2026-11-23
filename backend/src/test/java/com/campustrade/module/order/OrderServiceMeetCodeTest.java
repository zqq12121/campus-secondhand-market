package com.campustrade.module.order;

import com.campustrade.common.BizException;
import com.campustrade.common.Consts.Operator;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.entity.TradeOrder;
import com.campustrade.mapper.TradeOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 面交核销码：只有卖家在待面交时可核销，错 5 次锁定 */
@ExtendWith(MockitoExtension.class)
class OrderServiceMeetCodeTest {

    private static final long BUYER = 1024L;
    private static final long SELLER = 2048L;
    private static final String FAIL_KEY = "order:meet:fail:9001";

    @Mock
    private TradeOrderMapper orderMapper;
    @Mock
    private OrderFlow flow;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @InjectMocks
    private OrderService service;

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
        TradeOrder o = new TradeOrder();
        o.setId(9001L);
        o.setBuyerId(BUYER);
        o.setSellerId(SELLER);
        o.setStatus(OrderStatus.WAIT_MEET);
        o.setMeetCode("042517");
        when(orderMapper.selectById(9001L)).thenReturn(o);
    }

    private void expect(ErrorCode code, long userId, String input) {
        assertThatThrownBy(() -> service.verifyMeetCode(userId, 9001L, input)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void correctCodeFinishesOrder() {
        service.verifyMeetCode(SELLER, 9001L, " 042517 ");
        verify(flow).finish(any(TradeOrder.class), eq(OrderStateMachine.VERIFY_MEET_CODE), eq(Operator.SELLER), eq(SELLER));
    }

    @Test
    void wrongCodeCountsFailure() {
        expect(ErrorCode.MEET_CODE_WRONG, SELLER, "000000");
        verify(ops).increment(FAIL_KEY);
        verify(flow, never()).finish(any(), anyString(), anyInt(), any());
    }

    @Test
    void lockedAfterFiveFailures() {
        when(ops.get(FAIL_KEY)).thenReturn("5");
        // 锁定后即使输对也不能核销，只能由买家确认完成
        expect(ErrorCode.MEET_CODE_LOCKED, SELLER, "042517");
        verify(flow, never()).finish(any(), anyString(), anyInt(), any());
    }

    @Test
    void buyerCannotVerify() {
        expect(ErrorCode.ORDER_STATE_INVALID, BUYER, "042517");
        verify(ops, never()).increment(anyString());
    }

    @Test
    void outsiderForbidden() {
        expect(ErrorCode.FORBIDDEN, 4096L, "042517");
        verify(flow, never()).finish(any(), anyString(), anyInt(), any());
    }
}
