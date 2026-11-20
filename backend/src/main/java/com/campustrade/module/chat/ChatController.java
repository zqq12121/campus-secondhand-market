package com.campustrade.module.chat;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.chat.ChatService.ConversationDetail;
import com.campustrade.module.chat.ChatService.ConversationItem;
import com.campustrade.module.chat.ChatService.MessagePage;
import com.campustrade.module.chat.ChatService.SendReq;
import com.campustrade.module.chat.ChatService.SendResult;
import com.campustrade.module.chat.ChatService.StartReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/conversations")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

    @PostMapping
    public R<ConversationDetail> start(@Valid @RequestBody StartReq req) {
        return R.ok(chatService.start(CurrentUser.id(), req));
    }

    @GetMapping
    public R<PageResult<ConversationItem>> list(PageQuery pq) {
        return R.ok(chatService.list(CurrentUser.id(), pq));
    }

    @GetMapping("/{id}")
    public R<ConversationDetail> detail(@PathVariable long id) {
        return R.ok(chatService.detail(CurrentUser.id(), id));
    }

    @GetMapping("/{id}/messages")
    public R<MessagePage> messages(@PathVariable long id, @RequestParam(required = false) Long beforeId,
                                   @RequestParam(required = false) Integer size) {
        return R.ok(chatService.messages(CurrentUser.id(), id, beforeId, size));
    }

    @PostMapping("/{id}/messages")
    public R<SendResult> send(@PathVariable long id, @Valid @RequestBody SendReq req) {
        return R.ok(chatService.send(CurrentUser.id(), id, req.type(), req.content(), req.clientMsgId()));
    }

    @PostMapping("/{id}/read")
    public R<Map<String, Integer>> read(@PathVariable long id) {
        return R.ok(Map.of("totalUnread", chatService.markRead(CurrentUser.id(), id)));
    }
}
