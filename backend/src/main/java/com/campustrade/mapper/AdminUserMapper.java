package com.campustrade.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.campustrade.entity.AdminUser;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface AdminUserMapper extends BaseMapper<AdminUser> {

    @Select("""
            SELECT DISTINCT p.code FROM admin_user u
            JOIN admin_user_role ur ON ur.admin_id = u.id
            JOIN admin_role_permission rp ON rp.role_id = ur.role_id
            JOIN admin_permission p ON p.id = rp.permission_id
            WHERE u.id = #{adminId} AND u.status = 1
            """)
    List<String> selectPermissionCodes(@Param("adminId") long adminId);

    @Select("""
            SELECT r.code FROM admin_user_role ur JOIN admin_role r ON r.id = ur.role_id
            WHERE ur.admin_id = #{adminId}
            """)
    List<String> selectRoleCodes(@Param("adminId") long adminId);
}
