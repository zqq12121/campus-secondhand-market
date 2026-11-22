package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.ErrorCode;
import com.campustrade.entity.Goods;
import com.campustrade.entity.User;
import com.campustrade.entity.UserFollow;
import com.campustrade.mapper.UserFollowMapper;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 关注卖家：不能关注自己、重复关注幂等、上新只通知一次 */
@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    @Mock
    private UserFollowMapper followMapper;
    @Mock
    private UserService userService;
    @Mock
    private NotificationService notificationService;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @InjectMocks
    private FollowService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(UserFollow.class);
    }

    @BeforeEach
    void setUp() {
        lenient().when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void cannotFollowSelf() {
        assertThatThrownBy(() -> service.follow(1L, 1L)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
    }

    @Test
    void duplicateFollowIsIdempotent() {
        doThrow(new DuplicateKeyException("uk_pair")).when(followMapper).insert(any(UserFollow.class));
        assertThatCode(() -> service.follow(1L, 2L)).doesNotThrowAnyException();
    }

    private static Goods goods() {
        Goods g = new Goods();
        g.setId(301L);
        g.setSellerId(2048L);
        g.setTitle("显示器");
        g.setPrice(new BigDecimal("300.00"));
        return g;
    }

    private static UserFollow fan(long userId) {
        UserFollow f = new UserFollow();
        f.setUserId(userId);
        f.setFolloweeId(2048L);
        return f;
    }

    @Test
    @SuppressWarnings("unchecked")
    void newGoodsNotifiesFans() {
        when(ops.setIfAbsent(eq("follow:notify:301"), eq("1"), any(Duration.class))).thenReturn(true);
        when(followMapper.selectList(any(Wrapper.class))).thenReturn(List.of(fan(1L), fan(2L)));
        User seller = new User();
        seller.setNickname("数码小陈");
        when(userService.require(2048L)).thenReturn(seller);
        assertThat(service.notifyFollowers(goods())).isEqualTo(2);
        verify(notificationService).send(eq(2L), eq(NotifyType.FOLLOW), anyString(), anyString(), any(), eq(301L));
    }

    @Test
    void reShelfDoesNotNotifyAgain() {
        when(ops.setIfAbsent(eq("follow:notify:301"), eq("1"), any(Duration.class))).thenReturn(false);
        assertThat(service.notifyFollowers(goods())).isZero();
        verify(notificationService, never()).send(anyLong(), anyInt(), anyString(), anyString(), any(), any());
    }
}
