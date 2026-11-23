package com.campustrade.module.want;

import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.Consts.RefType;
import com.campustrade.module.goods.entity.Goods;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.user.entity.UserBlock;
import com.campustrade.module.user.mapper.UserBlockMapper;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.mapper.WantPostMapper;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WantMatchServiceTest {

    private WantPostMapper wantMapper;
    private UserBlockMapper blockMapper;
    private NotificationService notificationService;
    private ValueOperations<String, String> ops;
    private WantMatchService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(WantPost.class, UserBlock.class);
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        wantMapper = mock(WantPostMapper.class);
        blockMapper = mock(UserBlockMapper.class);
        notificationService = mock(NotificationService.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new WantMatchService(wantMapper, blockMapper, notificationService, redis);
    }

    private static Goods goods() {
        Goods g = new Goods();
        g.setId(5001L);
        g.setSellerId(1L);
        g.setSchoolId(1L);
        g.setCategoryId(202L);
        g.setTitle("24 寸显示器");
        g.setPrice(new BigDecimal("260.00"));
        return g;
    }

    private static WantPost want(long id, long userId) {
        WantPost w = new WantPost();
        w.setId(id);
        w.setUserId(userId);
        w.setTitle("求显示器");
        return w;
    }

    @Test
    void noCandidateSendsNothing() {
        when(wantMapper.selectList(any())).thenReturn(List.of());
        assertThat(service.match(goods())).isZero();
        verifyNoInteractions(blockMapper, notificationService);
    }

    @Test
    void notifiesCandidatesExceptBlockersAndAlreadyNotified() {
        when(wantMapper.selectList(any())).thenReturn(List.of(want(701, 10), want(702, 11), want(703, 12)));
        UserBlock b = new UserBlock();
        b.setUserId(11L);
        b.setBlockedUserId(1L);
        when(blockMapper.selectList(any())).thenReturn(List.of(b));
        when(ops.setIfAbsent(eq("want:match:701:5001"), eq("1"), any(Duration.class))).thenReturn(true);
        when(ops.setIfAbsent(eq("want:match:703:5001"), eq("1"), any(Duration.class))).thenReturn(false);

        assertThat(service.match(goods())).isEqualTo(1);

        verify(notificationService).send(eq(10L), eq(NotifyType.WANT_MATCH), anyString(),
                contains("¥260.00"), eq(RefType.GOODS), eq(5001L));
        verify(notificationService, never()).send(eq(11L), any(Integer.class), anyString(), anyString(), any(), anyLong());
        verify(notificationService, never()).send(eq(12L), any(Integer.class), anyString(), anyString(), any(), anyLong());
    }
}
