package com.campustrade.module.admin.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;


/** 角色-权限 */
@Data
@TableName("admin_role_permission")
public class AdminRolePermission {

    private Long roleId;

    private Long permissionId;
}
