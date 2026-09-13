package com.aihub.platform.system.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.common.tenant.TenantContext;
import com.aihub.platform.system.entity.SysRoleDO;
import com.aihub.platform.system.entity.SysUserRoleDO;
import com.aihub.platform.system.mapper.SysRoleMapper;
import com.aihub.platform.system.mapper.SysUserRoleMapper;
import com.aihub.platform.system.vo.SysUserCreateReqVO;
import com.aihub.platform.system.vo.SysUserRespVO;
import com.aihub.platform.user.entity.SysUserDO;
import com.aihub.platform.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 用户管理服务：管理员管理本租户的子用户（增删改/重置密码/分配角色）。
 *
 * <p><b>VO 分层的价值在本类体现得最直接：</b>
 * page() 返回 SysUserRespVO 列表——<b>passwordHash 在 DO → VO 转换时天然被丢弃</b>，
 * 不再依赖"转换完记得手动 setNull"这种容易漏的人肉防线。
 *
 * <p><b>两条安全红线：</b>
 * <ul>
 *   <li>一切操作都带 TenantContext.requireTenantId()（IDOR 防御）；</li>
 *   <li>操作者不能删除/停用自己（防止自锁）。</li>
 * </ul>
 * 详见学习文档《07-RBAC与管理端.md》。
 */
@Service
@RequiredArgsConstructor
public class SysUserService {

    /** 用户名规则：与注册一致 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^\\w{3,32}$");

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;

    /** DO → RespVO（密码哈希天然不出门） */
    private static SysUserRespVO toRespVO(SysUserDO user) {
        SysUserRespVO vo = new SysUserRespVO();
        vo.setId(user.getId());
        vo.setTenantId(user.getTenantId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateTime(user.getUpdateTime());
        return vo;
    }

    /** 分页查询本租户用户（可选按用户名/昵称模糊过滤） */
    public Page<SysUserRespVO> page(long pageNo, long pageSize, String keyword) {
        Long tenantId = TenantContext.requireTenantId();
        Page<SysUserDO> doPage = userMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<SysUserDO>lambdaQuery()
                        .eq(SysUserDO::getTenantId, tenantId)
                        .and(keyword != null && !keyword.isBlank(),
                                w -> w.like(SysUserDO::getUsername, keyword)
                                        .or().like(SysUserDO::getNickname, keyword))
                        .orderByDesc(SysUserDO::getCreateTime));
        Page<SysUserRespVO> voPage = new Page<>(doPage.getCurrent(), doPage.getSize(), doPage.getTotal());
        voPage.setRecords(doPage.getRecords().stream().map(SysUserService::toRespVO).toList());
        return voPage;
    }

