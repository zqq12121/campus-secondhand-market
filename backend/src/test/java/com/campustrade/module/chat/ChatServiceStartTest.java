package com.campustrade.module.chat;

import com.campustrade.common.BizException;
import com.campustrade.common.Consts.WantStatus;
import com.campustrade.common.ErrorCode;
import com.campustrade.entity.WantPost;
import com.campustrade.mapper.WantPostMapper;
import com.campustrade.module.chat.ChatService.StartReq;
import com.campustrade.module.user.UserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** 「我有」发起会话时的 wantId 校验 */
@ExtendWith(MockitoExtension.class)
class ChatServiceStartTest {

    @Mock
    private WantPostMapper wantMapper;
    @Mock
    private UserService userService;
    @InjectMocks
    private ChatService service;

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
}
