package com.aihub.platform.auth.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.platform.auth.vo.AuthLoginReqVO;
import com.aihub.platform.auth.vo.AuthLoginRespVO;
import com.aihub.platform.auth.vo.AuthRegisterReqVO;
import com.aihub.platform.auth.vo.AuthRegisterRespVO;
import com.aihub.platform.security.JwtTokenProvider;
import com.aihub.platform.system.entity.SysRoleDO;
import com.aihub.platform.system.entity.SysRoleMenuDO;
import com.aihub.platform.system.entity.SysUserRoleDO;
import com.aihub.platform.system.mapper.SysMenuMapper;
import com.aihub.platform.system.mapper.SysRoleMapper;
import com.aihub.platform.system.mapper.SysRoleMenuMapper;
import com.aihub.platform.system.mapper.SysUserRoleMapper;
import com.aihub.platform.tenant.entity.SysTenantDO;
import com.aihub.platform.user.mapper.SysTenantMapper;
import com.aihub.platform.user.entity.SysUserDO;
import com.aihub.platform.user.mapper.SysUserMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 认证服务 —— 登录 + 注册的核心业务逻辑。
 *
 * <p><b>完整登录流程（对照 login 方法逐步看）：</b>
 * <pre>
 * ⓪ 图形验证码校验（防暴力破解，一次性消费）
 * ① 租户码 → 租户 ID（查 sys_tenant 并校验状态）
 * ② 按 租户+用户名 查用户（带租户条件，天然防跨租户撞库）
 * ③ BCrypt 校验密码（数据库只存哈希）
 * ④ 校验账号状态（停用的拒绝）
 * ⑤ 签发 JWT（tenantId/userId 写进令牌）
 * </pre>
 *
 * <p><b>安全细节：</b>用户不存在和密码错误返回<b>同一个提示</b>"用户名或密码错误"——
 * 如果分别提示"用户不存在"，攻击者就能批量探测哪些用户名有效（用户名枚举攻击）。
 *
 * <p><b>入参/出参都是 VO</b>（web 层模型），本服务负责 VO ↔ DO 的转换——
 * Controller 永远不接触 DO。详见学习文档《07-RBAC与管理端.md》。
 */
@Service          // 声明为业务层 Bean，Spring 启动时创建单例并注入依赖
@RequiredArgsConstructor   // Lombok：为 final 字段生成构造器，实现构造器注入
public class AuthService {

