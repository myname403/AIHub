package com.aihub.platform.system.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.system.entity.SysRoleDO;
import com.aihub.platform.system.entity.SysRoleMenuDO;
import com.aihub.platform.system.mapper.SysRoleMapper;
import com.aihub.platform.system.mapper.SysRoleMenuMapper;
import com.aihub.platform.system.vo.SysRoleRespVO;
import com.aihub.platform.system.vo.SysRoleSaveReqVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 角色服务：角色的 CRUD 与菜单授权（严格租户隔离）。
 *
 * <p><b>入参/出参都是 VO</b>：SysRoleSaveReqVO 进、SysRoleRespVO 出（Page 泛型同换），
 * DO 不出 Service 签名。VO → DO 的字段映射手写（字段少且有意排除不合法字段，
 * 如"编码创建后不可改"——用 BeanUtils 全量拷贝反而容易把不该改的字段带上）。
 *
 * <p><b>菜单授权的保存方式（芋道同款）：</b>前端传该角色勾选的全部菜单 ID，
 * 后端"先删旧关联、再批量插入新关联"整批替换。保存后失效权限缓存立即生效。
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Service
@RequiredArgsConstructor
public class SysRoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final PermissionService permissionService;

    /** DO → RespVO */
    private static SysRoleRespVO toRespVO(SysRoleDO role) {
        SysRoleRespVO vo = new SysRoleRespVO();
        vo.setId(role.getId());
        vo.setCode(role.getCode());
        vo.setName(role.getName());
        vo.setSort(role.getSort());
        vo.setStatus(role.getStatus());
        vo.setRemark(role.getRemark());
        return vo;
    }

    /** 分页查询本租户的角色（可选按名称/编码模糊过滤） */
    public Page<SysRoleRespVO> page(long pageNo, long pageSize, String keyword) {
        Long tenantId = TenantContext.requireTenantId();
        Page<SysRoleDO> doPage = roleMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<SysRoleDO>lambdaQuery()
                        .eq(SysRoleDO::getTenantId, tenantId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(SysRoleDO::getName, keyword)
                                        .or().like(SysRoleDO::getCode, keyword))
                        .orderByAsc(SysRoleDO::getSort));
        // Page 泛型转换：保留分页信息，records 逐条转 VO
        Page<SysRoleRespVO> voPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        voPage.setRecords(doPage.getRecords().stream().map(SysRoleService::toRespVO).toList());
        return voPage;
    }

    /** 本租户全部启用角色（下拉框用，不分页） */
    public List<SysRoleRespVO> listAll() {
        Long tenantId = TenantContext.requireTenantId();
        return roleMapper.selectList(Wrappers.<SysRoleDO>lambdaQuery()
                        .eq(SysRoleDO::getTenantId, tenantId)
                        .eq(SysRoleDO::getStatus, 1)
                        .orderByAsc(SysRoleDO::getSort))
                .stream().map(SysRoleService::toRespVO).toList();
    }

    /** 新增角色 */
    public Long create(SysRoleSaveReqVO reqVO) {
        Long tenantId = TenantContext.requireTenantId();
        validateCode(reqVO.getCode());
        SysRoleDO role = new SysRoleDO();
        role.setTenantId(tenantId);   // 强制本租户，防前端伪造
        role.setCode(reqVO.getCode());
        role.setName(reqVO.getName());
        role.setSort(reqVO.getSort() == null ? 0 : reqVO.getSort());
        role.setStatus(reqVO.getStatus() == null ? 1 : reqVO.getStatus());
        role.setRemark(reqVO.getRemark());
        roleMapper.insert(role);
        return role.getId();
    }

    /** 修改角色（编码创建后不可改 —— 它是代码里引用的标识） */
    public void update(Long id, SysRoleSaveReqVO reqVO) {
        SysRoleDO exists = mustGetOwn(id);
        if (reqVO.getCode() != null && !reqVO.getCode().equals(exists.getCode())) {
            throw new BizException(ResultCode.PARAM_ERROR, "角色编码不允许修改");
        }
        SysRoleDO patch = new SysRoleDO();
        patch.setId(id);
        patch.setName(reqVO.getName());
        patch.setSort(reqVO.getSort());
        patch.setStatus(reqVO.getStatus());
        patch.setRemark(reqVO.getRemark());
        roleMapper.updateById(patch);
        permissionService.invalidateRole(id);
    }

    /** 删除角色（整批删除其菜单授权） */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long roleId) {
        mustGetOwn(roleId);
        roleMenuMapper.delete(Wrappers.<SysRoleMenuDO>lambdaQuery()
                .eq(SysRoleMenuDO::getRoleId, roleId));
        roleMapper.deleteById(roleId);
        permissionService.invalidateRole(roleId);
    }

    /** 查角色已授权的菜单 ID 集合（授权弹窗的回显） */
    public List<Long> getMenuIds(Long roleId) {
        mustGetOwn(roleId);
        return roleMenuMapper.selectList(Wrappers.<SysRoleMenuDO>lambdaQuery()
                        .eq(SysRoleMenuDO::getRoleId, roleId))
                .stream().map(SysRoleMenuDO::getMenuId).toList();
    }

    /** 菜单授权：整批替换该角色的菜单关联（先删旧、再插新，单事务） */
    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        mustGetOwn(roleId);
        roleMenuMapper.delete(Wrappers.<SysRoleMenuDO>lambdaQuery()
                .eq(SysRoleMenuDO::getRoleId, roleId));
        if (menuIds != null) {
            for (Long menuId : menuIds) {
                SysRoleMenuDO rm = new SysRoleMenuDO();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            }
        }
        permissionService.invalidateRole(roleId);
    }

    /** 取本租户的角色，不存在/不是本租户的抛异常（防越权按 ID 操作） */
    private SysRoleDO mustGetOwn(Long roleId) {
        Long tenantId = TenantContext.requireTenantId();
        SysRoleDO role = roleMapper.selectById(roleId);
        if (role == null || !tenantId.equals(role.getTenantId())) {
            throw new BizException(ResultCode.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private void validateCode(String code) {
        if (code == null || !code.matches("^ROLE_[A-Z0-9_]{2,32}$")) {
            throw new BizException(ResultCode.PARAM_ERROR, "角色编码须为 ROLE_ 前缀的大写字母/数字/下划线");
        }
    }
}
