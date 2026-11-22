package com.campustrade.module.auth;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.infra.CaptchaService;
import com.campustrade.entity.User;
import com.campustrade.mapper.UserMapper;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 登录连续失败 3 次后必须带图形验证码 */
@ExtendWith(MockitoExtension.class)
class AuthServiceLoginCaptchaTest {

    private static final String EMAIL = "zhangsan@wu.edu.cn";
    private static final String FAIL_KEY = "login:fail:" + EMAIL;

    @Mock
    private UserMapper userMapper;
    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;
    @Mock
    private CaptchaService captchaService;
    @Mock
    private AppProperties props;
    @InjectMocks
    private AuthService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(User.class);
    }

    @BeforeEach
    void setUp() {
        AppProperties.Rules rules = mock(AppProperties.Rules.class);
        lenient().when(rules.loginMaxFail()).thenReturn(5);
        lenient().when(rules.loginLockMinutes()).thenReturn(15);
        when(props.rules()).thenReturn(rules);
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void captchaRequiredAfterThreeFailures() {
        when(ops.get(FAIL_KEY)).thenReturn("3");
        assertThatThrownBy(() -> service.login(EMAIL, "abc12345", null, null)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_REQUIRED);
        // 没过验证码就不查用户、不累计失败次数
        verify(userMapper, never()).selectOne(any());
        verify(ops, never()).increment(anyString());
    }

    @Test
    void thirdFailureTellsClientToShowCaptcha() {
        when(ops.get(FAIL_KEY)).thenReturn("2");
        when(ops.increment(FAIL_KEY)).thenReturn(3L);
        assertThatThrownBy(() -> service.login(EMAIL, "wrong", null, null)).isInstanceOf(BizException.class)
                .satisfies(e -> {
                    BizException b = (BizException) e;
                    assertThat(b.getErrorCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS);
                    @SuppressWarnings("unchecked") Map<String, Object> data = (Map<String, Object>) b.getData();
                    assertThat(data).containsEntry("captchaRequired", true).containsEntry("remainingAttempts", 2);
                });
        verify(captchaService, never()).verify(any(), any());
    }

    @Test
    void captchaCheckedBeforePassword() {
        when(ops.get(FAIL_KEY)).thenReturn("4");
        when(ops.increment(FAIL_KEY)).thenReturn(5L);
        assertThatThrownBy(() -> service.login(EMAIL, "wrong", "cid", "K7XM")).isInstanceOf(BizException.class);
        verify(captchaService).verify("cid", "K7XM");
    }
}