    /** 租户码规则：2-32 位字母/数字/下划线/中划线（会成为登录时的"公司标识"） */
    private static final Pattern TENANT_CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,32}$");
    /** 用户名规则：3-32 位字母/数字/下划线 */
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^\\w{3,32}$");

    /** 注册内置角色编码：每个租户的第一个用户即该租户管理员 */
    public static final String ROLE_ADMIN_CODE = "ROLE_ADMIN";

    /** MyBatis-Plus 的用户表访问器（BaseMapper），selectByTenantAndUsername 是自定义 SQL */
    private final SysUserMapper userMapper;

    /** 租户表访问器（注册时建租户 + 登录时解析租户码） */
    private final SysTenantMapper tenantMapper;

    /** 角色相关访问器（注册时建 ROLE_ADMIN 并授权全部菜单） */
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    /** 图形验证码（登录/注册防刷） */
    private final CaptchaService captchaService;

    /** JWT 签发器（见 security 包），负责生成带 tenantId/userId 的令牌 */
    private final JwtTokenProvider jwtTokenProvider;

    /** 密码编码器（CryptoConfig 提供的 BCrypt Bean，全局单例） */
    private final PasswordEncoder passwordEncoder;

    /* ==================== 登录 ==================== */

    /**
     * 登录主流程（含防暴力破解验证码校验）。
     */
    public AuthLoginRespVO login(AuthLoginReqVO reqVO) {
        // ⓪ 验证码：防暴力破解（穷举密码的第一道闸）。一次性消费，失败即作废。
        //    放在最前面——验证码都不对就没必要查库了（省资源 + 不泄露"账号是否存在"的信息）。
        if (!captchaService.verify(reqVO.getCaptchaId(), reqVO.getCaptchaCode())) {
            throw new BizException(ResultCode.PARAM_ERROR, "验证码错误或已过期");
        }
        // ① 解析租户：租户码 → 租户 ID，后续所有查询都以这个 ID 限定范围
        Long tenantId = resolveTenantId(reqVO.getTenantCode());

        // ② 查用户：SQL 带 tenant_id 条件 —— 同名用户在不同租户互不干扰
        SysUserDO user = userMapper.selectByTenantAndUsername(tenantId, reqVO.getUsername());
        if (user == null) {
            // 用户不存在与密码错误用同一文案（防用户名枚举，见类注释）
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        // ③ 验密码：matches(明文, 库里哈希)。哈希算法自带随机盐，直接比即可
        if (!passwordEncoder.matches(reqVO.getPassword(), user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        // ④ 验状态：1=启用。status 为 null 视为停用（fail-safe：不确定时按最严处理）
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已停用");
        }

        // ⑤ 签发 JWT：tenantId/userId/username 写进令牌 payload，有效期由配置决定
        String token = jwtTokenProvider.generate(tenantId, user.getId(), user.getUsername());

        // ⑥ 组装登录响应 VO（类型安全，替代手拼 HashMap）
        AuthLoginRespVO respVO = new AuthLoginRespVO();
        respVO.setToken(token);
        respVO.setTenantId(tenantId);
        respVO.setUserId(user.getId());
        respVO.setUsername(user.getUsername());
        return respVO;
    }

    /* ==================== 注册 ==================== */

    /**
     * 自助注册：创建新租户 + 管理员账号 + ROLE_ADMIN 角色（授权全部菜单）。
     *
     * <p><b>@Transactional（重要）：</b>本方法要写 5 张表（租户/用户/角色/角色菜单/用户角色）。
     * 事务保证"要么全部成功、要么全部回滚"——比如授权菜单时失败，不能留下
     * "有租户有管理员但没有角色"的脏数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public AuthRegisterRespVO register(AuthRegisterReqVO reqVO) {
        String companyName = reqVO.getCompanyName();
        String tenantCode = reqVO.getTenantCode();
        String username = reqVO.getUsername();
        String password = reqVO.getPassword();

        // ① 基础校验：验证码 → 格式 → 唯一性（顺序：先廉价的本地校验，再查库）
        if (!captchaService.verify(reqVO.getCaptchaId(), reqVO.getCaptchaCode())) {
            throw new BizException(ResultCode.PARAM_ERROR, "验证码错误或已过期");
        }
        if (companyName == null || companyName.isBlank() || companyName.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "企业名称不能为空且不超过 64 字");
        }
        if (tenantCode == null || !TENANT_CODE_PATTERN.matcher(tenantCode).matches()) {
            throw new BizException(ResultCode.PARAM_ERROR, "租户码须为 2-32 位字母/数字/下划线/中划线");
        }
        if (username == null || !USERNAME_PATTERN.matcher(username).matches()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名须为 3-32 位字母/数字/下划线");
        }
        if (password == null || password.length() < 6 || password.length() > 64) {
            throw new BizException(ResultCode.PARAM_ERROR, "密码长度须为 6-64 位");
        }
        // "demo" 是演示租户保留码，防止被抢注导致演示账号混乱
        if ("demo".equalsIgnoreCase(tenantCode)) {
            throw new BizException(ResultCode.PARAM_ERROR, "该租户码为系统保留，请换一个");
        }

        // ② 唯一性检查：租户码全局唯一（登录标识）
        if (tenantMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysTenantDO>()
                        .eq(SysTenantDO::getCode, tenantCode)) > 0) {
            throw new BizException(ResultCode.PARAM_ERROR, "租户码已被使用");
        }

        // ③ 建租户：status=1 直接启用（教学项目不做审核流）
        Long tenantId = IdWorker.getId();
        SysTenantDO tenant = new SysTenantDO();
        tenant.setId(tenantId);
        tenant.setName(companyName);
        tenant.setCode(tenantCode);
        tenant.setStatus(1);
        tenantMapper.insert(tenant);

        // ④ 建管理员用户（BCrypt 加密后落库，永不存明文）
        Long userId = IdWorker.getId();
        SysUserDO admin = new SysUserDO();
        admin.setId(userId);
        admin.setTenantId(tenantId);
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setNickname("管理员");
        admin.setStatus(1);
        userMapper.insert(admin);

        // ⑤ 建 ROLE_ADMIN 角色（每个租户各自一份，互不可见）
        Long roleId = IdWorker.getId();
        SysRoleDO role = new SysRoleDO();
        role.setId(roleId);
        role.setTenantId(tenantId);
        role.setCode(ROLE_ADMIN_CODE);
        role.setName("管理员");
        role.setSort(1);
        role.setStatus(1);
        role.setRemark("租户注册时自动创建，拥有本租户全部权限");
        roleMapper.insert(role);

        // ⑥ 角色授权全部菜单：查全部菜单 ID，逐条建关联
        List<Long> allMenuIds = menuMapper.selectList(null).stream()
                .map(m -> m.getId()).toList();
        for (Long menuId : allMenuIds) {
            SysRoleMenuDO rm = new SysRoleMenuDO();
            rm.setRoleId(roleId);
            rm.setMenuId(menuId);
            roleMenuMapper.insert(rm);
        }

        // ⑦ 用户绑定角色
        SysUserRoleDO ur = new SysUserRoleDO();
        ur.setTenantId(tenantId);
        ur.setUserId(userId);
        ur.setRoleId(roleId);
        userRoleMapper.insert(ur);

        // ⑧ 返回注册响应 VO（前端展示后跳登录页）
        return new AuthRegisterRespVO(tenantCode, username, "注册成功，请使用租户码 + 用户名登录");
    }

    /**
     * 解析租户码为租户 ID（查 sys_tenant 表并校验状态）。
     * 注意"租户不存在"与"用户不存在/密码错误"分开提示：注册流程要求用户
     * 自查租户码，这里明确报错更友好（登录接口仍保持防枚举的模糊提示）。
     */
    private Long resolveTenantId(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少租户标识");
        }
        SysTenantDO tenant = tenantMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysTenantDO>()
                        .eq(SysTenantDO::getCode, tenantCode)
                        .last("limit 1"));
        if (tenant == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "租户不存在，请检查租户码");
        }
        if (tenant.getStatus() == null || tenant.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN, "租户已停用");
        }
        return tenant.getId();
    }
}
