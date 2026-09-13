package com.aihub.platform.system.controller;

import com.aihub.common.result.R;
import com.aihub.platform.system.annotation.RequirePermission;
import com.aihub.platform.system.service.SysMenuService;
import com.aihub.platform.system.vo.MenuVO;
import com.aihub.platform.system.vo.SysMenuSaveReqVO;
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
 * 菜单管理接口（平台全局数据，通常只有租户 ADMIN 操作）。
 * 出参 MenuVO 树（含 children），入参 SysMenuSaveReqVO。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@RestController
@RequestMapping("/api/platform/system/menu")
@RequiredArgsConstructor
public class MenuController {

    private final SysMenuService menuService;

    /** 全量菜单树（菜单管理页表格 & 角色授权树共用） */
    @RequirePermission("system:menu:query")
    @GetMapping("/tree")
    public R<List<MenuVO>> tree() {
        return R.ok(menuService.tree());
    }

    @RequirePermission("system:menu:create")
    @PostMapping
    public R<Long> create(@Valid @RequestBody SysMenuSaveReqVO reqVO) {
        return R.ok(menuService.create(reqVO));
    }

    @RequirePermission("system:menu:update")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody SysMenuSaveReqVO reqVO) {
        reqVO.setId(id);
        menuService.update(id, reqVO);
        return R.ok();
    }

    @RequirePermission("system:menu:delete")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        menuService.delete(id);
        return R.ok();
    }
}
