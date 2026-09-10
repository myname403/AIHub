package com.aihub.platform.auth.controller;

import com.aihub.common.result.R;
import com.aihub.platform.auth.service.AuthService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;

/**
 * 认证接口。网关已将此路径配置为免鉴权（/auth/**）。
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return R.ok(authService.login(request.getTenantCode(), request.getUsername(), request.getPassword()));
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "租户标识不能为空")
        private String tenantCode;
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }
}
