package com.aihub.platform.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAPI 文档配置单测：钉住直连调试的租户头注入规则——
 * 普通接口补 X-Tenant-Id / X-User-Id（网关链路自动注入，直连手动填），
 * /auth 登录端点跳过（登录本身不要求租户上下文）。
 */
class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @RestController
    @RequestMapping("/api/tenants")
    static class FakeTenantController {
        @GetMapping("/x")
        public String x() { return "ok"; }
    }

    @RestController
    @RequestMapping("/auth")
    static class FakeAuthController {
        @GetMapping("/y")
        public String y() { return "ok"; }
    }

    private Operation customize(Class<?> controllerType, String methodName) throws Exception {
        Method method = controllerType.getMethod(methodName);
        HandlerMethod handlerMethod = new HandlerMethod(controllerType.getDeclaredConstructor().newInstance(), method);
        return config.tenantHeaderCustomizer().customize(new Operation(), handlerMethod);
    }

    @Test
    void normalEndpointGetsTenantHeaders() throws Exception {
        Operation operation = customize(FakeTenantController.class, "x");

        List<String> names = operation.getParameters().stream()
                .map(io.swagger.v3.oas.models.parameters.Parameter::getName)
                .toList();
        assertThat(names).containsExactly("X-Tenant-Id", "X-User-Id");
    }

    @Test
    void authEndpointSkipsTenantHeaders() throws Exception {
        Operation operation = customize(FakeAuthController.class, "y");

        assertThat(operation.getParameters()).isNull();
    }

    @Test
    void openApiExposesBearerScheme() {
        OpenAPI openApi = config.aihubOpenApi();

        SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("bearerAuth");
        assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
        assertThat(scheme.getScheme()).isEqualTo("bearer");
    }
}
