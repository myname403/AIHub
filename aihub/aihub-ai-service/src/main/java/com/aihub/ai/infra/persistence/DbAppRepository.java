package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.ai.infra.persistence.do_.AiAppDO;
import com.aihub.ai.infra.persistence.do_.AiAppKbDO;
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
        if (ddo == null) {
            return Optional.empty();
        }
        App app = new App();
        app.setId(ddo.getId());
        app.setTenantId(ddo.getTenantId());
        app.setName(ddo.getName());
        app.setSystemPrompt(ddo.getSystemPrompt());
        app.setAgentStrategy(ddo.getAgentStrategy());
        app.setMemoryPolicy(ddo.getMemoryPolicy());
        app.setStatus(ddo.getStatus());
        return Optional.of(app);
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
        if (exists > 0) {
            return;
        }
        AiAppKbDO ddo = new AiAppKbDO();
        ddo.setTenantId(tenantId);
        ddo.setAppId(appId);
        ddo.setKbId(kbId);
        appKbMapper.insert(ddo);
    }
}
