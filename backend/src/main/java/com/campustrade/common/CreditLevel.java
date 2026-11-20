package com.campustrade.common;

/** 信用等级（PRD 第 7 章） */
public enum CreditLevel {
    EXCELLENT, GOOD, FAIR, POOR, RESTRICTED;

    public static CreditLevel of(int score) {
        if (score >= 90) {
            return EXCELLENT;
        }
        if (score >= 80) {
            return GOOD;
        }
        if (score >= 60) {
            return FAIR;
        }
        if (score >= 40) {
            return POOR;
        }
        return RESTRICTED;
    }
}
