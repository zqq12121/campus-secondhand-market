package com.campustrade.infra;

import com.campustrade.common.Biz;
import com.campustrade.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/** 图形验证码（P2）：4 位字符 PNG，Redis 存 5 分钟，校验一次即作废（无论对错） */
@Service
@RequiredArgsConstructor
public class CaptchaService {

    /** 去掉 0/O、1/l/I 等易混字符 */
    private static final String CHARS = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LENGTH = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final StringRedisTemplate redis;

    /** image 为 data:image/png;base64,... 可直接放进 img 的 src */
    public record Captcha(String captchaId, String image, int expiresIn) {
    }

    public Captcha create() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < LENGTH; i++) {
            text.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        redis.opsForValue().set(key(id), text.toString(), TTL);
        return new Captcha(id, "data:image/png;base64," + Base64.getEncoder().encodeToString(render(text.toString())),
                (int) TTL.toSeconds());
    }

    /** 不区分大小写；缺参数或错误都报 CAPTCHA_INVALID */
    public void verify(String captchaId, String answer) {
        Biz.check(captchaId != null && answer != null && !answer.isBlank(), ErrorCode.CAPTCHA_INVALID);
        String expect = redis.opsForValue().getAndDelete(key(captchaId));
        Biz.check(expect != null && expect.equalsIgnoreCase(answer.trim()), ErrorCode.CAPTCHA_INVALID);
    }

    static byte[] render(String text) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 246, 248));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 6; i++) {
                g.setColor(randomColor(150, 210));
                g.setStroke(new BasicStroke(1 + RANDOM.nextFloat()));
                g.drawLine(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            int step = WIDTH / (LENGTH + 1);
            for (int i = 0; i < text.length(); i++) {
                AffineTransform old = g.getTransform();
                int x = step / 2 + i * step + RANDOM.nextInt(6);
                int y = 28 + RANDOM.nextInt(6);
                g.rotate(Math.toRadians(RANDOM.nextInt(41) - 20), x, y);
                g.setColor(randomColor(20, 110));
                g.drawString(String.valueOf(text.charAt(i)), x, y);
                g.setTransform(old);
            }
            for (int i = 0; i < 60; i++) {
                img.setRGB(RANDOM.nextInt(WIDTH), RANDOM.nextInt(HEIGHT), randomColor(100, 200).getRGB());
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static Color randomColor(int min, int max) {
        int span = max - min;
        return new Color(min + RANDOM.nextInt(span), min + RANDOM.nextInt(span), min + RANDOM.nextInt(span));
    }

    private static String key(String id) {
        return "captcha:" + id;
    }
}
