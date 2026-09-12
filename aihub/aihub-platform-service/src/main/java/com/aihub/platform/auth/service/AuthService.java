package com.aihub.platform.auth.service;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import com.aihub.platform.security.JwtTokenProvider;
import com.aihub.platform.user.entity.SysUser;
import com.aihub.platform.user.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 认证服务 —— 登录的核心业务逻辑（验密码、发令牌）。
 *
 * <p><b>完整登录流程（对照 login 方法逐步看）：</b>
 * <pre>
 * ① 租户码 → 租户 ID（resolveTenantId）
 * ② 按 租户+用户名 查用户（带租户条件，天然防跨租户撞库）
 * ③ BCrypt 校验密码（数据库只存哈希）
 * ④ 校验账号状态（停用的拒绝）
 * ⑤ 签发 JWT（tenantId/userId 写进令牌）
 * ⑥ 组装返回（token + 基本信息）
 * </pre>
 *
 * <p><b>安全细节：</b>用户不存在和密码错误返回<b>同一个提示</b>"用户名或密码错误"——
 * 如果分别提示"用户不存在"，攻击者就能批量探测哪些用户名有效（用户名枚举攻击）。
 *
 * <p>登录必须显式指定租户（租户码），避免跨租户用户名冲突导致越权。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Service          // 声明为业务层 Bean，Spring 启动时创建单例并注入依赖
@RequiredArgsConstructor   // Lombok：为 final 字段生成构造器，实现构造器注入
public class AuthService {

    /** MyBatis-Plus 的用户表访问器（BaseMapper），selectByTenantAndUsername 是自定义 SQL */
    private final SysUserMapper userMapper;

    /** JWT 签发器（见 security 包），负责生成带 tenantId/userId 的令牌 */
    private final JwtTokenProvider jwtTokenProvider;

    /**
     * BCrypt 密码校验器。
     * BCrypt 特点：① 加盐（同一个密码每次哈希结果都不同，防彩虹表）；
     * ② 刻意慢（约几十毫秒，拖慢暴力破解）；③ matches() 直接对比"明文 vs 哈希"，无需手工加盐。
     * 这里直接 new 而不是注入 Bean：无状态工具对象，且未注册为全局 Bean（历史原因）。
     */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录主流程。
     *
     * @param tenantCode  租户码（如 "demo"），登录必须显式指定租户
     * @param username    用户名
     * @param rawPassword 用户输入的明文密码（只在内存，比较完即弃）
     * @return token + 租户/用户信息；任何一步失败抛 BizException（由全局异常处理器转响应）
     */
    public Map<String, Object> login(String tenantCode, String username, String rawPassword) {
        // ① 解析租户：租户码 → 租户 ID，后续所有查询都以这个 ID 限定范围
        Long tenantId = resolveTenantId(tenantCode);

        // ② 查用户：SQL 带 tenant_id 条件 —— 同名用户在不同租户互不干扰
        SysUser user = userMapper.selectByTenantAndUsername(tenantId, username);
        if (user == null) {
            // 用户不存在与密码错误用同一文案（防用户名枚举，见类注释）
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        // ③ 验密码：matches(明文, 库里哈希)。哈希算法自带随机盐，直接比即可
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        // ④ 验状态：1=启用。status 为 null 视为停用（fail-safe：不确定时按最严处理）
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已停用");
        }

        // ⑤ 签发 JWT：tenantId/userId/username 写进令牌 payload，有效期由配置决定
        String token = jwtTokenProvider.generate(tenantId, user.getId(), user.getUsername());

        // ⑥ 组装响应数据（Map 结构与前端约定）
        Map<String, Object> result = new HashMap<>();
        result.put("token", token);
        result.put("tenantId", tenantId);
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        return result;
    }

    /**
     * 解析租户码为租户 ID。
     * M0 阶段使用内置映射（演示用），后续接入 sys_tenant 表查询。
     */
    private Long resolveTenantId(String tenantCode) {
        if (tenantCode == null || tenantCode.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "缺少租户标识");
        }
        // TODO(M0->M1): 改为查询 sys_tenant 表并校验租户状态
        // switch 表达式（Java 14+）：值返回型 switch，比传统 break 写法简洁
        return switch (tenantCode) {
            case "demo" -> 1001L;
            default -> Long.parseLong(tenantCode);
        };
    }
}
