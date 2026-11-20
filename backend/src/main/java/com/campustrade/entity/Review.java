package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 交易互评 */
@Data
@TableName(value = "review", autoResultMap = true)
public class Review {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long orderId;

    private Long reviewerId;

    private Long revieweeId;

    /** 评价人身份：1买家评卖家 2卖家评买家 */
    private Integer role;

    /** 1好评 2中评 3差评 */
    private Integer rating;

    private String content;

    /** 如 ["描述相符","准时赴约"] */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> tags;

    /** 被评方追加解释，仅一次 */
    private String reply;

    /** 1 表示到期未评系统默认好评 */
    private Integer isDefault;

    /** 双方都评或到期后才公开 */
    private Integer visible;

    private LocalDateTime createdAt;
}
