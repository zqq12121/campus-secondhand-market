package com.campustrade.module.want.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 求购响应：同一用户对同一求购只记一次 */
@Data
@TableName("want_response")
public class WantResponse {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long wantId;

    /** 点「我有」的用户 */
    private Long responderId;

    private LocalDateTime createdAt;
}
