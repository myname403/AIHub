package com.aihub.platform.system.controller;

import com.aihub.common.result.R;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.system.service.PermissionService;
import com.aihub.platform.system.service.SysMenuService;
import com.aihub.platform.system.vo.MenuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 权限信息接口 —— 管理端登录后的第一个调用：拿"我的菜单路由树 + 按钮权限集合"。
 *
 * <p><b>管理端动态路由的原理：</b>前端骨架启动时只注册登录页等基础路由，
 * 登录成功后调 GET /routes 拿到当前用户可见的菜单树，
 * 用 component 字符串映射到本地 views 目录的组件，动态 addRoute ——
 * 这就是"不同角色登录看到不同菜单"的实现机制（芋道同款）。
 *
 * <p>本 Controller 只要求登录态（每个登录用户都要查自己的权限），
 * 不标注 @RequirePermission。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@RestController
@RequestMapping("/api/platform/system/permission")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionService permissionService;
    private final SysMenuService menuService;

    /**
     * 当前用户的菜单路由树（只含 M/C 类型；前端转换成 vue-router 路由）。
     * 树结构直接复用菜单管理的 MenuVO，前端按需取字段。
     */
    @GetMapping("/routes")
    public R<List<MenuVO>> routes() {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.requireUserId();
        List<MenuVO> tree =
                menuService.buildUserMenuTree(permissionService.getUserMenus(tenantId, userId));
        return R.ok(tree);
    }

    /**
     * 当前用户的按钮权限标识集合（如 ["system:user:create","ai:model:update"]）。
     * 前端存全局，v-permission 指令/函数按它控制按钮显隐。
     */
    @GetMapping("/codes")
    public R<Set<String>> codes() {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.requireUserId();
        return R.ok(permissionService.getPermissions(tenantId, userId));
    }

    /** 一次请求同时拿路由树 + 权限码 + 用户信息（管理端登录后一次拉齐，省两趟） */
    @GetMapping("/info")
    public R<Map<String, Object>> info() {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.requireUserId();
        Map<String, Object> result = new HashMap<>();
        result.put("routes", menuService.buildUserMenuTree(
                permissionService.getUserMenus(tenantId, userId)));
        result.put("permissions", permissionService.getPermissions(tenantId, userId));
        return R.ok(result);
    }
}
