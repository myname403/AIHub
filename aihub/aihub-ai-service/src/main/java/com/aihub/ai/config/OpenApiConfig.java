package com.aihub.ai.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;

import java.util.Arrays;

/**
 * OpenAPI 3 文档配置（springdoc-openapi）。
 *
 * <p>访问入口：{@code http://127.0.0.1:8082/swagger-ui.html}（JSON: {@code /v3/api-docs}）。
 * 生产环境不对外暴露时配置 {@code springdoc.api-docs.enabled=false}，
 * 并在网关/防火墙层面限制服务端口直连。
 *
 * <p>调试须知（两条请求链路的差别）：
 * <ul>
 *   <li><b>走网关（8080）</b>：JWT 在网关解析，X-Tenant-Id / X-User-Id 由网关注入，
 *       客户端伪造的同名头会被剥离——swagger 只用来查文档。</li>
 *   <li><b>直连本服务（8082）</b>：拦截器要求 X-Tenant-Id 头（缺失即拒），
 *       因此下方 customizer 给每个接口补了这两个头参数，配合 Authorize 按钮填 JWT 即可调试。</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aihubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AIHub AI 服务 API")
                        .description("模型网关 / 应用 / 知识库 RAG / Agent / 会话 / 产物。"
                                + "流式接口（NDJSON / SSE）在 UI 中展示为一次性 JSON 响应，调试请看响应体。")
                        .version("1.0.0"))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                // 全局要求 bearer 认证：UI 右上角出现 Authorize 按钮，填一次 token 全局生效
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }

    /**
     * 直连调试的租户头参数：网关链路自动注入（文档里标注即可），
     * 直连链路手动填写。/auth/** 是登录本身，无需租户上下文，跳过。
     */
    @Bean
    public OperationCustomizer tenantHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            if (isAuthEndpoint(handlerMethod)) {
                return operation;
            }
            operation.addParametersItem(new Parameter().in("header").name("X-Tenant-Id")
                    .description("租户 ID（走网关自动注入；直连调试必填）")
                    .required(false)
                    .schema(new StringSchema().example("1")));
            operation.addParametersItem(new Parameter().in("header").name("X-User-Id")
                    .description("用户 ID（走网关自动注入；直连调试可选）")
                    .required(false)
                    .schema(new StringSchema().example("1")));
            return operation;
        };
    }

    /** 类级 @RequestMapping 以 /auth 开头即视为登录端点（AuthController），无需租户头 */
    private boolean isAuthEndpoint(HandlerMethod handlerMethod) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(
                handlerMethod.getBeanType(), RequestMapping.class);
        return mapping != null && Arrays.stream(mapping.value())
                .anyMatch(path -> path.startsWith("/auth"));
    }
}
