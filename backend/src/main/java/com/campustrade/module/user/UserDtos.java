package com.campustrade.module.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public final class UserDtos {

    private UserDtos() {
    }

    /** 列表里嵌套的用户简要信息 */
    public record UserBrief(Long id, String nickname, String avatarUrl) {
    }

    public record Stats(int onSaleCount, int soldCount, int boughtCount, int favoriteCount, String goodRate) {
    }

    public record MeVO(Long id, String email, boolean verified, String nickname, String avatarUrl, String college,
                       Long campusId, String campusName, String bio, Integer creditScore, String creditLevel,
                       BigDecimal balance, Integer status, LocalDateTime banUntil, Stats stats,
                       LocalDateTime createdAt) {
    }

    public record UpdateMeReq(@Size(min = 2, max = 16, message = "昵称 2–16 字") String nickname,
                              @Size(max = 255) String avatarUrl,
                              @Size(max = 64) String college,
                              Long campusId,
                              @Size(max = 200) String bio) {
    }

    public record ChangePasswordReq(@NotBlank String oldPassword, @NotBlank String newPassword) {
    }

    public record CreditLogVO(Long id, Integer delta, Integer scoreAfter, Integer reasonType, String reasonText,
                              Long refId, String remark, LocalDateTime createdAt) {
    }

    public record BalanceLogVO(Long id, String amount, BigDecimal balanceAfter, Integer type, Long orderId,
                               String orderNo, LocalDateTime createdAt) {
    }

    public record BlockVO(Long userId, String nickname, String avatarUrl, LocalDateTime createdAt) {
    }

    public record ProfileVO(Long id, String nickname, String avatarUrl, String college, String campusName, String bio,
                            Integer creditScore, String creditLevel, boolean badge, String goodRate, int reviewCount,
                            int onSaleCount, int soldCount, boolean blocked, Integer status, LocalDate joinedAt) {
    }
}
