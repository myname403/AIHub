package com.aihub.ai.infra.ai;

import com.aihub.ai.domain.model.ModelRoute;
import com.aihub.ai.domain.spi.ModelConfigRepository;
import com.aihub.ai.domain.spi.ModelGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 模型网关实现：从 DB（ai_model_route / ai_model）解析场景路由。
 *
 * <p>未配置路由时退回到 application.yml / Nacos 的默认模型配置（开发环境友好）。
 * M1 后续：接入主备降级探测与健康检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbModelGateway implements ModelGateway {

    private final ModelConfigRepository configRepository;

    @Value("${aihub.model.default-chat-model:gpt-4o-mini}")
    private String defaultChatModel;

    @Value("${aihub.model.default-fallback-model:}")
    private String defaultFallbackModel;

    @Override
    public ModelRoute routeFor(Long tenantId, String scene) {
        return configRepository.findRoute(tenantId, scene)
                .orElseGet(() -> new ModelRoute(scene, defaultChatModel, defaultFallbackModel));
    }

    @Override
    public boolean isAvailable(String modelCode) {
        if (modelCode == null || modelCode.isBlank()) {
            return false;
        }
        // 默认模型走 Spring Boot 自动装配（spring.ai.openai.*），始终视为可用
        if (defaultChatModel.equals(modelCode)) {
            return true;
        }
        Long tenantId = com.aihub.common.tenant.TenantContext.getTenantId();
        if (tenantId == null) {
            log.debug("isAvailable 无租户上下文，按可用处理");
            return true;
        }
        return configRepository.findEndpoint(tenantId, modelCode).isPresent();
    }
}
