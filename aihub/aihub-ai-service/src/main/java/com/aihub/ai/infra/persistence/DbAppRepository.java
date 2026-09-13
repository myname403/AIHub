package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.infra.persistence.dataobject.AiAppDO;
import com.aihub.ai.infra.persistence.dataobject.AiAppKbDO;
import com.aihub.ai.infra.persistence.mapper.AiAppKbMapper;
import com.aihub.ai.infra.persistence.mapper.AiAppMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 应用仓储实现（ai_app / ai_app_kb）。所有查询强制带 tenant_id。
 */
@Repository
@RequiredArgsConstructor
public class DbAppRepository implements AppRepository {

    private final AiAppMapper appMapper;
    private final AiAppKbMapper appKbMapper;

    @Override
    public Optional<App> find(Long tenantId, Long appId) {
        AiAppDO ddo = appMapper.selectOne(Wrappers.<AiAppDO>lambdaQuery()
                .eq(AiAppDO::getTenantId, tenantId)
                .eq(AiAppDO::getId, appId)
                .last("limit 1"));
        return ddo == null ? Optional.empty() : Optional.of(toDomain(ddo));
    }

    @Override
    public List<App> list(Long tenantId) {
        return appMapper.selectList(Wrappers.<AiAppDO>lambdaQuery()
                        .eq(AiAppDO::getTenantId, tenantId)
                        .orderByDesc(AiAppDO::getId))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public Long create(App app) {
        AiAppDO ddo = new AiAppDO();
        // 显式忽略调用方传入的 id：新建时由 IdWorker 生成，防止覆盖他人的主键
        ddo.setTenantId(app.getTenantId());
        ddo.setName(app.getName());
        ddo.setSystemPrompt(app.getSystemPrompt());
        ddo.setModelRouteId(app.getModelRouteId());
        ddo.setAgentStrategy(app.getAgentStrategy());
        ddo.setMemoryPolicy(app.getMemoryPolicy());
        ddo.setTemperature(app.getTemperature());
        ddo.setStatus(app.getStatus());
        appMapper.insert(ddo);
        return ddo.getId();
    }

    /**
     * 更新：WHERE 条件同时带 tenantId 与 id。
     *
     * <p>这不是「先查再改」的乐观检查，而是把租户作为更新条件本身——
     * 即使调用方拿到了别人的 appId，影响行数也会是 0 而不是改掉别人的数据。
     */
    @Override
    public boolean update(Long tenantId, App app) {
        AiAppDO patch = new AiAppDO();
        patch.setName(app.getName());
        patch.setSystemPrompt(app.getSystemPrompt());
        patch.setModelRouteId(app.getModelRouteId());
        patch.setAgentStrategy(app.getAgentStrategy());
        patch.setMemoryPolicy(app.getMemoryPolicy());
        patch.setTemperature(app.getTemperature());
        patch.setStatus(app.getStatus());
        return appMapper.update(patch, Wrappers.<AiAppDO>lambdaUpdate()
                .eq(AiAppDO::getTenantId, tenantId)
                .eq(AiAppDO::getId, app.getId())) > 0;
    }

    @Override
    public boolean delete(Long tenantId, Long appId) {
        // 先删绑定关系，避免遗留孤儿行让 RAG 仍能召回已删应用的知识库
        appKbMapper.delete(Wrappers.<AiAppKbDO>lambdaQuery()
                .eq(AiAppKbDO::getTenantId, tenantId)
                .eq(AiAppKbDO::getAppId, appId));
        return appMapper.delete(Wrappers.<AiAppDO>lambdaQuery()
                .eq(AiAppDO::getTenantId, tenantId)
                .eq(AiAppDO::getId, appId)) > 0;
    }

    @Override
    public List<Long> knowledgeBaseIds(Long tenantId, Long appId) {
        return appKbMapper.selectList(Wrappers.<AiAppKbDO>lambdaQuery()
                        .eq(AiAppKbDO::getTenantId, tenantId)
                        .eq(AiAppKbDO::getAppId, appId))
                .stream().map(AiAppKbDO::getKbId).toList();
    }

    @Override
    public void bindKnowledgeBase(Long tenantId, Long appId, Long kbId) {
        Long exists = appKbMapper.selectCount(Wrappers.<AiAppKbDO>lambdaQuery()
                .eq(AiAppKbDO::getTenantId, tenantId)
                .eq(AiAppKbDO::getAppId, appId)
                .eq(AiAppKbDO::getKbId, kbId));
        if (exists != null && exists > 0) {
            return;
        }
        AiAppKbDO ddo = new AiAppKbDO();
        ddo.setTenantId(tenantId);
        ddo.setAppId(appId);
        ddo.setKbId(kbId);
        appKbMapper.insert(ddo);
    }

    @Override
    public boolean unbindKnowledgeBase(Long tenantId, Long appId, Long kbId) {
        return appKbMapper.delete(Wrappers.<AiAppKbDO>lambdaQuery()
                .eq(AiAppKbDO::getTenantId, tenantId)
                .eq(AiAppKbDO::getAppId, appId)
                .eq(AiAppKbDO::getKbId, kbId)) > 0;
    }

    private App toDomain(AiAppDO ddo) {
        App app = new App();
        app.setId(ddo.getId());
        app.setTenantId(ddo.getTenantId());
        app.setName(ddo.getName());
        app.setSystemPrompt(ddo.getSystemPrompt());
        app.setModelRouteId(ddo.getModelRouteId());
        app.setAgentStrategy(ddo.getAgentStrategy());
        app.setMemoryPolicy(ddo.getMemoryPolicy());
        app.setTemperature(ddo.getTemperature());
        app.setStatus(ddo.getStatus());
        return app;
    }
}
