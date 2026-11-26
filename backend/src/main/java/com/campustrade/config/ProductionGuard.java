package com.campustrade.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产环境（SPRING_PROFILES_ACTIVE=prod）启动自检：配置不安全或不可用时直接拒绝启动，
 * 避免带着示例密钥、模拟支付、关闭的邮件上线。
 */
@Slf4j
@Component
@Profile("prod")
@RequiredArgsConstructor
public class ProductionGuard implements InitializingBean {

    private static final String PLACEHOLDER = "change-me";

    private final AppProperties props;

    @Override
    public void afterPropertiesSet() {
        List<String> problems = check(props);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("生产环境配置不合格：\n - " + String.join("\n - ", problems));
        }
        log.info("生产环境配置自检通过，支付模式 {}", props.pay().mode());
    }

    static List<String> check(AppProperties p) {
        List<String> problems = new ArrayList<>();
        String userSecret = p.jwt().userSecret();
        String adminSecret = p.jwt().adminSecret();
        if (isPlaceholder(userSecret) || isPlaceholder(adminSecret)) {
            problems.add("JWT_USER_SECRET / JWT_ADMIN_SECRET 仍是示例值，请用 openssl rand -base64 48 生成");
        } else if (userSecret.equals(adminSecret)) {
            problems.add("JWT_USER_SECRET 与 JWT_ADMIN_SECRET 不能相同");
        }
        if (p.adminInit().password() != null && !p.adminInit().password().isEmpty()
                && (isPlaceholder(p.adminInit().password()) || p.adminInit().password().length() < 12)) {
            problems.add("ADMIN_INIT_PASSWORD 至少 12 位且不能是示例值（首次启动后可清空）");
        }
        if ("mock".equalsIgnoreCase(p.pay().mode())) {
            problems.add("PAY_MODE=mock 会让任何人把订单标记为已支付；未接支付请设为 disabled");
        }
        if ("alipay".equalsIgnoreCase(p.pay().mode()) && p.pay().gateway() != null
                && p.pay().gateway().contains("sandbox")) {
            problems.add("PAY_MODE=alipay 但 ALIPAY_GATEWAY 是沙箱地址");
        }
        if (!p.mail().enabled()) {
            problems.add("MAIL_ENABLED=false 时注册验证码只写日志，用户无法注册；请配置 SMTP");
        }
        if (isLocal(p.upload().publicBaseUrl())) {
            problems.add("UPLOAD_PUBLIC_BASE_URL 指向 localhost，请改为站点域名（PUBLIC_BASE_URL）");
        }
        return problems;
    }

    private static boolean isPlaceholder(String v) {
        return v == null || v.isBlank() || v.startsWith(PLACEHOLDER);
    }

    private static boolean isLocal(String url) {
        return url == null || url.contains("://localhost") || url.contains("://127.0.0.1");
    }
}
