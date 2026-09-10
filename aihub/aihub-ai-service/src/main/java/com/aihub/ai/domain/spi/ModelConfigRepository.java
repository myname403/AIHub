package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.ai.domain.model.ModelRoute;

import java.util.Optional;

/**
 * 模型配置仓储 SPI。
 *
 * <p>由 infra-persistence 实现（读 ai_model_route / ai_model 表）。
 * infra-ai 通过它取配置，从而满足「infra 模块间禁止横向依赖」的架构卡口。
 */
public interface ModelConfigRepository {

    /** 查询某租户某场景的模型路由（主模型 + 备用模型） */
    Optional<ModelRoute> findRoute(Long tenantId, String scene);

    /** 查询某租户某模型的接入点信息 */
    Optional<ModelEndpoint> findEndpoint(Long tenantId, String modelCode);
}
