package com.campustrade.module.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.TxUtil;
import com.campustrade.entity.Notification;
import com.campustrade.entity.User;
import com.campustrade.infra.MailService;
import com.campustrade.infra.RealtimePusher;
import com.campustrade.mapper.NotificationMapper;
import com.campustrade.mapper.UserMapper;
import com.campustrade.module.chat.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** 站内通知：落库后（事务提交后）通过 /user/queue/notifications 实时推送 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper mapper;
    private final ChatService chatService;
    private final RealtimePusher pusher;
    private final UserMapper userMapper;
    private final MailService mailService;

    /** 开启邮件通知后，这几类通知同时发邮件；求购匹配、关注上新、降价提醒、公告量大，只发站内 */
    static final Set<Integer> EMAIL_TYPES = Set.of(NotifyType.ORDER, NotifyType.AUDIT, NotifyType.REPORT, NotifyType.CREDIT);

    public record NotificationVO(Long id, Integer type, String title, String content, Integer refType, Long refId,
                                 boolean isRead, LocalDateTime createdAt) {
    }

    public record Unread(int notification, int chat) {
    }

    public record NotificationEvent(String event, NotificationVO notification, Unread unread) {
    }

    public static NotificationVO toVO(Notification n) {
        return new NotificationVO(n.getId(), n.getType(), n.getTitle(), n.getContent(), n.getRefType(), n.getRefId(),
                n.getIsRead() == 1, n.getCreatedAt());
    }

    public void send(long userId, int type, String title, String content, Integer refType, Long refId) {
        Notification n = new Notification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setContent(content);
        n.setRefType(refType);
        n.setRefId(refId);
        n.setIsRead(0);
        n.setCreatedAt(LocalDateTime.now());
        mapper.insert(n);
        pusher.push(userId, RealtimePusher.NOTIFICATIONS, new NotificationEvent("NOTIFICATION", toVO(n),
                new Unread(unreadCount(userId), chatService.totalUnread(userId))));
        if (EMAIL_TYPES.contains(type)) {
            TxUtil.afterCommit(() -> CompletableFuture.runAsync(() -> emailCopy(userId, title, content)));
        }
    }

    /** 邮件只是副本：用户没开、发送失败都不影响站内通知 */
    void emailCopy(long userId, String title, String content) {
        try {
            User u = userMapper.selectById(userId);
            if (u != null && Integer.valueOf(1).equals(u.getEmailNotify())) {
                mailService.sendNotice(u.getEmail(), title, content);
            }
        } catch (RuntimeException e) {
            log.warn("通知邮件发送失败 userId={} title={}", userId, title, e);
        }
    }

    public int unreadCount(long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId).eq(Notification::getIsRead, 0)).intValue();
    }
}
