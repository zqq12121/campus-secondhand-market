package com.campustrade.module.notification;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.entity.Notification;
import com.campustrade.infra.RealtimePusher;
import com.campustrade.mapper.NotificationMapper;
import com.campustrade.module.chat.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/** 站内通知：落库后（事务提交后）通过 /user/queue/notifications 实时推送 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationMapper mapper;
    private final ChatService chatService;
    private final RealtimePusher pusher;

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
    }

    public int unreadCount(long userId) {
        return mapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getUserId, userId).eq(Notification::getIsRead, 0)).intValue();
    }
}
