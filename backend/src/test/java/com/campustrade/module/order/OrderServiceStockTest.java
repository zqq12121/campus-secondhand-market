package com.campustrade.module.order;

import com.campustrade.common.BizException;
import com.campustrade.common.Consts.GoodsStatus;
import com.campustrade.common.Consts.TradeMode;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.Goods;
import com.campustrade.entity.User;
import com.campustrade.infra.StockCache;
import com.campustrade.infra.mq.DelayPublisher;
import com.campustrade.mapper.GoodsMapper;
import com.campustrade.mapper.OrderLogMapper;
import com.campustrade.mapper.TradeOrderMapper;
import com.campustrade.module.aftersale.AfterSaleService;
import com.campustrade.module.chat.ChatService;
import com.campustrade.module.order.OrderDtos.CreateReq;
import com.campustrade.module.pay.PaymentService;
import com.campustrade.module.review.ReviewService;
import com.campustrade.module.user.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderServiceStockTest {

    private static final long BUYER = 2L;

    private TradeOrderMapper orderMapper;
    private GoodsMapper goodsMapper;
    private StockCache stockCache;
    private StringRedisTemplate redis;
    private OrderService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        orderMapper = mock(TradeOrderMapper.class);
        goodsMapper = mock(GoodsMapper.class);
        stockCache = mock(StockCache.class);
        redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        when(ops.increment(anyString())).thenReturn(1L);

        UserService userService = mock(UserService.class);
        User buyer = new User();
        buyer.setId(BUYER);
        buyer.setStatus(UserStatus.NORMAL);
        when(userService.require(BUYER)).thenReturn(buyer);

        AppProperties props = mock(AppProperties.class);
        AppProperties.Rules rules = mock(AppProperties.Rules.class);
        when(props.rules()).thenReturn(rules);
        when(rules.dailyCancelLimit()).thenReturn(5);

        when(goodsMapper.selectById(anyLong())).thenAnswer(inv -> goods());
        service = new OrderService(orderMapper, mock(OrderLogMapper.class), goodsMapper, mock(OrderFlow.class),
                userService, mock(ReviewService.class), mock(PaymentService.class), mock(AfterSaleService.class),
                mock(ChatService.class), mock(DelayPublisher.class), stockCache, redis, props);
    }

    private static Goods goods() {
        Goods g = new Goods();
        g.setId(5001L);
        g.setSellerId(1L);
        g.setTitle("台灯");
        g.setPrice(new BigDecimal("20.00"));
        g.setStatus(GoodsStatus.ON_SALE);
        g.setTradeMode(TradeMode.BOTH);
        g.setStock(3);
        g.setVersion(1);
        return g;
    }

    private static CreateReq req() {
        return new CreateReq(5001L, 1, TradeMode.FACE, "req-1");
    }

    @Test
    void redisRejectSkipsDatabaseUpdate() {
        doThrow(new BizException(ErrorCode.STOCK_NOT_ENOUGH)).when(stockCache).reserve(5001L, 1, 3);

        assertThatThrownBy(() -> service.create(BUYER, req())).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.STOCK_NOT_ENOUGH);
        verify(goodsMapper, never()).updateById(any(Goods.class));
        verify(orderMapper, never()).insert(any(com.campustrade.entity.TradeOrder.class));
        verify(redis).delete("order:req:" + BUYER + ":req-1");
    }

    @Test
    void optimisticRetryReservesOnlyOnce() {
        when(goodsMapper.updateById(any(Goods.class))).thenReturn(0, 1);

        service.create(BUYER, req());

        verify(stockCache, times(1)).reserve(5001L, 1, 3);
        verify(goodsMapper, times(2)).updateById(any(Goods.class));
        verify(orderMapper).insert(any(com.campustrade.entity.TradeOrder.class));
    }
}
