package com.campustrade.infra;

import com.campustrade.entity.SensitiveWord;
import com.campustrade.mapper.SensitiveWordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SensitiveWordServiceTest {

    private SensitiveWordService service;

    @BeforeEach
    void setUp() {
        SensitiveWordMapper mapper = mock(SensitiveWordMapper.class);
        when(mapper.selectList(null)).thenReturn(Stream.of("代考", "代考试", "VX").map(w -> {
            SensitiveWord sw = new SensitiveWord();
            sw.setWord(w);
            return sw;
        }).toList());
        service = new SensitiveWordService(mapper);
        service.reload();
    }

    @Test
    void longestMatchAndCaseInsensitive() {
        assertThat(service.findHits("专业代考试，加vx")).containsExactly("代考试", "vx");
    }

    @Test
    void hitsAreDeduplicated() {
        assertThat(service.findHits("代考 代考")).isEqualTo(List.of("代考"));
    }

    @Test
    void replaceMasksHits() {
        assertThat(service.replace("接代考，加Vx")).isEqualTo("接***，加***");
        assertThat(service.replace("干净的文本")).isEqualTo("干净的文本");
        assertThat(service.replace(null)).isNull();
    }
}
