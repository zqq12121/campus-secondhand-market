package com.campustrade.module.chat;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.infra.OnlineTracker;
import com.campustrade.module.chat.ChatService.StartReq;
import com.campustrade.module.chat.entity.Conversation;
import com.campustrade.module.chat.mapper.ConversationMapper;
import com.campustrade.module.user.UserService;
import com.campustrade.module.user.entity.User;
import com.campustrade.module.want.entity.WantPost;
import com.campustrade.module.want.mapper.WantPostMapper;
import com.campustrade.module.want.mapper.WantResponseMapper;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 「我有」发起会话时的 wantId 校验 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStartTest {

    @Mock
    private WantPostMapper wantMapper;
    @Mock
    private UserService userService;
    @Mock
    private WantResponseMapper wantResponseMapper;
    @Mock
    private ConversationMapper conversationMapper;
    @Mock
    private OnlineTracker onlineTracker;
    @InjectMocks
    private ChatService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(Conversation.class);
    }

    private static WantPost want(int status, LocalDateTime expireAt) {
        WantPost w = new WantPost();
        w.setId(701L);
        w.setUserId(2048L);
        w.setStatus(status);
        w.setExpireAt(expireAt);
        return w;
    }

    private void expect(ErrorCode code, StartReq req) {
        assertThatThrownBy(() -> service.start(1024L, req)).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void wantMissing() {
        expect(ErrorCode.WANT_NOT_FOUND, new StartReq(2048L, null, 701L));
    }

    @Test
    void wantNotActiveOrExpired() {
        when(wantMapper.selectById(701L)).thenReturn(want(WantStatus.SOLVED, LocalDateTime.now().plusDays(1)));
        expect(ErrorCode.WANT_NOT_FOUND, new StartReq(2048L, null, 701L));
        when(wantMapper.selectById(701L)).thenReturn(want(WantStatus.ACTIVE, LocalDateTime.now().minusMinutes(1)));
        expect(ErrorCode.WANT_NOT_FOUND, new StartReq(2048L, null, 701L));
    }

    @Test
    void peerMustBeWantOwner() {
        when(wantMapper.selectById(701L)).thenReturn(want(WantStatus.ACTIVE, LocalDateTime.now().plusDays(1)));
        expect(ErrorCode.BAD_REQUEST, new StartReq(9999L, null, 701L));
    }

    @Test
    void notRecordedWhenWantInvalid() {
        when(wantMapper.selectById(701L)).thenReturn(want(WantStatus.ACTIVE, LocalDateTime.now().plusDays(1)));
        expect(ErrorCode.BAD_REQUEST, new StartReq(9999L, null, 701L));
        verify(wantResponseMapper, never()).insertIgnore(anyLong(), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void respondRecordsWantResponse() {
        when(wantMapper.selectById(701L)).thenReturn(want(WantStatus.ACTIVE, LocalDateTime.now().plusDays(1)));
        Conversation c = new Conversation();
        c.setId(10L);
        c.setUserSmallId(1024L);
        c.setUserBigId(2048L);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(c);
        when(conversationMapper.selectById(10L)).thenReturn(c);
        User owner = new User();
        owner.setId(2048L);
        owner.setNickname("求购者");
        owner.setCreditScore(80);
        when(userService.require(2048L)).thenReturn(owner);

        assertThat(service.start(1024L, new StartReq(2048L, null, 701L)).id()).isEqualTo(10L);

        verify(wantResponseMapper).insertIgnore(701L, 1024L);
    }
}
