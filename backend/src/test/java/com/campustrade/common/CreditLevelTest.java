package com.campustrade.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CreditLevelTest {

    @ParameterizedTest
    @CsvSource({"100,EXCELLENT", "90,EXCELLENT", "89,GOOD", "80,GOOD", "79,FAIR", "60,FAIR", "59,POOR", "40,POOR",
            "39,RESTRICTED", "0,RESTRICTED"})
    void boundaries(int score, CreditLevel expected) {
        assertThat(CreditLevel.of(score)).isEqualTo(expected);
    }
}
