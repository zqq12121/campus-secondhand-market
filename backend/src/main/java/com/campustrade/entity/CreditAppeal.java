package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 信用分申诉 */
@Data
@TableName("credit_appeal")
public class CreditAppeal {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 被申诉的扣分流水，每条只能申诉一次 */
    private Long creditLogId;

    /** 被扣分值（负数），通过时按其绝对值加回 */
    private Integer delta;

    private String reason;

    /** 0待处理 1通过 2驳回 */
    private Integer status;

    /** 处理的管理员 */
    private Long handlerId;

    /** 处理说明 */
    private String reply;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
}
