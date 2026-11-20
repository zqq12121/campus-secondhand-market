package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品 */
@Data
@TableName("goods")
public class Goods {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sellerId;

    private Long schoolId;

    /** 交易校区 */
    private Long campusId;

    /** 二级分类ID */
    private Long categoryId;

    private String title;

    private String description;

    /** 封面图，冗余首张图片 */
    private String coverUrl;

    /** 0 表示免费送 */
    private BigDecimal price;

    private BigDecimal originalPrice;

    /** 1全新 2几乎全新 3轻微使用 4明显使用 */
    private Integer conditionLevel;

    /** 1仅面交 2仅担保 3都支持 */
    private Integer tradeMode;

    /** 面交地点描述 */
    private String location;

    /** 可售库存 */
    private Integer stock;

    /** 0草稿 1待审核 2在售 3审核驳回 4交易中 5已售出 6已下架 7强制下架 */
    private Integer status;

    /** 驳回/强制下架原因 */
    private String auditRemark;

    private Integer viewCount;

    private Integer favoriteCount;

    /** 最近一次上架时间，在售超60天自动下架以此计算 */
    private LocalDateTime publishedAt;

    /** 乐观锁，扣库存使用 */
    @Version
    private Integer version;

    @TableLogic
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
