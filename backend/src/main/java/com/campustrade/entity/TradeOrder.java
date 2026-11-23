package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 交易订单 */
@Data
@TableName("trade_order")
public class TradeOrder {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务订单号 */
    private String orderNo;

    private Long buyerId;

    private Long sellerId;

    private Long goodsId;

    /** 下单时商品快照 */
    private String goodsTitle;

    private String goodsCover;

    private BigDecimal unitPrice;

    private Integer quantity;

    private BigDecimal totalAmount;

    /** 1面交 2担保 */
    private Integer tradeMode;

    /** 10待卖家确认 20待面交 30待支付 40待交货 50待收货 60售后中 70已完成 80已取消 90已退款 */
    private Integer status;

    private String cancelReason;

    /** 1买家 2卖家 3系统超时 4管理员 */
    private Integer cancelBy;

    /** 面交：卖家确认时间 */
    private LocalDateTime sellerConfirmedAt;

    /** 面交核销码：卖家确认时生成，仅买家可见，卖家输入后完成交易 */
    private String meetCode;

    /** 担保：支付时间 */
    private LocalDateTime paidAt;

    /** 担保：卖家交货时间 */
    private LocalDateTime deliveredAt;

    private LocalDateTime finishedAt;

    private LocalDateTime canceledAt;

    /** 互评截止时间 = 完成后15天 */
    private LocalDateTime reviewDeadline;

    /** 乐观锁，防止状态并发流转 */
    @Version
    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
