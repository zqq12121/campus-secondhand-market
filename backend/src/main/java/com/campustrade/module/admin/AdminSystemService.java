package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.campustrade.common.Biz;
import com.campustrade.common.ErrorCode;
import com.campustrade.common.PageQuery;
import com.campustrade.common.PageResult;
import com.campustrade.entity.AdminPermission;
import com.campustrade.entity.AdminRole;
import com.campustrade.entity.AdminRolePermission;
import com.campustrade.entity.AdminUser;
import com.campustrade.entity.AdminUserRole;
import com.campustrade.entity.OperationLog;
import com.campustrade.mapper.AdminPermissionMapper;
import com.campustrade.mapper.AdminRoleMapper;
import com.campustrade.mapper.AdminRolePermissionMapper;
import com.campustrade.mapper.AdminUserMapper;
import com.campustrade.mapper.AdminUserRoleMapper;
import com.campustrade.mapper.OperationLogMapper;
import com.campustrade.module.auth.AuthService;
import com.campustrade.security.TokenStore;
import com.campustrade.security.TokenType;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 系统管理（接口文档 13.11）：管理员、角色与权限、操作日志 */
@Service
@RequiredArgsConstructor
public class AdminSystemService {

    private static final Set<String> BUILT_IN_ROLES = Set.of(AdminInitializer.SUPER_ADMIN, AdminInitializer.ADMIN);
    private static final String SYSTEM_ROLE_PERMISSION = "system:role";

    private final AdminUserMapper adminMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminPermissionMapper permissionMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final OperationLogMapper logMapper;
    private final PasswordEncoder passwordEncoder;
    private final TokenStore tokenStore;

    // ---------------------------------------------------------------- DTO

