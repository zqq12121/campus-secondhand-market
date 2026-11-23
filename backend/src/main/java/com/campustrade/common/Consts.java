package com.campustrade.common;

/** 状态码常量，与 schema 字段注释一一对应（接口文档附录 A） */
public final class Consts {

    private Consts() {
    }

    public static final class UserStatus {
        public static final int NORMAL = 0, MUTED = 1, BANNED = 2;
    }

    public static final class GoodsStatus {
        public static final int DRAFT = 0, PENDING = 1, ON_SALE = 2, REJECTED = 3, TRADING = 4,
                SOLD = 5, OFF_SHELF = 6, FORCED_OFF = 7;
    }

    public static final class WantStatus {
        public static final int ACTIVE = 0, SOLVED = 1, EXPIRED = 2, CLOSED_BY_ADMIN = 3;
    }

    public static final class ReportTarget {
        public static final int GOODS = 1, USER = 2, WANT = 3;
    }

    public static final class TradeMode {
        public static final int FACE = 1, GUARANTEE = 2, BOTH = 3;
    }

    public static final class OrderStatus {
        public static final int WAIT_SELLER_CONFIRM = 10, WAIT_MEET = 20, WAIT_PAY = 30, WAIT_DELIVER = 40,
                WAIT_RECEIVE = 50, AFTER_SALE = 60, FINISHED = 70, CANCELED = 80, REFUNDED = 90;
    }

    public static final class Operator {
        public static final int BUYER = 1, SELLER = 2, SYSTEM = 3, ADMIN = 4;
    }

    public static final class CreditReason {
        public static final int TRADE_DONE = 1, GOOD_REVIEW = 2, BAD_REVIEW = 3, DEFAULT_CANCEL = 4,
                DISPUTE_LOST = 5, REPORT_ESTABLISHED = 6, ADMIN_ADJUST = 7, APPEAL_PASSED = 8;
    }

    public static final class NotifyType {
        public static final int ORDER = 1, AUDIT = 2, WANT_MATCH = 3, REPORT = 4, CREDIT = 5, SYSTEM = 6,
                FOLLOW = 7, PRICE_DROP = 8;
    }

    public static final class RefType {
        public static final int ORDER = 1, GOODS = 2, WANT = 3;
    }

    public static final class AuditResult {
        public static final int PASS = 1, REJECT = 2, MANUAL = 3;
    }
}
