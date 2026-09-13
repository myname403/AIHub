package com.aihub.platform.system.controller;

import com.aihub.common.result.R;
import com.aihub.platform.system.annotation.RequirePermission;
import com.aihub.platform.system.service.SysRoleService;
import com.aihub.platform.system.vo.SysRoleAssignMenusReqVO;
import com.aihub.platform.system.vo.SysRolePageReqVO;
import com.aihub.platform.system.vo.SysRoleRespVO;
import com.aihub.platform.system.vo.SysRoleSaveReqVO;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 角色管理接口（本租户视角，租户隔离由 Service 层强制）。
 * 入参/出参全部走 vo 包（SysRoleSaveReqVO / SysRoleRespVO）。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@RestController
@RequestMapping("/api/platform/system/role")
@RequiredArgsConstructor
public class RoleController {

    private final SysRoleService roleService;

    /** 分页查询（keyword 模糊匹配名称/编码） */
    @RequirePermission("system:role:query")
    @GetMapping("/page")
    public R<Page<SysRoleRespVO>> page(SysRolePageReqVO pageReqVO) {
        return R.ok(roleService.page(pageReqVO.getPageNo(), pageReqVO.getPageSize(), pageReqVO.getKeyword()));
    }

    /** 全部启用角色（下拉框用） */
    @RequirePermission("system:role:query")
    @GetMapping("/list")
    public R<List<SysRoleRespVO>> list() {
        return R.ok(roleService.listAll());
    }

    @RequirePermission("system:role:create")
    @PostMapping
    public R<Long> create(@Valid @RequestBody SysRoleSaveReqVO reqVO) {
        return R.ok(roleService.create(reqVO));
    }

    @RequirePermission("system:role:update")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SysRoleSaveReqVO reqVO) {
        roleService.update(id, reqVO);
        return R.ok();
    }

    @RequirePermission("system:role:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        roleService.delete(id);
        return R.ok();
    }

    /** 角色已授权的菜单 ID（授权弹窗回显） */
    @RequirePermission("system:role:query")
    @GetMapping("/{id}/menu-ids")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(roleService.getMenuIds(id));
    }

    /** 菜单授权：保存勾选的全部菜单 ID（整批替换） */
    @RequirePermission("system:role:assign-menu")
    @PutMapping("/{id}/assign-menus")
    public R<Void> assignMenus(@PathVariable Long id, @Valid @RequestBody SysRoleAssignMenusReqVO reqVO) {
        roleService.assignMenus(id, reqVO.getMenuIds());
        return R.ok();
    }
}
