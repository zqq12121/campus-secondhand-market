package com.campustrade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Mail mail, Upload upload, AdminInit adminInit, Rules rules, Pay pay) {

    public record Jwt(String userSecret, String adminSecret, long userTtlSeconds, long adminTtlSeconds) {
    }

    public record Mail(boolean enabled, String from) {
    }

    public record Upload(String dir, String publicBaseUrl) {
    }

    public record AdminInit(String username, String password) {
    }

    public record Rules(int loginMaxFail, int loginLockMinutes, int codeTtlSeconds, int codeResendSeconds,
                        int maxOnSale, int dailyPublish, int dailyPublishPoorCredit, int goodsExpireDays,
                        Duration faceConfirmTimeout, Duration faceAutoComplete, Duration payTimeout,
                        Duration autoConfirm, Duration afterSaleCancelTimeout, Duration afterSaleRefundTimeout,
                        Duration afterSaleAppealTimeout,
                        int reviewDays, int dailyCancelLimit) {
    }

    /**
     * 支付配置。mode=alipay 走支付宝开放平台；mode=mock 使用本地模拟收银台，
     * 仅用于没有沙箱密钥时的本地开发与演示；mode=disabled 关闭担保交易，只允许面交（生产未接支付时用）。
     */
    public record Pay(String mode, String appId, String privateKey, String alipayPublicKey, String gateway,
                      String notifyUrl, String returnUrl) {

        public boolean isMock() {
            return !"alipay".equalsIgnoreCase(mode);
        }

        public boolean guaranteeEnabled() {
            return !"disabled".equalsIgnoreCase(mode);
        }
    }
}
