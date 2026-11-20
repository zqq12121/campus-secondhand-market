package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 聊天消息（按 id 倒序分页拉历史） */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 系统消息为 0 */
    private Long senderId;

    /** 1文字 2图片 3商品卡片 4系统消息 */
    private Integer type;

    /** 文字内容/图片URL/商品ID，已做敏感词替换 */
    private String content;

    private LocalDateTime createdAt;
}
