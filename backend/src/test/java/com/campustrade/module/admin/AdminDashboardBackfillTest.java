package com.campustrade.module.admin;

import com.campustrade.mapper.StatDailyMapper;
import com.campustrade.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 启动补算：聚合函数在空表上返回一行 NULL */
@ExtendWith(MockitoExtension.class)
class AdminDashboardBackfillTest {

    @Mock
    private StatDailyMapper statMapper;
    @Mock
    private UserMapper userMapper;
    @InjectMocks
    private AdminDashboardService service;

    @Test
    void emptyDatabaseRefreshesTodayOnly() {
        when(statMapper.selectObjs(any())).thenReturn(Arrays.asList((Object) null));
        when(userMapper.selectObjs(any())).thenReturn(Arrays.asList((Object) null));
        service.backfillOnStartup();
        LocalDate today = LocalDate.now();
        verify(statMapper, times(1)).refresh(any(), any());
        verify(statMapper).refresh(today, today.plusDays(1));
    }

    @Test
    void emptyStatTableBackfillsFromFirstUser() {
        when(statMapper.selectObjs(any())).thenReturn(Arrays.asList((Object) null));
        when(userMapper.selectObjs(any())).thenReturn(List.of(LocalDateTime.now().minusDays(2)));
        service.backfillOnStartup();
        verify(statMapper, times(3)).refresh(any(), any());
    }

    @Test
    void resumesFromLastStatDate() {
        when(statMapper.selectObjs(any())).thenReturn(List.of(Date.valueOf(LocalDate.now().minusDays(3))));
        service.backfillOnStartup();
        verify(statMapper, times(4)).refresh(any(), any());
    }
}
