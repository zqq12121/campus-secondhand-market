package com.campustrade.module.pay.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 支付单（担保交易） */
@Data
@TableName("payment")
public class Payment {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 商户支付单号，传给支付宝 */
    private String outTradeNo;

    /** 支付宝交易号 */
    private String alipayTradeNo;

    private BigDecimal amount;

    /** 0待支付 1支付成功 2已关闭 3已全额退款 */
    private Integer status;

    private LocalDateTime paidAt;

    /** 最近一次异步通知原文，排查用 */
    private String notifyPayload;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
