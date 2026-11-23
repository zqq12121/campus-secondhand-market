package com.campustrade.module.goods.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 审核记录 */
@Data
@TableName("audit_record")
public class AuditRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1商品 */
    private Integer targetType;

    private Long targetId;

    /** 1系统自动 2管理员 */
    private Integer auditorType;

    private Long auditorId;

    /** 1通过 2驳回 3转人工 */
    private Integer result;

    private String reason;

    /** 命中的敏感词 */
    private String hitWords;

    private LocalDateTime createdAt;
}
