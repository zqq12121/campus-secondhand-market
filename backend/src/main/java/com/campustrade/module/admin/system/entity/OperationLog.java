package com.campustrade.module.admin.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 后台操作日志 */
@Data
@TableName("operation_log")
public class OperationLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long adminId;

    /** 如 商品审核 / 用户管理 */
    private String module;

    private String action;

    private Long targetId;

    /** 请求参数摘要 */
    private String detail;

    private String ip;

    private LocalDateTime createdAt;
}
