package com.campustrade.infra;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 图形验证码：能生成 PNG、不区分大小写、用一次即作废 */
class CaptchaServiceTest {

    private ValueOperations<String, String> ops;
    private CaptchaService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        service = new CaptchaService(redis);
    }

    @Test
    void createsPngAndStoresAnswer() throws Exception {
        CaptchaService.Captcha c = service.create();
        assertThat(c.image()).startsWith("data:image/png;base64,");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(c.image().substring("data:image/png;base64,".length()))));
        assertThat(img.getWidth()).isEqualTo(120);
        assertThat(img.getHeight()).isEqualTo(40);
        verify(ops).set(eq("captcha:" + c.captchaId()), anyString(), eq(Duration.ofMinutes(5)));
    }

    @Test
    void verifyIgnoresCaseAndConsumes() {
        when(ops.getAndDelete("captcha:abc")).thenReturn("K7XM");
        service.verify("abc", " k7xm ");
        verify(ops).getAndDelete("captcha:abc");
    }

    @Test
    void wrongOrMissingRejected() {
        when(ops.getAndDelete("captcha:abc")).thenReturn("K7XM");
        assertInvalid("abc", "K7XN");
        assertInvalid("abc", "");
        assertInvalid(null, "K7XM");
        // 已经被用掉（或过期）
        when(ops.getAndDelete("captcha:used")).thenReturn(null);
        assertInvalid("used", "K7XM");
    }

    private void assertInvalid(String id, String answer) {
        assertThatThrownBy(() -> service.verify(id, answer)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CAPTCHA_INVALID);
    }
}
