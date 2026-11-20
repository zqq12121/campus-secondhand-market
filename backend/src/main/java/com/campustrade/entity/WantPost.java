package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 求购帖 */
@Data
@TableName("want_post")
public class WantPost {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long schoolId;

    private Long campusId;

    /** 二级分类ID */
    private Long categoryId;

    private String title;

    private String description;

    private BigDecimal priceMin;

    private BigDecimal priceMax;

    /** 0进行中 1已解决 2已过期 3被管理员关闭 */
    private Integer status;

    /** 有效期 3/7/15/30 天 */
    private LocalDateTime expireAt;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
