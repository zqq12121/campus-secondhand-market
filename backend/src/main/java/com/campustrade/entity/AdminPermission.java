package com.campustrade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 权限点 */
@Data
@TableName("admin_permission")
public class AdminPermission {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long parentId;

    /** 如 goods:audit */
    private String code;

    private String name;

    /** 1菜单 2按钮/接口 */
    private Integer type;

    private Integer sort;
}
