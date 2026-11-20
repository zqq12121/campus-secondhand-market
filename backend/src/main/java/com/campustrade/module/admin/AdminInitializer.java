package com.campustrade.module.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.campustrade.config.AppProperties;
import com.campustrade.entity.AdminPermission;
import com.campustrade.entity.AdminRole;
import com.campustrade.entity.AdminRolePermission;
import com.campustrade.entity.AdminUser;
import com.campustrade.entity.AdminUserRole;
import com.campustrade.mapper.AdminPermissionMapper;
import com.campustrade.mapper.AdminRoleMapper;
import com.campustrade.mapper.AdminRolePermissionMapper;
import com.campustrade.mapper.AdminUserMapper;
import com.campustrade.mapper.AdminUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时补齐权限点与内置角色授权；若库中没有管理员，按环境变量
 * ADMIN_INIT_USERNAME / ADMIN_INIT_PASSWORD 创建超级管理员。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminInitializer implements ApplicationRunner {

    public static final String SUPER_ADMIN = "SUPER_ADMIN";
    public static final String ADMIN = "ADMIN";
    private static final int TYPE_MENU = 1;
    private static final int TYPE_API = 2;

    /** 菜单 → [菜单名, 子权限码=名称...]，与接口文档 13.0 一致 */
    static final Map<String, String[]> MENUS = new LinkedHashMap<>();

    static {
        MENUS.put("dashboard", new String[]{"数据看板", "dashboard:view=数据看板"});
        MENUS.put("goods", new String[]{"商品", "goods:audit=商品审核", "goods:manage=商品管理"});
        MENUS.put("user", new String[]{"用户", "user:view=用户查看", "user:punish=禁言/封禁/解封", "user:credit=调整信用分"});
        MENUS.put("trade", new String[]{"交易", "order:view=订单查询", "dispute:handle=纠纷裁决", "report:handle=举报处理"});
        MENUS.put("content", new String[]{"运营", "category:manage=分类管理", "content:manage=公告/轮播/敏感词"});
        MENUS.put("system", new String[]{"系统", "system:admin=管理员账号", "system:role=角色与权限", "system:log=操作日志"});
    }

    private final AdminPermissionMapper permissionMapper;
    private final AdminRoleMapper roleMapper;
    private final AdminRolePermissionMapper rolePermissionMapper;
    private final AdminUserMapper adminUserMapper;
    private final AdminUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties props;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean fresh = permissionMapper.selectCount(null) == 0;
        int sort = 0;
        for (Map.Entry<String, String[]> e : MENUS.entrySet()) {
            AdminPermission menu = ensurePermission(0L, e.getKey(), e.getValue()[0], TYPE_MENU, sort++);
            for (int i = 1; i < e.getValue().length; i++) {
                String[] kv = e.getValue()[i].split("=", 2);
                ensurePermission(menu.getId(), kv[0], kv[1], TYPE_API, i);
            }
        }
        AdminRole superRole = role(SUPER_ADMIN);
        AdminRole adminRole = role(ADMIN);
        for (AdminPermission p : permissionMapper.selectList(null)) {
            grant(superRole.getId(), p.getId());
            // 普通管理员默认授予除"系统"菜单外的全部权限，仅首次初始化时授予，之后由超级管理员调整
            if (fresh && !p.getCode().startsWith("system")) {
                grant(adminRole.getId(), p.getId());
            }
        }
        // system:role 固定只授予超级管理员
        AdminPermission rolePerm = permissionMapper.selectOne(new LambdaQueryWrapper<AdminPermission>()
                .eq(AdminPermission::getCode, "system:role"));
        rolePermissionMapper.delete(new LambdaQueryWrapper<AdminRolePermission>()
                .ne(AdminRolePermission::getRoleId, superRole.getId())
                .eq(AdminRolePermission::getPermissionId, rolePerm.getId()));
        initAdmin(superRole);
    }

    private void initAdmin(AdminRole superRole) {
        if (adminUserMapper.selectCount(null) > 0) {
            return;
        }
        AppProperties.AdminInit init = props.adminInit();
        if (init.username() == null || init.username().isBlank() || init.password() == null
                || init.password().length() < 8) {
            log.warn("库中没有管理员，且未设置 ADMIN_INIT_USERNAME / ADMIN_INIT_PASSWORD（≥8 位），跳过创建");
            return;
        }
        AdminUser u = new AdminUser();
        u.setUsername(init.username().trim());
        u.setPasswordHash(passwordEncoder.encode(init.password()));
        u.setRealName("超级管理员");
        u.setStatus(1);
        adminUserMapper.insert(u);
        AdminUserRole ur = new AdminUserRole();
        ur.setAdminId(u.getId());
        ur.setRoleId(superRole.getId());
        userRoleMapper.insert(ur);
        log.info("已创建初始超级管理员 {}", u.getUsername());
    }

    private AdminPermission ensurePermission(long parentId, String code, String name, int type, int sort) {
        AdminPermission p = permissionMapper.selectOne(new LambdaQueryWrapper<AdminPermission>()
                .eq(AdminPermission::getCode, code));
        if (p == null) {
            p = new AdminPermission();
            p.setParentId(parentId);
            p.setCode(code);
            p.setName(name);
            p.setType(type);
            p.setSort(sort);
            permissionMapper.insert(p);
        }
        return p;
    }

    private AdminRole role(String code) {
        AdminRole r = roleMapper.selectOne(new LambdaQueryWrapper<AdminRole>().eq(AdminRole::getCode, code));
        if (r == null) {
            r = new AdminRole();
            r.setCode(code);
            r.setName(SUPER_ADMIN.equals(code) ? "超级管理员" : "普通管理员");
            roleMapper.insert(r);
        }
        return r;
    }

    private void grant(long roleId, long permissionId) {
        boolean exists = rolePermissionMapper.exists(new LambdaQueryWrapper<AdminRolePermission>()
                .eq(AdminRolePermission::getRoleId, roleId).eq(AdminRolePermission::getPermissionId, permissionId));
        if (!exists) {
            AdminRolePermission rp = new AdminRolePermission();
            rp.setRoleId(roleId);
            rp.setPermissionId(permissionId);
            rolePermissionMapper.insert(rp);
        }
    }
}
