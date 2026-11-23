package com.campustrade.module.user;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.campustrade.common.BizException;
import com.campustrade.common.Consts.CreditReason;
import com.campustrade.common.Consts.NotifyType;
import com.campustrade.common.ErrorCode;
import com.campustrade.module.notification.NotificationService;
import com.campustrade.module.user.CreditAppealService.ApplyReq;
import com.campustrade.module.user.CreditAppealService.HandleReq;
import com.campustrade.module.user.entity.CreditAppeal;
import com.campustrade.module.user.entity.CreditLog;
import com.campustrade.module.user.mapper.CreditAppealMapper;
import com.campustrade.module.user.mapper.CreditLogMapper;
import com.campustrade.support.MpTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 信用分申诉：只能申诉 30 天内自己的扣分、每条一次；通过后加回扣分，驳回发通知 */
@ExtendWith(MockitoExtension.class)
class CreditAppealServiceTest {

    private static final long USER = 1024L;

    @Mock
    private CreditAppealMapper appealMapper;
    @Mock
    private CreditLogMapper creditLogMapper;
    @Mock
    private CreditService creditService;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private CreditAppealService service;

    @BeforeAll
    static void initTables() {
        MpTestSupport.initTables(CreditAppeal.class);
    }

    private static CreditLog log(long userId, int delta, int reason, int daysAgo) {
        CreditLog l = new CreditLog();
        l.setId(77L);
        l.setUserId(userId);
        l.setDelta(delta);
        l.setReasonType(reason);
        l.setCreatedAt(LocalDateTime.now().minusDays(daysAgo));
        return l;
    }

    private void expectApply(ErrorCode code) {
        assertThatThrownBy(() -> service.apply(USER, 77L, new ApplyReq("对方临时改了时间"))).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(code);
    }

    @Test
    void appealRecentDeduction() {
        when(creditLogMapper.selectById(77L)).thenReturn(log(USER, -3, CreditReason.DEFAULT_CANCEL, 2));
        CreditAppealService.AppealVO vo = service.apply(USER, 77L, new ApplyReq("  对方临时改了时间  "));
        assertThat(vo.delta()).isEqualTo(-3);
        assertThat(vo.reason()).isEqualTo("对方临时改了时间");
        assertThat(vo.status()).isEqualTo(CreditAppealService.PENDING);
    }

    @Test
    void cannotAppealGainsOthersOrOldLogs() {
        when(creditLogMapper.selectById(77L)).thenReturn(log(USER, 1, CreditReason.TRADE_DONE, 1));
        expectApply(ErrorCode.CREDIT_APPEAL_NOT_ALLOWED);
        when(creditLogMapper.selectById(77L)).thenReturn(log(4096L, -3, CreditReason.DEFAULT_CANCEL, 1));
        expectApply(ErrorCode.CREDIT_APPEAL_NOT_ALLOWED);
        when(creditLogMapper.selectById(77L)).thenReturn(log(USER, -3, CreditReason.DEFAULT_CANCEL, 31));
        expectApply(ErrorCode.CREDIT_APPEAL_NOT_ALLOWED);
        verify(appealMapper, never()).insert(any(CreditAppeal.class));
    }

    @Test
    void onlyOncePerLog() {
        when(creditLogMapper.selectById(77L)).thenReturn(log(USER, -3, CreditReason.DEFAULT_CANCEL, 1));
        doThrow(new DuplicateKeyException("uk_credit_log")).when(appealMapper).insert(any(CreditAppeal.class));
        expectApply(ErrorCode.CREDIT_APPEAL_EXISTS);
    }

    private static CreditAppeal pending() {
        CreditAppeal a = new CreditAppeal();
        a.setId(5L);
        a.setUserId(USER);
        a.setDelta(-5);
        a.setStatus(CreditAppealService.PENDING);
        return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    void approveRestoresDeductedPoints() {
        when(appealMapper.selectById(5L)).thenReturn(pending());
        when(appealMapper.update(any(Wrapper.class))).thenReturn(1);
        service.handle(1L, 5L, new HandleReq(true, "有聊天记录"));
        verify(creditService).change(eq(USER), eq(5), eq(CreditReason.APPEAL_PASSED), eq(5L), anyString(), eq(1L));
        verify(notificationService, never()).send(anyLong(), anyInt(), anyString(), anyString(), any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectNotifiesUser() {
        when(appealMapper.selectById(5L)).thenReturn(pending());
        when(appealMapper.update(any(Wrapper.class))).thenReturn(1);
        service.handle(1L, 5L, new HandleReq(false, "证据不足"));
        verify(creditService, never()).change(anyLong(), anyInt(), anyInt(), any(), any(), any());
        verify(notificationService).send(eq(USER), eq(NotifyType.CREDIT), anyString(), eq("证据不足"), any(), any());
    }

    @Test
    void cannotHandleTwice() {
        CreditAppeal a = pending();
        a.setStatus(CreditAppealService.APPROVED);
        when(appealMapper.selectById(5L)).thenReturn(a);
        assertThatThrownBy(() -> service.handle(1L, 5L, new HandleReq(true, "x"))).isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ErrorCode.CONFLICT);
    }
}
