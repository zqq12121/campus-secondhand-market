package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 公告与轮播图 */
@Data
@TableName("announcement")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 1公告 2首页轮播图 */
    private Integer type;

    private String title;

    private String content;

    /** 轮播图图片 */
    private String imageUrl;

    private String linkUrl;

    private Integer sort;

    /** 0下线 1上线 */
    private Integer status;

    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