    public record CreateAdminReq(@NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,32}", message = "用户名为 3–32 位字母、数字或下划线")
                                 String username,
                                 @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank String password,
                                 @Size(max = 32) String realName,
                                 @NotEmpty List<Long> roleIds) {
    }

    public record UpdateAdminReq(@Size(max = 32) String realName, @NotEmpty List<Long> roleIds) {
    }

    public record StatusReq(@NotNull @Min(0) @Max(1) Integer status) {
    }

    /** WRITE_ONLY：操作日志序列化请求参数时不输出明文密码 */
    public record PasswordReq(@JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank String password) {
    }

    public record CreateRoleReq(@NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,31}", message = "编码为 2–32 位大写字母、数字或下划线")
                                String code,
                                @NotBlank @Size(max = 32) String name) {
    }

    public record UpdateRoleReq(@NotBlank @Size(max = 32) String name) {
    }

    public record PermissionsReq(@NotNull List<Long> permissionIds) {
    }

    public record IdResp(Long id) {
    }

    public record RoleBrief(Long id, String name) {
    }

    public record AdminVO(Long id, String username, String realName, Integer status, List<RoleBrief> roles,
                          LocalDateTime lastLoginAt, LocalDateTime createdAt) {
    }

    public record RoleVO(Long id, String code, String name, boolean builtIn, int adminCount) {
    }

    public record PermissionNode(Long id, String code, String name, List<PermissionNode> children) {
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    public static class LogQuery extends PageQuery {
        private Long adminId;
        private String module;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime startTime;
        @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime endTime;
    }

    public record LogVO(Long id, Long adminId, String adminName, String module, String action, Long targetId,
                        String detail, String ip, LocalDateTime createdAt) {
    }

    // ---------------------------------------------------------------- 管理员

    public PageResult<AdminVO> admins(PageQuery pq) {
        Page<AdminUser> page = adminMapper.selectPage(pq.toPage(), new LambdaQueryWrapper<AdminUser>()
                .orderByAsc(AdminUser::getId));
        List<Long> ids = page.getRecords().stream().map(AdminUser::getId).toList();
        Map<Long, List<Long>> roleIdsByAdmin = ids.isEmpty() ? Map.of() : userRoleMapper.selectList(
                        new LambdaQueryWrapper<AdminUserRole>().in(AdminUserRole::getAdminId, ids)).stream()
                .collect(Collectors.groupingBy(AdminUserRole::getAdminId,
                        Collectors.mapping(AdminUserRole::getRoleId, Collectors.toList())));
        Map<Long, AdminRole> roles = roleMapper.selectList(null).stream()
                .collect(Collectors.toMap(AdminRole::getId, Function.identity()));
        return PageResult.of(page, u -> new AdminVO(u.getId(), u.getUsername(), u.getRealName(), u.getStatus(),
                roleIdsByAdmin.getOrDefault(u.getId(), List.of()).stream().map(roles::get).filter(r -> r != null)
                        .map(r -> new RoleBrief(r.getId(), r.getName())).toList(),
                u.getLastLoginAt(), u.getCreatedAt()));
    }

    @Transactional
    public IdResp createAdmin(CreateAdminReq req) {
        String username = req.username().trim();
        Biz.check(!adminMapper.exists(new LambdaQueryWrapper<AdminUser>().eq(AdminUser::getUsername, username)),
                ErrorCode.BAD_REQUEST, "用户名已存在");
        AuthService.checkPassword(req.password());
        checkRoles(req.roleIds());
        AdminUser u = new AdminUser();
        u.setUsername(username);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setRealName(blankToNull(req.realName()));
        u.setStatus(1);
        adminMapper.insert(u);
        bindRoles(u.getId(), req.roleIds());
        return new IdResp(u.getId());
    }

    @Transactional
    public void updateAdmin(long id, UpdateAdminReq req) {
        AdminUser u = requireAdmin(id);
        checkRoles(req.roleIds());
        long superRoleId = superRole().getId();
        if (isSuperAdmin(id) && !req.roleIds().contains(superRoleId) && u.getStatus() == 1) {
            Biz.check(enabledSuperAdminCount() > 1, ErrorCode.ADMIN_SELF_OPERATION, "不能移除最后一个超级管理员");
        }
        adminMapper.update(new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id)
                .set(AdminUser::getRealName, blankToNull(req.realName())));
        userRoleMapper.delete(new LambdaQueryWrapper<AdminUserRole>().eq(AdminUserRole::getAdminId, id));
        bindRoles(id, req.roleIds());
    }

    /** 不能停用自己，也不能停用最后一个启用中的超级管理员；停用后其 token 立即失效 */
    public void setAdminStatus(long operatorId, long id, int status) {
        AdminUser u = requireAdmin(id);
        if (status == 0) {
            Biz.check(operatorId != id, ErrorCode.ADMIN_SELF_OPERATION, "不能停用自己");
            if (u.getStatus() == 1 && isSuperAdmin(id)) {
                Biz.check(enabledSuperAdminCount() > 1, ErrorCode.ADMIN_SELF_OPERATION, "不能停用最后一个超级管理员");
            }
        }
        adminMapper.update(new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id).set(AdminUser::getStatus, status));
        if (status == 0) {
            tokenStore.invalidateAll(TokenType.ADMIN, id);
        }
    }

    /** 重置密码后该管理员已登录的 token 全部失效 */
    public void resetPassword(long id, String password) {
        requireAdmin(id);
        AuthService.checkPassword(password);
        adminMapper.update(new LambdaUpdateWrapper<AdminUser>().eq(AdminUser::getId, id)
                .set(AdminUser::getPasswordHash, passwordEncoder.encode(password)));
        tokenStore.invalidateAll(TokenType.ADMIN, id);
    }

    // ---------------------------------------------------------------- 角色与权限

    public List<RoleVO> roles() {
        Map<Long, Long> counts = userRoleMapper.selectList(null).stream()
                .collect(Collectors.groupingBy(AdminUserRole::getRoleId, Collectors.counting()));
        return roleMapper.selectList(new LambdaQueryWrapper<AdminRole>().orderByAsc(AdminRole::getId)).stream()
                .map(r -> new RoleVO(r.getId(), r.getCode(), r.getName(), BUILT_IN_ROLES.contains(r.getCode()),
                        counts.getOrDefault(r.getId(), 0L).intValue())).toList();
    }

    public IdResp createRole(CreateRoleReq req) {
        Biz.check(!roleMapper.exists(new LambdaQueryWrapper<AdminRole>().eq(AdminRole::getCode, req.code())),
                ErrorCode.BAD_REQUEST, "角色编码已存在");
        AdminRole r = new AdminRole();
        r.setCode(req.code());
        r.setName(req.name().trim());
        roleMapper.insert(r);
        return new IdResp(r.getId());
    }

    public void updateRole(long id, UpdateRoleReq req) {
        requireRole(id);
        roleMapper.update(new LambdaUpdateWrapper<AdminRole>().eq(AdminRole::getId, id)
                .set(AdminRole::getName, req.name().trim()));
    }

    /** 内置角色不可删；有管理员绑定时不可删 */
    @Transactional
    public void deleteRole(long id) {
        AdminRole r = requireRole(id);
        Biz.check(!BUILT_IN_ROLES.contains(r.getCode()), ErrorCode.BAD_REQUEST, "内置角色不能删除");
        Biz.check(!userRoleMapper.exists(new LambdaQueryWrapper<AdminUserRole>().eq(AdminUserRole::getRoleId, id)),
                ErrorCode.BAD_REQUEST, "该角色下还有管理员，不能删除");
        rolePermissionMapper.delete(new LambdaQueryWrapper<AdminRolePermission>().eq(AdminRolePermission::getRoleId, id));
        roleMapper.deleteById(id);
    }

    public List<PermissionNode> permissionTree() {
        List<AdminPermission> all = permissionMapper.selectList(new LambdaQueryWrapper<AdminPermission>()
                .orderByAsc(AdminPermission::getSort, AdminPermission::getId));
        return all.stream().filter(p -> p.getParentId() == 0)
                .map(m -> new PermissionNode(m.getId(), m.getCode(), m.getName(), all.stream()
                        .filter(c -> c.getParentId().equals(m.getId()))
                        .map(c -> new PermissionNode(c.getId(), c.getCode(), c.getName(), List.of())).toList()))
                .toList();
    }

    public List<Long> rolePermissions(long roleId) {
        requireRole(roleId);
        return rolePermissionMapper.selectList(new LambdaQueryWrapper<AdminRolePermission>()
                .eq(AdminRolePermission::getRoleId, roleId)).stream().map(AdminRolePermission::getPermissionId)
                .sorted().toList();
    }

    /**
     * 全量覆盖角色权限。超级管理员固定拥有全部权限，不可修改；system:role 只能授予超级管理员。
     * 勾选了子权限时自动带上所属菜单，没有任何子权限的菜单自动去掉（菜单显示依赖菜单权限码）。
     */
    @Transactional
    public void setRolePermissions(long roleId, List<Long> permissionIds) {
        AdminRole role = requireRole(roleId);
        Biz.check(!AdminInitializer.SUPER_ADMIN.equals(role.getCode()), ErrorCode.BAD_REQUEST, "超级管理员固定拥有全部权限");
        Map<Long, AdminPermission> all = permissionMapper.selectList(null).stream()
                .collect(Collectors.toMap(AdminPermission::getId, Function.identity()));
        Set<Long> granted = new HashSet<>();
        for (Long id : new HashSet<>(permissionIds)) {
            AdminPermission p = all.get(id);
            Biz.check(p != null, ErrorCode.BAD_REQUEST, "权限不存在：" + id);
            Biz.check(!SYSTEM_ROLE_PERMISSION.equals(p.getCode()), ErrorCode.BAD_REQUEST, "角色与权限只能授予超级管理员");
            if (p.getParentId() != 0) {
                granted.add(p.getId());
                granted.add(p.getParentId());
            }
        }
        rolePermissionMapper.delete(new LambdaQueryWrapper<AdminRolePermission>().eq(AdminRolePermission::getRoleId, roleId));
        granted.stream().sorted(Comparator.naturalOrder()).forEach(pid -> {
            AdminRolePermission rp = new AdminRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(pid);
            rolePermissionMapper.insert(rp);
        });
    }

    // ---------------------------------------------------------------- 操作日志

    public PageResult<LogVO> logs(LogQuery q) {
        String module = q.getModule() == null || q.getModule().isBlank() ? null : q.getModule().trim();
        Page<OperationLog> page = logMapper.selectPage(q.toPage(), new LambdaQueryWrapper<OperationLog>()
                .eq(q.getAdminId() != null, OperationLog::getAdminId, q.getAdminId())
                .eq(module != null, OperationLog::getModule, module)
                .ge(q.getStartTime() != null, OperationLog::getCreatedAt, q.getStartTime())
                .le(q.getEndTime() != null, OperationLog::getCreatedAt, q.getEndTime())
                .orderByDesc(OperationLog::getId));
        List<Long> adminIds = page.getRecords().stream().map(OperationLog::getAdminId).distinct().toList();
        Map<Long, AdminUser> admins = adminIds.isEmpty() ? Map.of() : adminMapper.selectByIds(adminIds).stream()
                .collect(Collectors.toMap(AdminUser::getId, Function.identity()));
        return PageResult.of(page, l -> {
            AdminUser a = admins.get(l.getAdminId());
            String name = a == null ? null : a.getRealName() != null ? a.getRealName() : a.getUsername();
            return new LogVO(l.getId(), l.getAdminId(), name, l.getModule(), l.getAction(), l.getTargetId(),
                    l.getDetail(), l.getIp(), l.getCreatedAt());
        });
    }

    // ---------------------------------------------------------------- 内部

    private AdminUser requireAdmin(long id) {
        return Biz.notNull(adminMapper.selectById(id), ErrorCode.NOT_FOUND);
    }

    private AdminRole requireRole(long id) {
        return Biz.notNull(roleMapper.selectById(id), ErrorCode.NOT_FOUND);
    }

    private AdminRole superRole() {
        return roleMapper.selectOne(new LambdaQueryWrapper<AdminRole>().eq(AdminRole::getCode, AdminInitializer.SUPER_ADMIN));
    }

    private boolean isSuperAdmin(long adminId) {
        return userRoleMapper.exists(new LambdaQueryWrapper<AdminUserRole>().eq(AdminUserRole::getAdminId, adminId)
                .eq(AdminUserRole::getRoleId, superRole().getId()));
    }

    private long enabledSuperAdminCount() {
        List<Long> ids = userRoleMapper.selectList(new LambdaQueryWrapper<AdminUserRole>()
                .eq(AdminUserRole::getRoleId, superRole().getId())).stream().map(AdminUserRole::getAdminId).toList();
        return ids.isEmpty() ? 0 : adminMapper.selectCount(new LambdaQueryWrapper<AdminUser>()
                .in(AdminUser::getId, ids).eq(AdminUser::getStatus, 1));
    }

    private void checkRoles(List<Long> roleIds) {
        Set<Long> uniq = new HashSet<>(roleIds);
        Biz.check(roleMapper.selectCount(new LambdaQueryWrapper<AdminRole>().in(AdminRole::getId, uniq)) == uniq.size(),
                ErrorCode.BAD_REQUEST, "角色不存在");
    }

    private void bindRoles(long adminId, List<Long> roleIds) {
        for (Long roleId : new HashSet<>(roleIds)) {
            AdminUserRole ur = new AdminUserRole();
            ur.setAdminId(adminId);
            ur.setRoleId(roleId);
            userRoleMapper.insert(ur);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