    /** 新增子用户（可同时分配角色） */
    @Transactional(rollbackFor = Exception.class)
    public Long create(SysUserCreateReqVO reqVO) {
        Long tenantId = TenantContext.requireTenantId();
        if (reqVO.getUsername() == null || !USERNAME_PATTERN.matcher(reqVO.getUsername()).matches()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名须为 3-32 位字母/数字/下划线");
        }
        if (reqVO.getPassword() == null || reqVO.getPassword().length() < 6 || reqVO.getPassword().length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "密码长度须为 6-64 位");
        }
        // 租户内用户名唯一（uk_tenant_username 索引兜底，这里先查给友好提示）
        Long exists = userMapper.selectCount(Wrappers.<SysUserDO>lambdaQuery()
                .eq(SysUserDO::getTenantId, tenantId)
                .eq(SysUserDO::getUsername, reqVO.getUsername()));
        if (exists > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名已存在");
        }
        Long userId = IdWorker.getId();
        SysUserDO user = new SysUserDO();
        user.setId(userId);
        user.setTenantId(tenantId);
        user.setUsername(reqVO.getUsername());
        user.setNickname(reqVO.getNickname() == null || reqVO.getNickname().isBlank()
                ? reqVO.getUsername() : reqVO.getNickname());
        user.setPasswordHash(passwordEncoder.encode(reqVO.getPassword()));
        user.setStatus(1);
        userMapper.insert(user);
        if (reqVO.getRoleIds() != null && !reqVO.getRoleIds().isEmpty()) {
            assignRoles(userId, reqVO.getRoleIds());
        }
        return userId;
    }

    /** 修改用户（昵称/状态），不允许改用户名 */
    public void update(Long userId, String nickname, Integer status) {
        SysUserDO user = mustGetOwn(userId);
        SysUserDO patch = new SysUserDO();
        patch.setId(user.getId());
        patch.setNickname(nickname);
        if (status != null) {
            // 防自锁：不能停用自己
            if (status == 0 && userId.equals(TenantContext.getUserId())) {
                throw new BizException(ResultCode.PARAM_ERROR, "不能停用自己的账号");
            }
            patch.setStatus(status);
        }
        userMapper.updateById(patch);
        if (status != null) {
            permissionService.invalidateUser(user.getTenantId(), userId);
        }
    }

    /** 删除用户（逻辑删 + 解绑角色）。不能删自己 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId) {
        SysUserDO user = mustGetOwn(userId);
        if (userId.equals(TenantContext.getUserId())) {
            throw new BizException(ResultCode.PARAM_ERROR, "不能删除自己的账号");
        }
        userRoleMapper.delete(Wrappers.<SysUserRoleDO>lambdaQuery()
                .eq(SysUserRoleDO::getUserId, userId));
        userMapper.deleteById(userId);
        permissionService.invalidateUser(user.getTenantId(), userId);
    }

    /** 重置密码（管理员操作，无需旧密码） */
    public void resetPassword(Long userId, String newPassword) {
        SysUserDO user = mustGetOwn(userId);
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "密码长度须为 6-64 位");
        }
        SysUserDO patch = new SysUserDO();
        patch.setId(user.getId());
        patch.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(patch);
    }

    /** 分配角色：整批替换用户的角色绑定 */
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        Long tenantId = TenantContext.requireTenantId();
        mustGetOwn(userId);
        userRoleMapper.delete(Wrappers.<SysUserRoleDO>lambdaQuery()
                .eq(SysUserRoleDO::getUserId, userId));
        if (roleIds != null) {
            for (Long roleId : roleIds) {
                // 校验角色归属本租户（防拿别的租户的角色 ID 来绑）
                SysRoleDO role = roleMapper.selectById(roleId);
                if (role == null || !tenantId.equals(role.getTenantId())) {
                    throw new BizException(ResultCode.PARAM_ERROR, "角色不存在或不属于本租户");
                }
                SysUserRoleDO ur = new SysUserRoleDO();
                ur.setTenantId(tenantId);
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            }
        }
        permissionService.invalidateUser(tenantId, userId);
    }

    /** 查用户已绑定的角色 ID 集合（分配弹窗回显） */
    public List<Long> getRoleIds(Long userId) {
        mustGetOwn(userId);
        return userRoleMapper.selectList(Wrappers.<SysUserRoleDO>lambdaQuery()
                        .eq(SysUserRoleDO::getUserId, userId))
                .stream().map(SysUserRoleDO::getRoleId).toList();
    }

    /** 个人信息（任何登录用户可查自己的） */
    public SysUserRespVO profile() {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.requireUserId();
        SysUserDO user = userMapper.selectById(userId);
        if (user == null || !tenantId.equals(user.getTenantId())) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return toRespVO(user);
    }

    /** 修改自己的密码（需验证旧密码，与管理员重置不同） */
    public void changePassword(String oldPassword, String newPassword) {
        Long tenantId = TenantContext.requireTenantId();
        Long userId = TenantContext.requireUserId();
        SysUserDO user = userMapper.selectById(userId);
        if (user == null || !tenantId.equals(user.getTenantId())) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        if (!passwordEncoder.matches(oldPassword == null ? "" : oldPassword, user.getPasswordHash())) {
            throw new BizException(ResultCode.PARAM_ERROR, "旧密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6 || newPassword.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "新密码长度须为 6-64 位");
        }
        SysUserDO patch = new SysUserDO();
        patch.setId(userId);
        patch.setPasswordHash(passwordEncoder.encode(newPassword));
        userMapper.updateById(patch);
    }

    /** 取本租户的用户，不存在/不属于本租户抛异常（IDOR 防御核心） */
    private SysUserDO mustGetOwn(Long userId) {
        Long tenantId = TenantContext.requireTenantId();
        SysUserDO user = userMapper.selectById(userId);
        if (user == null || !tenantId.equals(user.getTenantId())) {
            throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
