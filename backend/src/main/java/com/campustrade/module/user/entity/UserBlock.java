package com.campustrade.module.user.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 拉黑关系：被拉黑方不能给发起人发消息和下单 */
@Data
@TableName("user_block")
public class UserBlock {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 拉黑发起人 */
    private Long userId;

    /** 被拉黑的人 */
    private Long blockedUserId;

    private LocalDateTime createdAt;
}
