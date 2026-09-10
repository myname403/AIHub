package com.aihub.ai.application;

import com.aihub.ai.domain.model.ModelRoute;
import com.aihub.ai.domain.spi.ModelGateway;
import com.aihub.common.tenant.TenantContext;
import org.springframework.stereotype.Service;

/**
 * 应用查询服务（编排层示例）。
 *
 * <p>依赖约束：编排层只依赖 domain 与 common，
 * <b>不得</b>依赖 infra-* 与 web（由 ArchUnit 卡口强制）。
 */
@Service
public class AppQueryService {

    private final ModelGateway modelGateway;

    public AppQueryService(ModelGateway modelGateway) {
        this.modelGateway = modelGateway;
    }

    /**
     * 查询某应用在当前场景下应使用的模型路由。
     * 租户 ID 从上下文获取，不接受调用方传参——避免越权。
     */
    public ModelRoute routeForApp(Long appId, String scene) {
        Long tenantId = TenantContext.requireTenantId();
        return modelGateway.routeFor(tenantId, scene);
    }
}
