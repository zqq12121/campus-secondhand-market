package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.campustrade.common.Biz;
import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.R;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.AdminPermission;
import com.campustrade.entity.AdminUser;
import com.campustrade.mapper.AdminPermissionMapper;
import com.campustrade.mapper.AdminUserMapper;
import com.campustrade.module.auth.AuthService;
import com.campustrade.security.CurrentUser;
import com.campustrade.security.JwtService;
import com.campustrade.security.LoginUser;
import com.campustrade.security.TokenStore;
import com.campustrade.security.TokenType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/v1/auth")
@RequiredArgsConstructor
public class AdminAuthController {

    private final AdminUserMapper adminUserMapper;
    private final AdminPermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenStore tokenStore;
    private final StringRedisTemplate redis;
    private final AppProperties props;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {
    }

    public record PasswordReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    public record AdminBrief(Long id, String username, String realName) {
    }

    public record LoginResp(String token, long expiresIn, AdminBrief admin) {
    }

    public record MenuChild(String code, String name) {
    }

    public record Menu(String code, String name, List<MenuChild> children) {
    }

    public record MeVO(Long id, String username, String realName, List<String> roles, Set<String> permissions,
                       List<Menu> menus) {
    }

    @PostMapping("/login")
    public R<LoginResp> login(@Valid @RequestBody LoginReq req) {
        String username = req.username().trim();
        AppProperties.Rules r = props.rules();
        String lockKey = "admin:login:lock:" + username;
        Long ttl = redis.getExpire(lockKey);
        if (ttl != null && ttl > 0) {
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED, Map.of("retryAfter", ttl.intValue()));
        }
        AdminUser u = adminUserMapper.selectOne(new LambdaQueryWrapper<AdminUser>()
                .eq(AdminUser::getUsername, username));
        if (u == null || !passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            String failKey = "admin:login:fail:" + username;
            Long fails = redis.opsForValue().increment(failKey);
            redis.expire(failKey, Duration.ofMinutes(r.loginLockMinutes()));
            if (fails != null && fails >= r.loginMaxFail()) {
                redis.delete(failKey);
                redis.opsForValue().set(lockKey, "1", Duration.ofMinutes(r.loginLockMinutes()));
                throw BizException.of(ErrorCode.ACCOUNT_LOCKED, Map.of("retryAfter", r.loginLockMinutes() * 60));
            }
            throw new BizException(ErrorCode.BAD_CREDENTIALS, "用户名或密码错误");
        }
        Biz.check(u.getStatus() == 1, ErrorCode.ACCOUNT_BANNED, "账号已停用");
        redis.delete("admin:login:fail:" + username);
        adminUserMapper.update(new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, u.getId())
                .set(AdminUser::getLastLoginAt, LocalDateTime.now()));
        JwtService.Issued issued = jwtService.issue(u.getId(), TokenType.ADMIN);
        return R.ok(new LoginResp(issued.token(), issued.expiresIn(),
                new AdminBrief(u.getId(), u.getUsername(), u.getRealName())));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        tokenStore.blacklist(CurrentUser.require());
        return R.ok();
    }

    @GetMapping("/me")
    public R<MeVO> me() {
        LoginUser me = CurrentUser.require();
        AdminUser u = adminUserMapper.selectById(me.id());
        List<AdminPermission> all = permissionMapper.selectList(new LambdaQueryWrapper<AdminPermission>()
                .orderByAsc(AdminPermission::getSort));
        List<Menu> menus = all.stream().filter(p -> p.getParentId() == 0 && me.permissions().contains(p.getCode()))
                .map(m -> new Menu(m.getCode(), m.getName(), all.stream()
                        .filter(c -> c.getParentId().equals(m.getId()) && me.permissions().contains(c.getCode()))
                        .map(c -> new MenuChild(c.getCode(), c.getName())).toList()))
                .toList();
        return R.ok(new MeVO(u.getId(), u.getUsername(), u.getRealName(), adminUserMapper.selectRoleCodes(u.getId()),
                me.permissions(), menus));
    }

    @PutMapping("/password")
    public R<Void> changePassword(@Valid @RequestBody PasswordReq req) {
        long id = CurrentUser.id();
        AdminUser u = adminUserMapper.selectById(id);
        Biz.check(passwordEncoder.matches(req.oldPassword(), u.getPasswordHash()), ErrorCode.BAD_CREDENTIALS, "原密码错误");
        AuthService.checkPassword(req.newPassword());
        adminUserMapper.update(new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id)
                .set(AdminUser::getPasswordHash, passwordEncoder.encode(req.newPassword())));
        tokenStore.invalidateAll(TokenType.ADMIN, id);
        return R.ok();
    }
}
