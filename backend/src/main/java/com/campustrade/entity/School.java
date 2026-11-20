package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 学校（当前单校部署，预留多校扩展） */
@Data
@TableName("school")
public class School {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 学校名称 */
    private String name;

    /** 校园邮箱后缀，如 @wu.edu.cn */
    private String emailSuffix;

    private LocalDateTime createdAt;
}
