package com.campustrade.module.order;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.OrderStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.infra.StockCache;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.goods.mapper.GoodsMapper;
import com.campustrade.module.order.OrderDtos.CreateReq;
import com.campustrade.module.order.OrderDtos.CreateResp;
import com.campustrade.module.order.entity.TradeOrder;
import com.campustrade.module.order.mapper.TradeOrderMapper;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 下单：幂等键、Redis 预扣、条件更新扣库存 */
@ExtendWith(MockitoExtension.class)
class OrderServiceCreateTest {

    private static final long BUYER = 1024L;
    private static final long SELLER = 2048L;
    private static final long GOODS = 301L;

    @Mock
    private TradeOrderMapper orderMapper;
    @Mock
    private GoodsMapper goodsMapper;
    @Mock
    private OrderFlow flow;
    @Mock
    private UserService userService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private StockCache stockCache;
    @Mock
    private AppProperties props;
    @InjectMocks
    private OrderService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(Goods.class);
    }

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
        lenient().when(ops.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(true);
        lenient().when(userService.require(BUYER)).thenReturn(user(BUYER, 100));
        lenient().when(ops.increment(anyString())).thenReturn(1L);
    }

    private static User user(long id, int credit) {
        User u = new User();
        u.setId(id);
        u.setStatus(UserStatus.NORMAL);
        u.setCreditScore(credit);
        return u;
    }

    private static Goods goods(int stock) {
        Goods g = new Goods();
        g.setId(GOODS);
        g.setSellerId(SELLER);
        g.setTitle("显示器");
        g.setPrice(new BigDecimal("300.00"));
        g.setStatus(GoodsStatus.ON_SALE);
        g.setTradeMode(TradeMode.BOTH);
        g.setStock(stock);
        g.setVersion(3);
        return g;
    }

    private static CreateReq req(int quantity, int mode) {
        return new CreateReq(GOODS, quantity, mode, "req-1");
    }

    private static AppProperties.Pay pay(String mode) {
        return new AppProperties.Pay(mode, null, null, null, null, null, null);
    }

    private void expect(ErrorCode code, CreateReq req) {
        assertThatThrownBy(() -> service.create(BUYER, req)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void redisSoldOutRejectsWithoutTouchingDatabase() {
        when(stockCache.tryDeduct(eq(GOODS), eq(1), any())).thenReturn(false);
        expect(ErrorCode.STOCK_NOT_ENOUGH, req(1, TradeMode.FACE));
        verify(goodsMapper, never()).update(any(Wrapper.class));
        verify(orderMapper, never()).insert(any(TradeOrder.class));
        // 失败后释放幂等键，允许用户重试
        verify(redis).delete("order:req:" + BUYER + ":req-1");
    }

    @SuppressWarnings("unchecked")
    private LambdaUpdateWrapper<Goods> capturedStockUpdate() {
        ArgumentCaptor<Wrapper<Goods>> w = ArgumentCaptor.forClass(Wrapper.class);
        verify(goodsMapper).update(w.capture());
        return (LambdaUpdateWrapper<Goods>) w.getValue();
    }

    @Test
    void deductsStockWithConditionalUpdate() {
        when(stockCache.tryDeduct(eq(GOODS), eq(2), any())).thenReturn(true);
        when(goodsMapper.selectById(GOODS)).thenReturn(goods(2));
        when(goodsMapper.update(any(Wrapper.class))).thenReturn(1);

        CreateResp r = service.create(BUYER, req(2, TradeMode.FACE));

        LambdaUpdateWrapper<Goods> upd = capturedStockUpdate();
        // 扣到 0 时进入交易中；IF 写在扣减前，比较的是扣减前的库存
        assertThat(upd.getSqlSet()).isEqualTo("status = IF(stock = 2, " + GoodsStatus.TRADING
                + ", status), stock = stock - 2, version = version + 1");
        // 只在「在售且库存够」时扣：不靠版本号，不需要重试
        assertThat(upd.getSqlSegment()).contains("status =").contains("stock >=").doesNotContain("version");
        ArgumentCaptor<TradeOrder> order = ArgumentCaptor.forClass(TradeOrder.class);
        verify(orderMapper).insert(order.capture());
        assertThat(order.getValue().getTotalAmount()).isEqualByComparingTo("600.00");
        assertThat(r.status()).isEqualTo(OrderStatus.WAIT_SELLER_CONFIRM);
        verify(flow).created(order.getValue());
    }

    @Test
    void conditionalUpdateMissMeansStockNotEnough() {
        when(stockCache.tryDeduct(eq(GOODS), eq(1), any())).thenReturn(true);
        when(goodsMapper.selectById(GOODS)).thenReturn(goods(1));
        when(goodsMapper.update(any(Wrapper.class))).thenReturn(0);
        expect(ErrorCode.STOCK_NOT_ENOUGH, req(1, TradeMode.FACE));
        verify(goodsMapper, times(1)).update(any(Wrapper.class));
        verify(orderMapper, never()).insert(any(TradeOrder.class));
    }

    @Test
    void goodsTakenDownDuringOrderIsNotBuyable() {
        when(stockCache.tryDeduct(eq(GOODS), eq(1), any())).thenReturn(true);
        Goods off = goods(1);
        off.setStatus(GoodsStatus.OFF_SHELF);
        when(goodsMapper.selectById(GOODS)).thenReturn(goods(1), off);
        when(goodsMapper.update(any(Wrapper.class))).thenReturn(0);
        expect(ErrorCode.GOODS_NOT_BUYABLE, req(1, TradeMode.FACE));
    }

    @Test
    void cannotBuyOwnGoods() {
        when(stockCache.tryDeduct(eq(GOODS), eq(1), any())).thenReturn(true);
        Goods g = goods(1);
        g.setSellerId(BUYER);
        when(goodsMapper.selectById(GOODS)).thenReturn(g);
        expect(ErrorCode.BUY_SELF, req(1, TradeMode.FACE));
    }

    @Test
    void guaranteeNeedsSellerCreditAtLeast60() {
        when(props.pay()).thenReturn(pay("mock"));
        when(stockCache.tryDeduct(eq(GOODS), eq(1), any())).thenReturn(true);
        when(goodsMapper.selectById(GOODS)).thenReturn(goods(1));
        when(goodsMapper.update(any(Wrapper.class))).thenReturn(1);
        when(userService.require(SELLER)).thenReturn(user(SELLER, 59));
        expect(ErrorCode.TRADE_MODE_UNSUPPORTED, req(1, TradeMode.GUARANTEE));
        verify(orderMapper, never()).insert(any(TradeOrder.class));
    }

    @Test
    void guaranteeRejectedWhenPaymentDisabled() {
        when(props.pay()).thenReturn(pay("disabled"));
        expect(ErrorCode.TRADE_MODE_UNSUPPORTED, req(1, TradeMode.GUARANTEE));
        verify(stockCache, never()).tryDeduct(anyLong(), anyInt(), any());
        verify(orderMapper, never()).insert(any(TradeOrder.class));
    }

    @Test
    void duplicateRequestReturnsExistingOrder() {
        when(ops.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get("order:req:" + BUYER + ":req-1")).thenReturn("9001");
        TradeOrder existing = new TradeOrder();
        existing.setId(9001L);
        existing.setOrderNo("202611221200001");
        existing.setTradeMode(TradeMode.FACE);
        existing.setStatus(OrderStatus.WAIT_SELLER_CONFIRM);
        when(orderMapper.selectById(9001L)).thenReturn(existing);

        CreateResp r = service.create(BUYER, req(1, TradeMode.FACE));

        assertThat(r.orderNo()).isEqualTo("202611221200001");
        verify(stockCache, never()).tryDeduct(anyLong(), anyInt(), any());
    }

    @Test
    void duplicateRequestBeforeCommitAsksToWait() {
        when(ops.setIfAbsent(anyString(), eq("PENDING"), any(Duration.class))).thenReturn(false);
        when(ops.get("order:req:" + BUYER + ":req-1")).thenReturn("9001");
        // 订单 id 已写入但订单还查不到（未提交）：提示稍候，而不是空指针 500
        expect(ErrorCode.CONFLICT, req(1, TradeMode.FACE));
    }
}
