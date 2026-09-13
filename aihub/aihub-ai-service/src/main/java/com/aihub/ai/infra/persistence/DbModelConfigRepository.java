package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ModelEndpoint;
import com.aihub.ai.domain.model.ModelRoute;
import com.aihub.ai.domain.spi.ModelConfigRepository;
import com.aihub.ai.infra.persistence.dataobject.AiModelDO;
import com.aihub.ai.infra.persistence.dataobject.AiModelRouteDO;
import com.aihub.ai.infra.persistence.mapper.AiModelMapper;
import com.aihub.ai.infra.persistence.mapper.AiModelRouteMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 模型配置仓储实现（读 ai_model_route / ai_model）。
 *
 * <p>所有查询强制带 tenant_id——租户隔离的第一道业务防线，
 * MyBatis 拦截器与出口校验提供其余防线。
 */
@Repository
@RequiredArgsConstructor
public class DbModelConfigRepository implements ModelConfigRepository {

    private final AiModelRouteMapper routeMapper;
    private final AiModelMapper modelMapper;

    @Override
    public Optional<ModelRoute> findRoute(Long tenantId, String scene) {
        AiModelRouteDO route = routeMapper.selectOne(Wrappers.<AiModelRouteDO>lambdaQuery()
                .eq(AiModelRouteDO::getTenantId, tenantId)
                .eq(AiModelRouteDO::getScene, scene)
                .last("limit 1"));
        if (route == null) {
            return Optional.empty();
        }
        String primary = modelCodeOf(tenantId, route.getPrimaryModelId());
        String fallback = modelCodeOf(tenantId, route.getFallbackModelId());
        if (primary == null && fallback == null) {
            return Optional.empty();
        }
        return Optional.of(new ModelRoute(scene, primary, fallback));
    }

    @Override
    public Optional<ModelEndpoint> findEndpoint(Long tenantId, String modelCode) {
        AiModelDO model = modelMapper.selectOne(Wrappers.<AiModelDO>lambdaQuery()
                .eq(AiModelDO::getTenantId, tenantId)
                .eq(AiModelDO::getModelCode, modelCode)
                .eq(AiModelDO::getStatus, 1)
                .last("limit 1"));
        if (model == null) {
            return Optional.empty();
        }
        return Optional.of(new ModelEndpoint(
                model.getProviderCode(), model.getBaseUrl(), model.getApiKeyEnc(), model.getModelCode()));
    }

    private String modelCodeOf(Long tenantId, Long modelId) {
        if (modelId == null) {
            return null;
        }
        AiModelDO model = modelMapper.selectById(modelId);
        return model == null ? null : model.getModelCode();
    }
}
