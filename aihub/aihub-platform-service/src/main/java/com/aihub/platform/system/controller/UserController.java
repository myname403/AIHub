package com.aihub.platform.system.controller;

import com.aihub.common.result.R;
import com.aihub.platform.system.annotation.RequirePermission;
import com.aihub.platform.system.service.SysUserService;
import com.aihub.platform.system.vo.SysUserChangePwdReqVO;
import com.aihub.platform.system.vo.SysUserCreateReqVO;
import com.aihub.platform.system.vo.SysUserPageReqVO;
import com.aihub.platform.system.vo.SysUserRespVO;
import com.aihub.platform.system.vo.SysUserResetPwdReqVO;
import com.aihub.platform.system.vo.SysUserUpdateReqVO;
import com.aihub.platform.system.vo.SysUserAssignRolesReqVO;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户管理接口（管理员管理本租户子用户 + 任何登录用户的个人信息）。
 *
 * <p><b>VO 分层示范：</b>入参全部是 vo 包下的 ReqVO（校验注解在 VO 字段上），
 * 出参全部是 RespVO（DO 的 passwordHash 天然不出门）。
 * 管理类接口逐个标注 @RequirePermission；个人信息接口只要求登录态。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@RestController
@RequestMapping("/api/platform/system/user")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService userService;

    /** 分页查询本租户用户 */
    @RequirePermission("system:user:query")
    @GetMapping("/page")
    public R<Page<SysUserRespVO>> page(SysUserPageReqVO pageReqVO) {
        return R.ok(userService.page(pageReqVO.getPageNo(), pageReqVO.getPageSize(), pageReqVO.getKeyword()));
    }

    /** 新增子用户（可同时分配角色） */
    @RequirePermission("system:user:create")
    @PostMapping
    public R<Long> create(@Valid @RequestBody SysUserCreateReqVO reqVO) {
        return R.ok(userService.create(reqVO));
    }

    /** 修改用户（昵称/状态） */
    @RequirePermission("system:user:update")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody SysUserUpdateReqVO reqVO) {
        userService.update(id, reqVO.getNickname(), reqVO.getStatus());
        return R.ok();
    }

    /** 删除用户（逻辑删） */
    @RequirePermission("system:user:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }

    /** 重置密码（管理员操作） */
    @RequirePermission("system:user:reset-pwd")
    @PutMapping("/{id}/reset-password")
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody SysUserResetPwdReqVO reqVO) {
        userService.resetPassword(id, reqVO.getNewPassword());
        return R.ok();
    }

    /** 用户已绑定的角色 ID（分配角色弹窗回显） */
    @RequirePermission("system:user:query")
    @GetMapping("/{id}/role-ids")
    public R<List<Long>> roleIds(@PathVariable Long id) {
        return R.ok(userService.getRoleIds(id));
    }

    /** 分配角色（整批替换） */
    @RequirePermission("system:user:update")
    @PutMapping("/{id}/assign-roles")
    public R<Void> assignRoles(@PathVariable Long id, @Valid @RequestBody SysUserAssignRolesReqVO reqVO) {
        userService.assignRoles(id, reqVO.getRoleIds());
        return R.ok();
    }

    /* ---------------- 个人信息（登录即可，不需要权限标识） ---------------- */

    /** 当前登录用户信息 */
    @GetMapping("/profile")
    public R<SysUserRespVO> profile() {
        return R.ok(userService.profile());
    }

    /** 修改自己的密码（需旧密码验证） */
    @PutMapping("/profile/password")
    public R<Void> changePassword(@Valid @RequestBody SysUserChangePwdReqVO reqVO) {
        userService.changePassword(reqVO.getOldPassword(), reqVO.getNewPassword());
        return R.ok();
    }
}
