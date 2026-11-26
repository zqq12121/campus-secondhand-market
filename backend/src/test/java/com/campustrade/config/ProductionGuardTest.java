package com.campustrade.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 生产启动自检：示例密钥、模拟支付、关闭邮件、localhost 地址都要拦下 */
class ProductionGuardTest {

    private static final String S1 = "Zk3q9V0bq1xYcR7tL2mN8pW4sE6uH5aJ0dF3gK9iO1";
    private static final String S2 = "Qw8eR4tY2uI6oP0aS3dF7gH1jK5lZ9xC4vB8nM2bV6";

    private static AppProperties props(String userSecret, String adminSecret, String adminPwd, String payMode,
                                       String gateway, boolean mail, String uploadBase) {
        return new AppProperties(new AppProperties.Jwt(userSecret, adminSecret, 1, 1),
                new AppProperties.Mail(mail, "noreply@example.com"),
                new AppProperties.Upload("/app/uploads", uploadBase),
                new AppProperties.AdminInit("admin", adminPwd), null,
                new AppProperties.Pay(payMode, null, null, null, gateway, null, null));
    }

    @Test
    void validProductionConfigPasses() {
        List<String> p = ProductionGuard.check(props(S1, S2, "", "disabled", null, true,
                "https://trade.example.edu.cn/uploads"));
        assertThat(p).isEmpty();
    }

    @Test
    void unsafeConfigReportsEveryProblem() {
        List<String> p = ProductionGuard.check(props("change-me-to-a-random-string-of-at-least-32-bytes", S2,
                "change-me", "mock", null, false, "http://localhost:8088/uploads"));
        assertThat(p).hasSize(5);
    }

    @Test
    void sameSecretsAndSandboxGatewayRejected() {
        List<String> p = ProductionGuard.check(props(S1, S1, "", "alipay",
                "https://openapi-sandbox.dl.alipaydev.com/gateway.do", true, "https://trade.example.edu.cn/uploads"));
        assertThat(p).hasSize(2);
    }
}
