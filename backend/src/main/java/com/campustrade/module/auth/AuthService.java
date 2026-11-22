package com.campustrade.module.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.UserStatus;
import com.campustrade.common.CreditLevel;
import com.campustrade.common.ErrorCode;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.Campus;
import com.campustrade.entity.School;
import com.campustrade.entity.User;
import com.campustrade.infra.CaptchaService;
import com.campustrade.infra.MailService;
import com.campustrade.infra.RateLimiter;
import com.campustrade.mapper.CampusMapper;
import com.campustrade.mapper.SchoolMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.auth.AuthDtos.LoginResp;
import com.campustrade.module.auth.AuthDtos.LoginUserVO;
import com.campustrade.module.auth.AuthDtos.RegisterReq;
import com.campustrade.security.JwtService;
import com.campustrade.security.TokenStore;
import com.campustrade.security.TokenType;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AuthService {

    public static final Pattern PASSWORD = Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d).{8,20}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 连续失败达到此次数后，登录需要图形验证码 */
    static final int CAPTCHA_AFTER_FAILS = 3;

    private final UserMapper userMapper;
    private final SchoolMapper schoolMapper;
    private final CampusMapper campusMapper;
    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final MailService mailService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final CaptchaService captchaService;
    private final AppProperties props;

    public void sendCode(String rawEmail, String scene, String ip) {
        String email = normalize(rawEmail);
        schoolOf(email);
        boolean registered = findByEmail(email) != null;
        if ("REGISTER".equals(scene)) {
            Biz.check(!registered, ErrorCode.EMAIL_REGISTERED);
        } else {
            Biz.check(registered, ErrorCode.EMAIL_NOT_REGISTERED);
        }
        AppProperties.Rules r = props.rules();
        Boolean first = redis.opsForValue().setIfAbsent("code:resend:" + email, "1",
                Duration.ofSeconds(r.codeResendSeconds()));
        Biz.check(Boolean.TRUE.equals(first), ErrorCode.CODE_TOO_FREQUENT);
        rateLimiter.acquire("code:ip:" + ip, 20, 3600);
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        redis.opsForValue().set(codeKey(scene, email), code, Duration.ofSeconds(r.codeTtlSeconds()));
        mailService.sendCode(email, code, r.codeTtlSeconds() / 60);
    }

    public LoginResp register(RegisterReq req) {
        String email = normalize(req.email());
        School school = schoolOf(email);
        checkPassword(req.password());
        Campus campus = campusMapper.selectById(req.campusId());
        Biz.check(campus != null && campus.getSchoolId().equals(school.getId()), ErrorCode.BAD_REQUEST, "校区不存在");
        Biz.check(findByEmail(email) == null, ErrorCode.EMAIL_REGISTERED);
        consumeCode("REGISTER", email, req.code());

        User user = new User();
        user.setSchoolId(school.getId());
        user.setCampusId(campus.getId());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setNickname(req.nickname().trim());
        user.setLastLoginAt(LocalDateTime.now());
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.EMAIL_REGISTERED);
        }
        return loginResp(userMapper.selectById(user.getId()));
    }

    public LoginResp login(String rawEmail, String password, String captchaId, String captchaCode) {
        String email = normalize(rawEmail);
        AppProperties.Rules r = props.rules();
        String lockKey = "login:lock:" + email;
        Long lockTtl = redis.getExpire(lockKey);
        if (lockTtl != null && lockTtl > 0) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED, Map.of("retryAfter", lockTtl.intValue()));
        }
        String failKey = "login:fail:" + email;
        String failed = redis.opsForValue().get(failKey);
        if (failed != null && Integer.parseInt(failed) >= CAPTCHA_AFTER_FAILS) {
            Biz.check(captchaId != null && !captchaId.isBlank(), ErrorCode.CAPTCHA_REQUIRED);
            captchaService.verify(captchaId, captchaCode);
        }
        User user = findByEmail(email);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            Long fails = redis.opsForValue().increment(failKey);
            if (fails != null && fails == 1) {
                redis.expire(failKey, Duration.ofMinutes(r.loginLockMinutes()));
            }
            if (fails != null && fails >= r.loginMaxFail()) {
                redis.delete(failKey);
                redis.opsForValue().set(lockKey, "1", Duration.ofMinutes(r.loginLockMinutes()));
                throw BizException.of(ErrorCode.ACCOUNT_LOCKED, Map.of("retryAfter", r.loginLockMinutes() * 60));
            }
            long remaining = r.loginMaxFail() - (fails == null ? 0 : fails);
            throw BizException.of(ErrorCode.BAD_CREDENTIALS, Map.of("remainingAttempts", (int) remaining,
                    "captchaRequired", fails != null && fails >= CAPTCHA_AFTER_FAILS));
        }
        if (isBanned(user)) {
            Map<String, Object> data = new HashMap<>();
            data.put("banUntil", user.getBanUntil());
            throw BizException.of(ErrorCode.ACCOUNT_BANNED, data);
        }
        redis.delete(failKey);
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, user.getId())
                .set(User::getLastLoginAt, LocalDateTime.now()));
        return loginResp(user);
    }

    public void resetPassword(String rawEmail, String code, String newPassword) {
        String email = normalize(rawEmail);
        checkPassword(newPassword);
        User user = Biz.notNull(findByEmail(email), ErrorCode.EMAIL_NOT_REGISTERED);
        consumeCode("RESET_PASSWORD", email, code);
        userMapper.update(new LambdaUpdateWrapper<User>().eq(User::getId, user.getId())
                .set(User::getPasswordHash, passwordEncoder.encode(newPassword)));
        tokenStore.invalidateAll(TokenType.USER, user.getId());
    }

    public static void checkPassword(String password) {
        Biz.check(password != null && PASSWORD.matcher(password).matches(), ErrorCode.PASSWORD_FORMAT);
    }

    public static boolean isBanned(User u) {
        return u.getStatus() == UserStatus.BANNED
                && (u.getBanUntil() == null || u.getBanUntil().isAfter(LocalDateTime.now()));
    }

    public LoginResp loginResp(User u) {
        JwtService.Issued issued = jwtService.issue(u.getId(), TokenType.USER);
        LoginUserVO vo = new LoginUserVO(u.getId(), u.getNickname(), u.getAvatarUrl(), u.getCampusId(),
                u.getCreditScore(), CreditLevel.of(u.getCreditScore()).name(), u.getStatus());
        return new LoginResp(issued.token(), issued.expiresIn(), vo);
    }

    private void consumeCode(String scene, String email, String code) {
        String key = codeKey(scene, email);
        String saved = redis.opsForValue().get(key);
        Biz.check(saved != null && saved.equals(code), ErrorCode.CODE_INVALID);
        redis.delete(key);
    }

    private School schoolOf(String email) {
        int at = email.lastIndexOf('@');
        if (at < 0) {
            throw new BizException(ErrorCode.EMAIL_SUFFIX_INVALID);
        }
        School school = schoolMapper.selectOne(new LambdaQueryWrapper<School>()
                .eq(School::getEmailSuffix, email.substring(at)));
        return Biz.notNull(school, ErrorCode.EMAIL_SUFFIX_INVALID);
    }

    private User findByEmail(String email) {
        return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getEmail, email));
    }

    private static String codeKey(String scene, String email) {
        return "code:" + scene + ":" + email;
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
