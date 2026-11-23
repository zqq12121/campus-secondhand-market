package com.campustrade.module.chat;

import com.campustrade.infra.OnlineTracker;
import com.campustrade.module.chat.ChatService.ConversationDetail;
import com.campustrade.module.chat.entity.Conversation;
import com.campustrade.module.chat.mapper.ConversationMapper;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 没有关联商品的会话（「我有」、从主页发起）详情不能报错 */
@ExtendWith(MockitoExtension.class)
class ChatServiceDetailTest {

    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private UserService userService;
    @Mock
    private OnlineTracker onlineTracker;
    @InjectMocks
    private ChatService service;

    @Test
    void conversationWithoutGoods() {
        Conversation c = new Conversation();
        c.setId(10L);
        c.setUserSmallId(1L);
        c.setUserBigId(2L);
        when(conversationMapper.selectById(10L)).thenReturn(c);
        User peer = new User();
        peer.setId(2L);
        peer.setNickname("对方");
        peer.setCreditScore(80);
        when(userService.require(2L)).thenReturn(peer);

        ConversationDetail d = service.detail(1L, 10L);

        assertThat(d.goods()).isNull();
        assertThat(d.peer().id()).isEqualTo(2L);
    }
}
