package com.campustrade.module.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 校区 */
@Data
@TableName("campus")
public class Campus {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long schoolId;

    /** 校区名称 */
    private String name;

    private Integer sort;

    private LocalDateTime createdAt;
}
