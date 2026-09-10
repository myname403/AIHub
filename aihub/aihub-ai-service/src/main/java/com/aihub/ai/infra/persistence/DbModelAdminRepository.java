package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.domain.model.ModelProvider;
import com.aihub.ai.domain.model.ModelRouteInfo;
import com.aihub.ai.domain.spi.ModelAdminRepository;
import com.aihub.ai.infra.persistence.do_.AiModelDO;
import com.aihub.ai.infra.persistence.do_.AiModelProviderDO;
import com.aihub.ai.infra.persistence.do_.AiModelRouteDO;
import com.aihub.ai.infra.persistence.mapper.AiModelMapper;
import com.aihub.ai.infra.persistence.mapper.AiModelProviderMapper;
import com.aihub.ai.infra.persistence.mapper.AiModelRouteMapper;
import com.aihub.common.crypto.AesGcmTextCipher;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型管理仓储实现（管理端 CRUD）。
 *
 * <p>密钥处理是本类唯一的敏感职责：
 * <ul>
 *   <li>写入：明文 → AES-GCM 加密 → 落库（列类型 VARBINARY）；</li>
 *   <li>读取：只回 {@code hasApiKey} 布尔值，密文与明文都不出库。</li>
 * </ul>
 */
@Repository
@RequiredArgsConstructor
public class DbModelAdminRepository implements ModelAdminRepository {

    private final AiModelMapper modelMapper;
    private final AiModelRouteMapper routeMapper;
    private final AiModelProviderMapper providerMapper;

    /** 与 infra-ai 解密时用的必须是同一个 key，否则改了 Key 后模型立刻不可用 */
    @Value("${aihub.security.data-key:aihub-dev-data-key}")
    private String dataKey;

    @Override
    public List<ModelProvider> listProviders() {
        return providerMapper.selectList(Wrappers.<AiModelProviderDO>lambdaQuery()
                        .orderByAsc(AiModelProviderDO::getId))
                .stream()
                .map(p -> new ModelProvider(p.getCode(), p.getName(), p.getBaseUrl()))
                .toList();
    }

    @Override
    public List<ModelInfo> listModels(Long tenantId) {
        return modelMapper.selectList(Wrappers.<AiModelDO>lambdaQuery()
                        .eq(AiModelDO::getTenantId, tenantId)
                        .orderByDesc(AiModelDO::getIsDefault)
                        .orderByDesc(AiModelDO::getId))
                .stream().map(this::toInfo).toList();
    }

    @Override
    public Long createModel(Long tenantId, ModelCommand command) {
        AiModelDO entity = new AiModelDO();
        entity.setTenantId(tenantId);
        entity.setProviderCode(command.providerCode());
        entity.setModelCode(command.modelCode());
        entity.setBaseUrl(command.baseUrl());
        entity.setVectorDim(command.vectorDim());
        entity.setIsDefault(command.defaultModel() ? 1 : 0);
        entity.setStatus(command.status() == null ? 1 : command.status());
        // 只有传了明文才写密钥；留空则保持 NULL（例如纯 embedding 的本地模型无需 Key）
        if (command.apiKeyProvided()) {
            entity.setApiKeyEnc(AesGcmTextCipher.encrypt(command.apiKey(), dataKey));
        }
        modelMapper.insert(entity);
        return entity.getId();
    }

    /**
     * 更新模型。
     *
     * <p>Key 字段的处理是关键：{@code apiKey} 为空时<b>不能</b>把 apiKeyEnc 写成 null，
     * 否则管理员每次改温度都会把密钥清掉。MyBatis-Plus 的 {@code update} 默认忽略 null 字段，
     * 正好满足这个语义——但依赖默认行为容易在换工具时踩坑，所以这里显式注释说明。
     */
    @Override
    public boolean updateModel(Long tenantId, Long modelId, ModelCommand command) {
        AiModelDO patch = new AiModelDO();
        patch.setProviderCode(command.providerCode());
        patch.setModelCode(command.modelCode());
        patch.setBaseUrl(command.baseUrl());
        patch.setVectorDim(command.vectorDim());
        patch.setIsDefault(command.defaultModel() ? 1 : 0);
        patch.setStatus(command.status());
        if (command.apiKeyProvided()) {
            patch.setApiKeyEnc(AesGcmTextCipher.encrypt(command.apiKey(), dataKey));
        }
        return modelMapper.update(patch, Wrappers.<AiModelDO>lambdaUpdate()
                .eq(AiModelDO::getTenantId, tenantId)
                .eq(AiModelDO::getId, modelId)) > 0;
    }

