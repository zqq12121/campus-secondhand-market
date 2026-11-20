package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 平台余额流水 */
@Data
@TableName("balance_log")
public class BalanceLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变动金额，正入负出 */
    private BigDecimal amount;

    private BigDecimal balanceAfter;

    /** 1担保订单放款 */
    private Integer type;

    private Long orderId;

    private LocalDateTime createdAt;
}
