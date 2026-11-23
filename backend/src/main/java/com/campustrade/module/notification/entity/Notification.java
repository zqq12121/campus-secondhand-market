package com.campustrade.module.notification.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 站内通知 */
@Data
@TableName("notification")
public class Notification {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 1订单 2审核 3求购匹配 4举报结果 5信用分 6系统公告 */
    private Integer type;

    private String title;

    private String content;

    /** 跳转目标：1订单 2商品 3求购帖 */
    private Integer refType;

    private Long refId;

    private Integer isRead;

    private LocalDateTime createdAt;
}
