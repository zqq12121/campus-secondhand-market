package com.campustrade.module.admin.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 管理员-角色 */
@Data
@TableName("admin_user_role")
public class AdminUserRole {

    private Long adminId;

    private Long roleId;
}
