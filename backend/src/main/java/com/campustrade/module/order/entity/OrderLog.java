package com.campustrade.module.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 订单状态流转日志 */
@Data
@TableName("order_log")
public class OrderLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    /** 创建时为 NULL */
    private Integer fromStatus;

    private Integer toStatus;

    /** 状态机动作，如 PAY / DELIVER / CONFIRM_RECEIPT */
    private String action;

    /** 1买家 2卖家 3系统 4管理员 */
    private Integer operatorType;

    private Long operatorId;

    private String remark;

    private LocalDateTime createdAt;
}
