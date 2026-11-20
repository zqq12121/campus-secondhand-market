package com.campustrade.infra;

import com.campustrade.common.TxUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** 点对点推送，事务提交后发送，推送失败不影响业务 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimePusher {

    public static final String MESSAGES = "/queue/messages";
    public static final String NOTIFICATIONS = "/queue/notifications";
    public static final String ERRORS = "/queue/errors";

    private final SimpMessagingTemplate template;

    public void push(long userId, String destination, Object payload) {
        TxUtil.afterCommit(() -> {
            try {
                template.convertAndSendToUser(String.valueOf(userId), destination, payload);
            } catch (RuntimeException e) {
                log.warn("推送失败 userId={} dest={}", userId, destination, e);
            }
        });
    }
}
