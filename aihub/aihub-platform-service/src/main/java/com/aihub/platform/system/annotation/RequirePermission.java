package com.aihub.platform.system.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口权限校验注解 —— 标在 Controller 方法上，声明调用它需要的权限标识。
 *
 * <p><b>用法（对齐芋道的 @PreAuthorize/@SaCheckPermission 思想，自研轻量版）：</b>
 * <pre>{@code
 * @RequirePermission("system:user:create")
 * @PostMapping
 * public R<Long> create(@RequestBody CreateUserRequest request) { ... }
 * }</pre>
 *
 * <p><b>生效机制：</b>PermissionInterceptor 在请求进 Controller 前读取本注解，
 * 从当前登录用户的权限集合（用户 → 角色 → 角色菜单 → 权限标识）里查找，
 * 没有则抛 FORBIDDEN。权限集合有 Caffeine 缓存，授权变更时失效。
 *
 * <p><b>权限标识命名规范（芋道同款）：模块:资源:动作</b>，如
 * system:user:create / system:role:assign-menu / ai:model:query。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Target(ElementType.METHOD)          // 只能标在方法上
@Retention(RetentionPolicy.RUNTIME)  // 运行期可反射读取（拦截器靠这个）
public @interface RequirePermission {

    /** 需要的权限标识，如 "system:user:create" */
    String value();
}
