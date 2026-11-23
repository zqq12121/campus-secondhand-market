package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 关注卖家：卖家有商品上架时通知粉丝 */
@Data
@TableName("user_follow")
public class UserFollow {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关注发起人 */
    private Long userId;

    /** 被关注的卖家 */
    private Long followeeId;

    private LocalDateTime createdAt;
}
