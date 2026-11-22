package com.campustrade.module.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record SendCodeReq(@NotBlank @Email String email,
                              @NotBlank @Pattern(regexp = "REGISTER|RESET_PASSWORD") String scene,
                              @NotBlank String captchaId,
                              @NotBlank String captchaCode) {
    }

    public record RegisterReq(@NotBlank @Email String email,
                              @NotBlank String code,
                              @NotBlank String password,
                              @NotBlank @Size(min = 2, max = 16, message = "昵称 2–16 字") String nickname,
                              @NotNull Long campusId) {
    }

    /** 登录失败达到一定次数后必须带图形验证码 */
    public record LoginReq(@NotBlank String email, @NotBlank String password, String captchaId, String captchaCode) {
    }

    public record ResetPasswordReq(@NotBlank @Email String email, @NotBlank String code,
                                   @NotBlank String newPassword) {
    }

    public record LoginUserVO(Long id, String nickname, String avatarUrl, Long campusId, Integer creditScore,
                              String creditLevel, Integer status) {
    }

    public record LoginResp(String token, long expiresIn, LoginUserVO user) {
    }
}
