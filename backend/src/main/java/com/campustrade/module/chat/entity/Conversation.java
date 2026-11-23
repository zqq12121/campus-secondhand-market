package com.campustrade.module.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 会话 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 两人中ID较小者，保证一对用户只有一个会话 */
    private Long userSmallId;

    /** 两人中ID较大者 */
    private Long userBigId;

    /** 最近一次关联的商品，会话顶部展示 */
    private Long goodsId;

    /** 最后一条消息摘要 */
    private String lastMessage;

    private LocalDateTime lastMessageAt;

    /** user_small 的未读数 */
    private Integer smallUnread;

    /** user_big 的未读数 */
    private Integer bigUnread;

    private LocalDateTime createdAt;
}
