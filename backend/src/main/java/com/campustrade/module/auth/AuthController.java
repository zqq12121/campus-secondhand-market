package com.campustrade.module.auth;

import com.campustrade.common.R;
import com.campustrade.infra.CaptchaService;
import com.campustrade.module.auth.AuthDtos.LoginReq;
import com.campustrade.module.auth.AuthDtos.LoginResp;
import com.campustrade.module.auth.AuthDtos.RegisterReq;
import com.campustrade.module.auth.AuthDtos.ResetPasswordReq;
import com.campustrade.module.auth.AuthDtos.SendCodeReq;
import com.campustrade.security.CurrentUser;
import com.campustrade.security.TokenStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final TokenStore tokenStore;
    private final CaptchaService captchaService;

    @GetMapping("/captcha")
    public R<CaptchaService.Captcha> captcha() {
        return R.ok(captchaService.create());
    }

    @PostMapping("/email-codes")
    public R<Void> sendCode(@Valid @RequestBody SendCodeReq req, HttpServletRequest http) {
        captchaService.verify(req.captchaId(), req.captchaCode());
        authService.sendCode(req.email(), req.scene(), http.getRemoteAddr());
        return R.ok();
    }

    @PostMapping("/register")
    public R<LoginResp> register(@Valid @RequestBody RegisterReq req) {
        return R.ok(authService.register(req));
    }

    @PostMapping("/login")
    public R<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return R.ok(authService.login(req.email(), req.password(), req.captchaId(), req.captchaCode()));
    }

    @PostMapping("/logout")
    public R<Void> logout() {
        tokenStore.blacklist(CurrentUser.require());
        return R.ok();
    }

    @PostMapping("/password/reset")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordReq req) {
        authService.resetPassword(req.email(), req.code(), req.newPassword());
        return R.ok();
    }
}
