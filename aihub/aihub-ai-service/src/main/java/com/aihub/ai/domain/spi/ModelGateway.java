package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ModelRoute;

/**
 * 模型网关 SPI（★ 扩展点）。
 *
 * <p>定义在领域层、实现在基础设施层：业务代码只认识这个接口，
 * 因此更换模型厂商（OpenAI 兼容 / 火山方舟 / 通义 / Ollama）时业务代码零改动。
 *
 * <p>M1 阶段会扩展为返回真正的模型句柄；此处先定义"场景选模"这一核心语义。
 */
public interface ModelGateway {

    /**
     * 按场景解析模型路由。
     *
     * @param tenantId 租户 ID（来自 TenantContext，绝不由前端传入）
     * @param scene    场景：chat / rag / embed / agent-plan
     */
    ModelRoute routeFor(Long tenantId, String scene);

    /** 判断某个模型当前是否可用（用于降级决策） */
    boolean isAvailable(String modelCode);
}
