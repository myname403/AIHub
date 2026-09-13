package com.aihub.platform.system.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.platform.system.entity.SysMenuDO;
import com.aihub.platform.system.entity.SysRoleDO;
import com.aihub.platform.system.entity.SysRoleMenuDO;
import com.aihub.platform.system.entity.SysUserRoleDO;
import com.aihub.platform.system.mapper.SysMenuMapper;
import com.aihub.platform.system.mapper.SysRoleMapper;
import com.aihub.platform.system.mapper.SysRoleMenuMapper;
import com.aihub.platform.system.mapper.SysUserRoleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限服务 —— RBAC 的"查询大脑"：算出"某用户能干什么"。
 *
 * <p><b>权限解析链路（RBAC 的核心，对照代码逐步看）：</b>
 * <pre>
 *   用户 → sys_user_role → 角色列表 → sys_role_menu → 菜单列表
 *        → 菜单上的 permission 字段集合（如 {system:user:query, system:user:create}）
 * </pre>
 * 一个用户可绑多角色，权限取<b>并集</b>（芋道同款）。
 *
 * <p><b>Caffeine 缓存：</b>权限集合每次请求都要用，逐级查 4 张表太浪费；
 * 缓存 10 分钟，并在角色授权/用户角色变更时主动失效（见各 Service 的 invalidateXxx）。
 * 单机本地缓存的取舍：多实例部署需换 Redis 或改为短 TTL。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    /** 权限集合缓存：key = "perm:{tenantId}:{userId}"，10 分钟过期 + 上限 1 万条 */
    private final Cache<String, Set<String>> permissionCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    /** 菜单列表缓存（构建路由树用，与权限缓存同生命周期） */
    private final Cache<String, List<SysMenuDO>> menuCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();

    /**
     * 查用户的权限标识集合（带缓存）。
     * 空集合表示"没有任何权限"（新角色未授权菜单、或用户没绑角色）。
     */
    public Set<String> getPermissions(Long tenantId, Long userId) {
        String key = "perm:" + tenantId + ":" + userId;
        return permissionCache.get(key, k -> loadPermissions(tenantId, userId));
    }

    /** 判断用户是否拥有某权限（拦截器调用） */
    public boolean hasPermission(Long tenantId, Long userId, String permission) {
        Set<String> permissions = getPermissions(tenantId, userId);
        // "*" 是超级通配符：内置 ROLE_ADMIN 可选配置；当前实现按精确匹配，通配留作扩展
        return permissions.contains(permission);
    }

    /**
     * 查用户可见的菜单列表（M/C 类型，已过滤停用；构建管理端动态路由用）。
     */
    public List<SysMenuDO> getUserMenus(Long tenantId, Long userId) {
        String key = "menu:" + tenantId + ":" + userId;
        return menuCache.get(key, k -> loadMenus(tenantId, userId));
    }

    /* ==================== 缓存失效（授权变更时由各 Service 调用） ==================== */

    /** 某用户的权限变了（分配角色、改状态等） */
    public void invalidateUser(Long tenantId, Long userId) {
        permissionCache.invalidate("perm:" + tenantId + ":" + userId);
        menuCache.invalidate("menu:" + tenantId + ":" + userId);
    }

    /** 某角色的授权变了（角色分配菜单）—— 该角色下所有用户的权限都要失效 */
    public void invalidateRole(Long roleId) {
        // 本地缓存无法反查"哪些用户绑了此角色"，简单起见：按前缀粗粒度清空全部
        // （教学项目单机低频操作，可接受；生产可用 Redis 按角色建反向索引）
        permissionCache.invalidateAll();
        menuCache.invalidateAll();
    }

    /** 菜单本身变了（新增/修改/删除菜单） */
    public void invalidateAllMenus() {
        permissionCache.invalidateAll();
        menuCache.invalidateAll();
    }

    /* ==================== 私有：真实查库逻辑 ==================== */

    /** 逐级查：用户角色 → 有效角色 → 角色菜单 → 菜单 permission 集合 */
    private Set<String> loadPermissions(Long tenantId, Long userId) {
        List<Long> roleIds = activeRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) {
            return Set.of();
        }
        // 角色 → 菜单关联
        List<Long> menuIds = roleMenuMapper.selectList(Wrappers.<SysRoleMenuDO>lambdaQuery()
                        .in(SysRoleMenuDO::getRoleId, roleIds))
                .stream().map(SysRoleMenuDO::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return Set.of();
        }
        // 菜单 → 非空 permission 集合
        return menuMapper.selectBatchIds(menuIds).stream()
                .map(SysMenuDO::getPermission)
                .filter(p -> p != null && !p.isBlank())
                .collect(Collectors.toSet());
    }

    /** 查用户可见菜单（仅 M/C 类型、启用状态；按钮 F 不进路由树，只进权限集合） */
    private List<SysMenuDO> loadMenus(Long tenantId, Long userId) {
        List<Long> roleIds = activeRoleIds(tenantId, userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> menuIds = roleMenuMapper.selectList(Wrappers.<SysRoleMenuDO>lambdaQuery()
                        .in(SysRoleMenuDO::getRoleId, roleIds))
                .stream().map(SysRoleMenuDO::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return menuMapper.selectBatchIds(menuIds).stream()
                .filter(m -> ("M".equals(m.getMenuType()) || "C".equals(m.getMenuType()))
                        && m.getStatus() != null && m.getStatus() == 1)
                .sorted(Comparator.comparing(m -> m.getSort() == null ? 0 : m.getSort()))
                .toList();
    }

    /** 用户 → 启用状态的角色 ID 列表（严格带 tenantId 条件，防跨租户伪造） */
    private List<Long> activeRoleIds(Long tenantId, Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRoleDO>lambdaQuery()
                        .eq(SysUserRoleDO::getTenantId, tenantId)
                        .eq(SysUserRoleDO::getUserId, userId))
                .stream().map(SysUserRoleDO::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        // 过滤掉已停用/非本租户的角色
        return roleMapper.selectList(Wrappers.<SysRoleDO>lambdaQuery()
                        .eq(SysRoleDO::getTenantId, tenantId)
                        .eq(SysRoleDO::getStatus, 1)
                        .in(SysRoleDO::getId, roleIds))
                .stream().map(SysRoleDO::getId).toList();
    }

    /** 供 Service 内部复用：查用户权限（无缓存版本，校验时直接用缓存版即可） */
    public void checkPermission(Long tenantId, Long userId, String permission) {
        if (!hasPermission(tenantId, userId, permission)) {
            throw new BizException(ResultCode.FORBIDDEN, "无权限：" + permission);
        }
    }
}
