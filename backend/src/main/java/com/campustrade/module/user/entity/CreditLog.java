package com.campustrade.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 信用分变动流水 */
@Data
@TableName("credit_log")
public class CreditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 变动分值，正加负减 */
    private Integer delta;

    /** 变动后信用分 */
    private Integer scoreAfter;

    /** 1完成交易 2收到好评 3收到差评 4违约取消/爽约 5纠纷败诉 6举报成立 7管理员调整 */
    private Integer reasonType;

    /** 关联业务ID（订单/举报等） */
    private Long refId;

    private String remark;

    /** 管理员调整时的管理员ID */
    private Long operatorId;

    private LocalDateTime createdAt;
}
