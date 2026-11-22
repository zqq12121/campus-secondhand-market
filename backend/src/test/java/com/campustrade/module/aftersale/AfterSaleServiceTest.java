package com.campustrade.module.aftersale;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.AfterSale;
import com.campustrade.entity.TradeOrder;
import com.campustrade.mapper.AfterSaleMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.aftersale.AfterSaleService.ApplyReq;
import com.campustrade.module.order.OrderFlow;
import com.campustrade.module.order.OrderStateMachine;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 售后：申请校验、申请介入保存补充说明、同意退款与渠道失败、介入截止时间 */
@ExtendWith(MockitoExtension.class)
class AfterSaleServiceTest {

    private static final long BUYER = 1024L;
    private static final long SELLER = 2048L;

    @Mock
    private AfterSaleMapper afterSaleMapper;
    @Mock
    private TradeOrderMapper orderMapper;
    @Mock
    private OrderFlow orderFlow;
    @Mock
    private PaymentService paymentService;
    @Mock
    private TransactionTemplate tx;
    @Mock
    private AppProperties props;
    @InjectMocks
    private AfterSaleService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(AfterSale.class, TradeOrder.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void runTxInline() {
        lenient().doAnswer(inv -> {
            inv.<Consumer<Object>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<Object>>getArgument(0).doInTransaction(null));
    }

    private static AfterSale afterSale(int type, int status) {
        AfterSale a = new AfterSale();
        a.setId(51L);
        a.setOrderId(9001L);
        a.setType(type);
        a.setStatus(status);
        a.setBuyerId(BUYER);
        a.setSellerId(SELLER);
        a.setUpdatedAt(LocalDateTime.of(2026, 11, 20, 10, 0));
        return a;
    }

    private static TradeOrder order(int mode, int status) {
        TradeOrder o = new TradeOrder();
        o.setId(9001L);
        o.setBuyerId(BUYER);
        o.setSellerId(SELLER);
        o.setTradeMode(mode);
        o.setStatus(status);
        return o;
    }

    private static void expect(ErrorCode code, Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void faceOrderCannotApply() {
        when(orderMapper.selectById(9001L)).thenReturn(order(TradeMode.FACE, OrderStatus.WAIT_MEET));
        expect(ErrorCode.ORDER_STATE_INVALID, () -> service.apply(BUYER, 9001L,
                new ApplyReq(AfterSaleService.TYPE_CANCEL, "不想要了", null)));
    }

    @Test
    void refundRequiresEvidence() {
        when(orderMapper.selectById(9001L)).thenReturn(order(TradeMode.GUARANTEE, OrderStatus.WAIT_RECEIVE));
        expect(ErrorCode.BAD_REQUEST, () -> service.apply(BUYER, 9001L,
                new ApplyReq(AfterSaleService.TYPE_REFUND, "屏幕有坏点", List.of())));
        verify(afterSaleMapper, never()).insert(any(AfterSale.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appealStoresRemarkOnAfterSale() {
        when(afterSaleMapper.selectById(51L)).thenReturn(afterSale(AfterSaleService.TYPE_REFUND, AfterSaleService.REJECTED));
        when(afterSaleMapper.update(any(Wrapper.class))).thenReturn(1);
        TradeOrder o = order(TradeMode.GUARANTEE, OrderStatus.AFTER_SALE);
        when(orderMapper.selectById(9001L)).thenReturn(o);

        service.appeal(BUYER, 51L, "  卖家说的不是事实  ");

        ArgumentCaptor<Wrapper<AfterSale>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(afterSaleMapper).update(w.capture());
        LambdaUpdateWrapper<AfterSale> upd = (LambdaUpdateWrapper<AfterSale>) w.getValue();
        assertThat(upd.getSqlSet()).contains("appeal_remark");
        assertThat(upd.getParamNameValuePairs()).containsValue("卖家说的不是事实")
                .containsValue(AfterSaleService.ARBITRATING);
        verify(orderFlow).transit(eq(o), eq(OrderStateMachine.APPEAL), anyInt(), eq(BUYER), isNull(),
                eq("卖家说的不是事实"), isNull());
    }

    @Test
    void appealOnlyAfterRefundRejected() {
        when(afterSaleMapper.selectById(51L)).thenReturn(afterSale(AfterSaleService.TYPE_CANCEL, AfterSaleService.REJECTED));
        expect(ErrorCode.AFTER_SALE_STATE_INVALID, () -> service.appeal(BUYER, 51L, null));
    }

    @Test
    void onlyBuyerCanAppeal() {
        when(afterSaleMapper.selectById(51L)).thenReturn(afterSale(AfterSaleService.TYPE_REFUND, AfterSaleService.REJECTED));
        expect(ErrorCode.FORBIDDEN, () -> service.appeal(SELLER, 51L, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void agreeRefundsAndClosesOrder() {
        when(afterSaleMapper.selectById(51L)).thenReturn(afterSale(AfterSaleService.TYPE_CANCEL, AfterSaleService.PENDING));
        when(afterSaleMapper.update(any(Wrapper.class))).thenReturn(1);
        when(paymentService.refund(9001L, "R51")).thenReturn(true);
        TradeOrder o = order(TradeMode.GUARANTEE, OrderStatus.AFTER_SALE);
        when(orderMapper.selectById(9001L)).thenReturn(o);

        service.agree(SELLER, 51L);

        verify(orderFlow).refunded(eq(o), eq(OrderStateMachine.AGREE_AFTER_SALE), anyInt(), eq(SELLER));
    }

    @Test
    @SuppressWarnings("unchecked")
    void refundFailureKeepsStateForRetry() {
        when(afterSaleMapper.selectById(51L)).thenReturn(afterSale(AfterSaleService.TYPE_CANCEL, AfterSaleService.PENDING));
        when(afterSaleMapper.update(any(Wrapper.class))).thenReturn(1);
        when(paymentService.refund(anyLong(), anyString())).thenReturn(false);

        expect(ErrorCode.REFUND_FAILED, () -> service.agree(SELLER, 51L));

        verify(orderFlow, never()).refunded(any(), anyString(), anyInt(), any());
    }

    @Test
    void appealDeadlineCountsFromRejectTime() {
        AppProperties.Rules rules = mock(AppProperties.Rules.class);
        when(props.rules()).thenReturn(rules);
        when(rules.afterSaleAppealTimeout()).thenReturn(Duration.ofHours(48));
        AfterSale a = afterSale(AfterSaleService.TYPE_REFUND, AfterSaleService.REJECTED);
        assertThat(service.appealDeadline(a)).isEqualTo(LocalDateTime.of(2026, 11, 22, 10, 0));

        a.setClosedAt(LocalDateTime.now());
        assertThat(service.appealDeadline(a)).isNull();
    }
}
