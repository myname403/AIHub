package com.aihub.platform.system.vo;

import com.aihub.platform.system.entity.SysMenuDO;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 菜单 VO（含 children 树结构）—— 管理端菜单树/授权树/动态路由共用。
 *
 * <p>替代原先的 MenuNode record 包装（{menu:{...}, children}）——
 * 直接把字段提升到 VO 本层，前端取值少一层嵌套。
 * <b>注意：</b>改了本 VO 的字段结构必须同步调整管理端
 * system/role、system/menu 两个页面的树数据处理。
 */
@Data
public class MenuVO {

    private Long id;
    private String name;
    private String permission;
    private String menuType;
    private Long parentId;
    private String path;
    private String component;
    private String icon;
    private Integer sort;
    private Integer visible;
    private Integer status;

    /** 子菜单 */
    private List<MenuVO> children = new ArrayList<>();

    /** DO → VO（不含 children，树组装在 Service） */
    public static MenuVO from(SysMenuDO menu) {
        MenuVO vo = new MenuVO();
        vo.setId(menu.getId());
        vo.setName(menu.getName());
        vo.setPermission(menu.getPermission());
        vo.setMenuType(menu.getMenuType());
        vo.setParentId(menu.getParentId());
        vo.setPath(menu.getPath());
        vo.setComponent(menu.getComponent());
        vo.setIcon(menu.getIcon());
        vo.setSort(menu.getSort());
        vo.setVisible(menu.getVisible());
        vo.setStatus(menu.getStatus());
        return vo;
    }
}
