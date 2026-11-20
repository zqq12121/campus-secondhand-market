package com.campustrade.module.chat;

import com.campustrade.common.BizException;
import com.campustrade.common.ErrorCode;
import com.campustrade.infra.RealtimePusher;
import com.campustrade.module.chat.ChatService.WsSendReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.util.Map;

/** STOMP 入口：/app/chat.send、/app/chat.read；失败推送到 /user/queue/errors */
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatWsController {

    private final ChatService chatService;
    private final RealtimePusher pusher;

    public record ErrorEvent(String event, String clientMsgId, int code, String message) {
    }

    @MessageMapping("/chat.send")
    public void send(@Payload WsSendReq req, Principal principal) {
        long me = Long.parseLong(principal.getName());
        try {
            if (req.conversationId() == null || req.type() == null || req.content() == null
                    || req.content().length() > 1000) {
                throw new BizException(ErrorCode.BAD_REQUEST);
            }
            chatService.send(me, req.conversationId(), req.type(), req.content(), req.clientMsgId());
        } catch (BizException e) {
            pusher.push(me, RealtimePusher.ERRORS,
                    new ErrorEvent("ERROR", req.clientMsgId(), e.getErrorCode().getCode(), e.getMessage()));
        } catch (RuntimeException e) {
            log.error("chat.send 失败 userId={}", me, e);
            pusher.push(me, RealtimePusher.ERRORS, new ErrorEvent("ERROR", req.clientMsgId(),
                    ErrorCode.INTERNAL.getCode(), ErrorCode.INTERNAL.getMessage()));
        }
    }

    @MessageMapping("/chat.read")
    public void read(@Payload Map<String, Object> req, Principal principal) {
        long me = Long.parseLong(principal.getName());
        Object id = req.get("conversationId");
        if (id == null) {
            return;
        }
        try {
            chatService.markRead(me, Long.parseLong(String.valueOf(id)));
        } catch (BizException | NumberFormatException ignored) {
            // 已读回执失败不提示
        }
    }
}
