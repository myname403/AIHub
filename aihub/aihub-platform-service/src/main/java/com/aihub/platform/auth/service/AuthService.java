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
 * 认证服务。
 *
 * <p>登录必须显式指定租户（租户码），避免跨租户用户名冲突导致越权。
 */
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final JwtTokenProvider jwtTokenProvider;
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    public Map<String, Object> login(String tenantCode, String username, String rawPassword) {
        Long tenantId = resolveTenantId(tenantCode);

        SysUser user = userMapper.selectByTenantAndUsername(tenantId, username);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException(ResultCode.FORBIDDEN, "账号已停用");
        }

        String token = jwtTokenProvider.generate(tenantId, user.getId(), user.getUsername());

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
        return switch (tenantCode) {
            case "demo" -> 1001L;
            default -> Long.parseLong(tenantCode);
        };
    }
}
