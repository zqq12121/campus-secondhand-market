package com.campustrade.module.want;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时任务：求购帖到期自动过期 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WantExpireJob {

    private final WantService wantService;

    /** 求购帖到期：进行中 → 已过期 */
    @Scheduled(cron = "0 5 * * * *")
    public void expireWants() {
        try {
            wantService.expire(null);
        } catch (RuntimeException e) {
            log.error("求购帖过期 失败", e);
        }
    }
}
