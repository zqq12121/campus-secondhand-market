package com.campustrade.module.admin.system;

import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.common.R;
import com.campustrade.module.admin.OpLog;
import com.campustrade.module.admin.system.AdminSystemService.AdminVO;
import com.campustrade.module.admin.system.AdminSystemService.CreateAdminReq;
import com.campustrade.module.admin.system.AdminSystemService.CreateRoleReq;
import com.campustrade.module.admin.system.AdminSystemService.IdResp;
import com.campustrade.module.admin.system.AdminSystemService.LogQuery;
import com.campustrade.module.admin.system.AdminSystemService.LogVO;
import com.campustrade.module.admin.system.AdminSystemService.PasswordReq;
import com.campustrade.module.admin.system.AdminSystemService.PermissionNode;
import com.campustrade.module.admin.system.AdminSystemService.PermissionsReq;
import com.campustrade.module.admin.system.AdminSystemService.RoleVO;
import com.campustrade.module.admin.system.AdminSystemService.StatusReq;
import com.campustrade.module.admin.system.AdminSystemService.UpdateAdminReq;
import com.campustrade.module.admin.system.AdminSystemService.UpdateRoleReq;
import com.campustrade.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 系统管理（接口文档 13.11） */
@RestController
@RequestMapping("/api/admin/v1")
@RequiredArgsConstructor
public class AdminSystemController {

    private final AdminSystemService service;

    // ---------------------------------------------------------------- 管理员

    @GetMapping("/admins")
    @PreAuthorize("hasAuthority('system:admin')")
    public R<PageResult<AdminVO>> admins(PageQuery pq) {
        return R.ok(service.admins(pq));
    }

    @PostMapping("/admins")
    @PreAuthorize("hasAuthority('system:admin')")
    @OpLog(module = "系统管理", action = "新增管理员")
    public R<IdResp> createAdmin(@Valid @RequestBody CreateAdminReq req) {
        return R.ok(service.createAdmin(req));
    }

    @PutMapping("/admins/{id}")
    @PreAuthorize("hasAuthority('system:admin')")
    @OpLog(module = "系统管理", action = "修改管理员")
    public R<Void> updateAdmin(@PathVariable long id, @Valid @RequestBody UpdateAdminReq req) {
        service.updateAdmin(id, req);
        return R.ok();
    }

    @PutMapping("/admins/{id}/status")
    @PreAuthorize("hasAuthority('system:admin')")
    @OpLog(module = "系统管理", action = "启停管理员")
    public R<Void> setAdminStatus(@PathVariable long id, @Valid @RequestBody StatusReq req) {
        service.setAdminStatus(CurrentUser.id(), id, req.status());
        return R.ok();
    }

    @PutMapping("/admins/{id}/password")
    @PreAuthorize("hasAuthority('system:admin')")
    @OpLog(module = "系统管理", action = "重置管理员密码")
    public R<Void> resetPassword(@PathVariable long id, @Valid @RequestBody PasswordReq req) {
        service.resetPassword(id, req.password());
        return R.ok();
    }

    // ---------------------------------------------------------------- 角色与权限

    @GetMapping("/roles")
    @PreAuthorize("hasAuthority('system:role')")
    public R<List<RoleVO>> roles() {
        return R.ok(service.roles());
    }

    @PostMapping("/roles")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "系统管理", action = "新增角色")
    public R<IdResp> createRole(@Valid @RequestBody CreateRoleReq req) {
        return R.ok(service.createRole(req));
    }

    @PutMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "系统管理", action = "修改角色")
    public R<Void> updateRole(@PathVariable long id, @Valid @RequestBody UpdateRoleReq req) {
        service.updateRole(id, req);
        return R.ok();
    }

    @DeleteMapping("/roles/{id}")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "系统管理", action = "删除角色")
    public R<Void> deleteRole(@PathVariable long id) {
        service.deleteRole(id);
        return R.ok();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('system:role')")
    public R<List<PermissionNode>> permissions() {
        return R.ok(service.permissionTree());
    }

    @GetMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role')")
    public R<List<Long>> rolePermissions(@PathVariable long id) {
        return R.ok(service.rolePermissions(id));
    }

    @PutMapping("/roles/{id}/permissions")
    @PreAuthorize("hasAuthority('system:role')")
    @OpLog(module = "系统管理", action = "设置角色权限")
    public R<Void> setRolePermissions(@PathVariable long id, @Valid @RequestBody PermissionsReq req) {
        service.setRolePermissions(id, req.permissionIds());
        return R.ok();
    }

    // ---------------------------------------------------------------- 操作日志

    @GetMapping("/operation-logs")
    @PreAuthorize("hasAuthority('system:log')")
    public R<PageResult<LogVO>> logs(LogQuery q) {
        return R.ok(service.logs(q));
    }
}
