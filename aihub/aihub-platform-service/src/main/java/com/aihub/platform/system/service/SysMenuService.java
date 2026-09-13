package com.aihub.platform.system.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.platform.system.entity.SysMenuDO;
import com.aihub.platform.system.mapper.SysMenuMapper;
import com.aihub.platform.system.vo.MenuVO;
import com.aihub.platform.system.vo.SysMenuSaveReqVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单服务：菜单树构建与菜单管理 CRUD（平台全局数据）。
 *
 * <p><b>入参/出参都是 VO</b>：SysMenuSaveReqVO 进、MenuVO 树出——
 * DO 只在本类与 Mapper 之间流转，绝不出 Service 签名。
 *
 * <p><b>树是怎么组装的：</b>数据库里菜单靠 parent_id 平铺存储，
 * 查全表后按 parentId 分组，从根（parentId=0）开始递归挂 children。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Service
@RequiredArgsConstructor
public class SysMenuService {

    private final SysMenuMapper menuMapper;
    private final PermissionService permissionService;

    /** 全量菜单树（菜单管理页 & 角色授权树都用它） */
    public List<MenuVO> tree() {
        List<SysMenuDO> all = menuMapper.selectList(Wrappers.<SysMenuDO>lambdaQuery()
                .orderByAsc(SysMenuDO::getSort));
        return buildTree(all, 0L);
    }

    /**
     * 把"某用户可见的菜单列表"组装成树（PermissionController.routes 用）。
     * 与 tree() 的区别：输入是已按角色过滤后的列表（PermissionService 算好），这里只负责组装。
     */
    public List<MenuVO> buildUserMenuTree(List<SysMenuDO> userMenus) {
        return buildTree(userMenus, 0L);
    }

    /** 新增菜单。ID 由雪花生成；permission 规则见 @RequirePermission 注释 */
    public Long create(SysMenuSaveReqVO reqVO) {
        validate(reqVO);
        SysMenuDO menu = new SysMenuDO();
        menu.setName(reqVO.getName());
        menu.setPermission(reqVO.getPermission());
        menu.setMenuType(reqVO.getMenuType());
        menu.setParentId(reqVO.getParentId() == null ? 0L : reqVO.getParentId());
        menu.setPath(reqVO.getPath());
        menu.setComponent(reqVO.getComponent());
        menu.setIcon(reqVO.getIcon());
        menu.setSort(reqVO.getSort() == null ? 0 : reqVO.getSort());
        menu.setVisible(reqVO.getVisible() == null ? 1 : reqVO.getVisible());
        menu.setStatus(reqVO.getStatus() == null ? 1 : reqVO.getStatus());
        menuMapper.insert(menu);
        permissionService.invalidateAllMenus();
        return menu.getId();
    }

    /** 修改菜单（不允许改类型，避免按钮变菜单这种破坏树结构的操作） */
    public void update(Long id, SysMenuSaveReqVO reqVO) {
        SysMenuDO exists = menuMapper.selectById(id);
        if (exists == null) {
            throw new BizException(ResultCode.NOT_FOUND, "菜单不存在");
        }
        if (id.equals(reqVO.getParentId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "父菜单不能是自己");
        }
        SysMenuDO patch = new SysMenuDO();
        patch.setId(id);
        patch.setName(reqVO.getName());
        patch.setPermission(reqVO.getPermission());
        patch.setParentId(reqVO.getParentId());
        patch.setPath(reqVO.getPath());
        patch.setComponent(reqVO.getComponent());
        patch.setIcon(reqVO.getIcon());
        patch.setSort(reqVO.getSort());
        patch.setVisible(reqVO.getVisible());
        patch.setStatus(reqVO.getStatus());
        // menuType 不可改
        menuMapper.updateById(patch);
        permissionService.invalidateAllMenus();
    }

    /** 删除菜单（有子菜单时拒绝，防止留下孤儿节点） */
    public void delete(Long id) {
        Long children = menuMapper.selectCount(Wrappers.<SysMenuDO>lambdaQuery()
                .eq(SysMenuDO::getParentId, id));
        if (children > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "存在子菜单，请先删除子菜单");
        }
        menuMapper.deleteById(id);
        permissionService.invalidateAllMenus();
    }

    /** 递归组装树：parentId 相同的为一组，挂到各自父节点下（DO → MenuVO） */
    private List<MenuVO> buildTree(List<SysMenuDO> all, Long parentId) {
        Map<Long, List<SysMenuDO>> byParent = all.stream()
                .collect(Collectors.groupingBy(SysMenuDO::getParentId));
        return buildChildren(byParent, parentId);
    }

    private List<MenuVO> buildChildren(Map<Long, List<SysMenuDO>> byParent, Long parentId) {
        List<SysMenuDO> children = byParent.getOrDefault(parentId, new ArrayList<>());
        children.sort(Comparator.comparing(m -> m.getSort() == null ? 0 : m.getSort()));
        List<MenuVO> nodes = new ArrayList<>(children.size());
        for (SysMenuDO child : children) {
            MenuVO vo = MenuVO.from(child);
            vo.setChildren(buildChildren(byParent, child.getId()));
            nodes.add(vo);
        }
        return nodes;
    }

    /** 新增时的基础校验 */
    private void validate(SysMenuSaveReqVO reqVO) {
        if (reqVO.getMenuType() == null ||
                !("M".equals(reqVO.getMenuType()) || "C".equals(reqVO.getMenuType()) || "F".equals(reqVO.getMenuType()))) {
            throw new BizException(ResultCode.PARAM_ERROR, "菜单类型必须是 M/C/F");
        }
    }
}
