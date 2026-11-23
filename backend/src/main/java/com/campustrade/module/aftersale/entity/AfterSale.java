package com.campustrade.module.aftersale.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 售后（退款/纠纷） */
@Data
@TableName(value = "after_sale", autoResultMap = true)
public class AfterSale {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 1交货前取消退款 2收货前申请退款 */
    private Integer type;

    private Long buyerId;

    private Long sellerId;

    private String reason;

    /** 凭证图片URL数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> evidenceUrls;

    private BigDecimal refundAmount;

    /** 0待卖家处理 1卖家同意 2卖家拒绝 3平台介入中 4裁决退款 5裁决放款 6买家撤销 */
    private Integer status;

    private String sellerReply;

    /** 买家申请平台介入时的补充说明 */
    private String appealRemark;

    /** 卖家处理截止时间，超时自动同意 */
    private LocalDateTime sellerDeadline;

    /** 裁决管理员 */
    private Long arbitratorId;

    private String arbitrationReason;

    /** 支付宝退款请求号，退款幂等 */
    private String outRefundNo;

    private LocalDateTime refundedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
