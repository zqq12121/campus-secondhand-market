package com.campustrade.common;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** 错误码，与《接口文档》附录 B 一致 */
@Getter
public enum ErrorCode {
    BAD_REQUEST(400, "参数校验失败"),
    UNAUTHORIZED(401, "未登录或登录已失效", HttpStatus.UNAUTHORIZED),
    FORBIDDEN(403, "无权限", HttpStatus.FORBIDDEN),
    NOT_FOUND(404, "资源不存在"),
    CONFLICT(409, "状态已变更，请刷新"),
    TOO_MANY_REQUESTS(429, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),
    INTERNAL(500, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR),

    EMAIL_SUFFIX_INVALID(1001, "仅支持校园邮箱"),
    EMAIL_REGISTERED(1002, "邮箱已注册"),
    CODE_INVALID(1003, "验证码错误或已过期"),
    CODE_TOO_FREQUENT(1004, "验证码发送过于频繁"),
    BAD_CREDENTIALS(1005, "邮箱或密码错误"),
    ACCOUNT_LOCKED(1006, "登录失败次数过多，账号已锁定"),
    PASSWORD_FORMAT(1007, "密码须为 8–20 位且包含字母和数字"),
    EMAIL_NOT_REGISTERED(1008, "邮箱未注册"),
    ACCOUNT_BANNED(1009, "账号已封禁"),
    ACCOUNT_MUTED(1010, "账号已禁言"),
    CREDIT_RESTRICTED(1011, "信用分不足，功能受限"),
    CREDIT_APPEAL_NOT_ALLOWED(1012, "只能申诉 30 天内自己的扣分记录"),
    CREDIT_APPEAL_EXISTS(1013, "这条记录已经申诉过"),
    CAPTCHA_INVALID(1014, "图形验证码错误或已过期"),
    CAPTCHA_REQUIRED(1015, "请输入图形验证码"),
    FILE_TYPE_NOT_ALLOWED(1101, "文件类型不允许"),
    FILE_TOO_LARGE(1102, "文件超过 5MB"),

    GOODS_NOT_FOUND(2001, "商品不存在"),
    ON_SALE_LIMIT(2002, "同时在售商品已达上限"),
    GOODS_STATE_INVALID(2003, "当前状态不可执行此操作"),
    DAILY_PUBLISH_LIMIT(2004, "今日发布数量已达上限"),
    PRICE_INVALID(2005, "价格不合法"),
    FREE_MUST_FACE(2006, "免费送商品只能选择面交"),
    CATEGORY_INVALID(2007, "分类不存在或已停用"),
    GUARANTEE_CREDIT_LOW(2008, "信用分低于 60 不能使用担保交易"),
    FAVORITE_SELF(2009, "不能收藏自己的商品"),
    GOODS_HAS_ORDER(2010, "商品有进行中的订单"),

    WANT_LIMIT(3001, "进行中的求购已达上限"),
    WANT_NOT_FOUND(3002, "求购帖不存在或已结束"),

    NOT_CONVERSATION_MEMBER(4001, "不是会话成员"),
    BLOCKED_BY_PEER(4002, "对方已将你拉黑"),
    MUTED(4003, "你已被禁言"),

    GOODS_NOT_BUYABLE(5001, "商品当前不可购买"),
    STOCK_NOT_ENOUGH(5002, "库存不足"),
    BUY_SELF(5003, "不能购买自己的商品"),
    BLOCKED_BY_SELLER(5004, "卖家已将你拉黑"),
    CANCEL_TOO_MANY(5005, "今日取消订单次数过多，明天再试"),
    TRADE_MODE_UNSUPPORTED(5006, "商品不支持该交易方式"),
    ORDER_STATE_INVALID(5007, "当前订单状态不允许此操作"),
    MEET_CODE_WRONG(5008, "核销码错误"),
    MEET_CODE_LOCKED(5009, "核销码错误次数过多，请让买家在订单页确认完成"),

    REVIEW_NOT_ALLOWED(6001, "当前不可评价"),
    REVIEW_DUPLICATED(6002, "已评价过"),
    REPLY_DUPLICATED(6003, "已追加过解释"),

    REPORT_DUPLICATED(7001, "24 小时内已举报过该对象"),
    REPORT_SELF(7002, "不能举报自己"),

    CATEGORY_IN_USE(8001, "分类下有商品或子分类，只能停用"),
    AFTER_SALE_EXISTS(9001, "该订单已有处理中的售后"),
    AFTER_SALE_STATE_INVALID(9002, "售后状态不允许此操作"),
    REFUND_FAILED(9003, "退款失败，系统将自动重试"),
    ADMIN_SELF_OPERATION(9101, "不能停用自己或删除最后一个超级管理员");

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message) {
        this(code, message, HttpStatus.OK);
    }

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