    @Override
    public void clearDefaultFlag(Long tenantId, Long exceptModelId) {
        AiModelDO patch = new AiModelDO();
        patch.setIsDefault(0);
        modelMapper.update(patch, Wrappers.<AiModelDO>lambdaUpdate()
                .eq(AiModelDO::getTenantId, tenantId)
                .eq(AiModelDO::getIsDefault, 1)
                .ne(exceptModelId != null, AiModelDO::getId, exceptModelId));
    }

    /**
     * 软删模型。
     *
     * <p>同时清掉指向它的路由：否则路由会指向一个已删模型，
     * 运行时 {@code findRoute} 取到 null modelCode，表现为「配了路由但不生效」的隐蔽故障。
     */
    @Override
    public boolean deleteModel(Long tenantId, Long modelId) {
        routeMapper.delete(Wrappers.<AiModelRouteDO>lambdaUpdate()
                .eq(AiModelRouteDO::getTenantId, tenantId)
                .and(w -> w.eq(AiModelRouteDO::getPrimaryModelId, modelId)
                        .or().eq(AiModelRouteDO::getFallbackModelId, modelId)));
        return modelMapper.delete(Wrappers.<AiModelDO>lambdaQuery()
                .eq(AiModelDO::getTenantId, tenantId)
                .eq(AiModelDO::getId, modelId)) > 0;
    }

    @Override
    public List<ModelRouteInfo> listRoutes(Long tenantId) {
        List<AiModelRouteDO> routes = routeMapper.selectList(Wrappers.<AiModelRouteDO>lambdaQuery()
                .eq(AiModelRouteDO::getTenantId, tenantId)
                .orderByAsc(AiModelRouteDO::getScene));
        if (routes.isEmpty()) {
            return List.of();
        }
        // 一次性把本租户模型捞出来做 id → code 映射，避免每条路由查两次库
        Map<Long, String> codeById = new HashMap<>();
        for (AiModelDO model : modelMapper.selectList(Wrappers.<AiModelDO>lambdaQuery()
                .eq(AiModelDO::getTenantId, tenantId))) {
            codeById.put(model.getId(), model.getModelCode());
        }
        return routes.stream()
                .map(r -> new ModelRouteInfo(
                        r.getId(), r.getScene(),
                        r.getPrimaryModelId(), codeById.get(r.getPrimaryModelId()),
                        r.getFallbackModelId(), codeById.get(r.getFallbackModelId())))
                .toList();
    }

    @Override
    public void saveRoute(Long tenantId, String scene, Long primaryModelId, Long fallbackModelId) {
        // 模型归属由 application 层校验（业务规则不写在仓储里）；
        // 这里只负责「存在则更新、不存在则插入」的持久化语义。
        AiModelRouteDO existing = routeMapper.selectOne(Wrappers.<AiModelRouteDO>lambdaQuery()
                .eq(AiModelRouteDO::getTenantId, tenantId)
                .eq(AiModelRouteDO::getScene, scene)
                .last("limit 1"));
        if (existing == null) {
            AiModelRouteDO entity = new AiModelRouteDO();
            entity.setTenantId(tenantId);
            entity.setScene(scene);
            entity.setPrimaryModelId(primaryModelId);
            entity.setFallbackModelId(fallbackModelId);
            routeMapper.insert(entity);
            return;
        }
        AiModelRouteDO patch = new AiModelRouteDO();
        patch.setPrimaryModelId(primaryModelId);
        patch.setFallbackModelId(fallbackModelId);
        routeMapper.update(patch, Wrappers.<AiModelRouteDO>lambdaUpdate()
                .eq(AiModelRouteDO::getTenantId, tenantId)
                .eq(AiModelRouteDO::getScene, scene));
    }

    private ModelInfo toInfo(AiModelDO entity) {
        boolean hasKey = entity.getApiKeyEnc() != null && !entity.getApiKeyEnc().isBlank();
        return new ModelInfo(
                entity.getId(), entity.getProviderCode(), entity.getModelCode(),
                entity.getBaseUrl(), entity.getVectorDim(),
                entity.getIsDefault() != null && entity.getIsDefault() == 1,
                entity.getStatus(), hasKey);
    }
}
