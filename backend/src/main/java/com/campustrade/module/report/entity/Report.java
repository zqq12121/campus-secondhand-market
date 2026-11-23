package com.campustrade.module.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/** 举报 */
@Data
@TableName(value = "report", autoResultMap = true)
public class Report {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long reporterId;

    /** 1商品 2用户 3求购帖 */
    private Integer targetType;

    private Long targetId;

    /** 1禁售品 2虚假信息 3诈骗 4辱骂骚扰 5其他 */
    private Integer reasonType;

    private String description;

    /** 凭证图片URL数组 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> evidenceUrls;

    /** 0待处理 1成立 2不成立 */
    private Integer status;

    /** 处理结果说明 */
    private String result;

    private Long handlerId;

    private LocalDateTime handledAt;

    private LocalDateTime createdAt;
}
