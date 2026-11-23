package com.campustrade.module.admin.content;

import com.campustrade.common.BizException;
import com.campustrade.infra.SensitiveWordSync;
import com.campustrade.module.admin.content.entity.SensitiveWord;
import com.campustrade.module.admin.content.mapper.SensitiveWordMapper;
import com.campustrade.module.common.mapper.AnnouncementMapper;
import com.campustrade.module.notification.mapper.NotificationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdminContentServiceWordsTest {

    private SensitiveWordMapper wordMapper;
    private SensitiveWordSync wordSync;
    private AdminContentService service;

    @BeforeEach
    void setUp() {
        wordMapper = mock(SensitiveWordMapper.class);
        wordSync = mock(SensitiveWordSync.class);
        service = new AdminContentService(mock(AnnouncementMapper.class), mock(NotificationMapper.class), wordMapper, wordSync);
    }

    @Test
    void trimsDedupesAndSkipsExisting() {
        when(wordMapper.insert(argThat((SensitiveWord w) -> w != null && "已有".equals(w.getWord()))))
                .thenThrow(new DuplicateKeyException("dup"));
        AdminContentService.AddWordsResp r = service.addWords(Arrays.asList(" 代考 ", "代考", "已有", "", null, "刷单"));
        assertThat(r.added()).isEqualTo(2);
        assertThat(r.skipped()).isEqualTo(4);
        verify(wordMapper, times(3)).insert(argThat((SensitiveWord w) -> w != null));
        verify(wordSync).publishReload();
    }

    @Test
    void nothingAddedDoesNotReload() {
        when(wordMapper.insert(argThat((SensitiveWord w) -> w != null))).thenThrow(new DuplicateKeyException("dup"));
        assertThat(service.addWords(List.of("已有")).added()).isZero();
        verify(wordSync, never()).publishReload();
    }

    @Test
    void rejectsBlankAndTooLong() {
        assertThatThrownBy(() -> service.addWords(List.of(" ", ""))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.addWords(List.of("x".repeat(33)))).isInstanceOf(BizException.class);
    }
}
