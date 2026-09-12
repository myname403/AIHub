package com.aihub.platform.config;

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
 * <p>访问入口：{@code http://127.0.0.1:8081/swagger-ui.html}（JSON: {@code /v3/api-docs}）。
 * 生产环境不对外暴露时配置 {@code springdoc.api-docs.enabled=false}，
 * 并在网关/防火墙层面限制服务端口直连。
 *
 * <p>调试须知（两条请求链路的差别）：
 * <ul>
 *   <li><b>走网关（8080）</b>：JWT 在网关解析，X-Tenant-Id / X-User-Id 由网关注入，
 *       客户端伪造的同名头会被剥离——swagger 只用来查文档。</li>
 *   <li><b>直连本服务（8081）</b>：拦截器要求 X-Tenant-Id 头（/auth 登录除外），
 *       因此下方 customizer 给需要租户上下文的接口补了这两个头参数。</li>
 * </ul>
 *
 * <p><b>注解说明：</b>@Configuration 标记配置类；@Bean 方法返回值注册为 Spring Bean
 * （springdoc-openapi 启动时收集 OpenAPI Bean 渲染文档页、收集 OperationCustomizer Bean
 * 逐个修饰每个接口的文档描述）。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI aihubOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AIHub 平台服务 API")
                        .description("认证登录 / API Key 管理 / 用量配额 / 内部接口。"
                                + "internal 接口仅供服务间调用（走网关不可达）。")
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
