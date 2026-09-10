package com.aihub.ai.application;

import com.aihub.ai.domain.model.App;
import com.aihub.ai.domain.spi.AppRepository;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 应用配置管理服务（管理端）。
 *
 * <p>分层约束：只依赖 domain 与 common，不碰 infra / web（ArchUnit 强制）。
 *
 * <p>与 {@link AppQueryService} 的分工：那个是运行时的「查应用走哪条模型路由」，
 * 这个是配置期的 CRUD。
 */
@Service
@RequiredArgsConstructor
public class AppAdminService {

    /** 与 ai_app.agent_strategy 的取值保持一致 */
    private static final List<String> VALID_STRATEGIES = List.of("none", "react", "plan_execute");
    private static final List<String> VALID_MEMORY = List.of("window", "summary");

    private final AppRepository appRepository;

    public List<App> list(Long tenantId) {
        return appRepository.list(tenantId);
    }

    public App get(Long tenantId, Long appId) {
        return appRepository.find(tenantId, appId)
                .orElseThrow(() -> new BizException(ResultCode.NOT_FOUND, "应用不存在"));
    }

    /**
     * 新建应用。
     *
     * <p>tenantId 由调用方从上下文传入，<b>并强制写到实体上</b>——
     * 即使请求体里带了 tenantId 也不会被采用（安全红线 1）。
     */
    public Long create(Long tenantId, App app) {
        validate(app);
        app.setId(null);
        app.setTenantId(tenantId);
        app.applyDefaults();
        return appRepository.create(app);
    }

    /**
     * 更新应用。
     *
     * <p>先按 (tenantId, appId) 确认存在，再更新。仓储层的更新语句本身也带 tenantId 条件，
     * 因此即便这里被绕过，也改不到别人的数据——双重保险。
     */
    public void update(Long tenantId, Long appId, App app) {
        validate(app);
        app.setId(appId);
        app.setTenantId(tenantId);
        app.applyDefaults();
        if (!appRepository.update(tenantId, app)) {
            throw new BizException(ResultCode.NOT_FOUND, "应用不存在或无权限修改");
        }
    }

    public void delete(Long tenantId, Long appId) {
        if (!appRepository.delete(tenantId, appId)) {
            throw new BizException(ResultCode.NOT_FOUND, "应用不存在或无权限删除");
        }
    }

    /** 应用当前绑定的知识库 ID 列表 */
    public List<Long> boundKnowledgeBases(Long tenantId, Long appId) {
        // 先确认应用归属，避免用别人的 appId 探测知识库绑定关系
        get(tenantId, appId);
        return appRepository.knowledgeBaseIds(tenantId, appId);
    }

    public void unbindKnowledgeBase(Long tenantId, Long appId, Long kbId) {
        get(tenantId, appId);
        appRepository.unbindKnowledgeBase(tenantId, appId, kbId);
    }

    /**
     * 取值校验。
     *
     * <p>策略/记忆策略是枚举语义的字符串，脏值不会立刻报错，
     * 而是在运行时静默退化成默认分支——所以必须在写入前拦住。
     */
    private void validate(App app) {
        if (app == null || !app.hasValidName()) {
            throw new BizException(ResultCode.PARAM_ERROR, "应用名称不能为空且不超过 128 字");
        }
        if (app.getAgentStrategy() != null
                && !VALID_STRATEGIES.contains(app.getAgentStrategy())) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "agentStrategy 只支持 " + VALID_STRATEGIES);
        }
        if (app.getMemoryPolicy() != null
                && !VALID_MEMORY.contains(app.getMemoryPolicy())) {
            throw new BizException(ResultCode.PARAM_ERROR,
                    "memoryPolicy 只支持 " + VALID_MEMORY);
        }
        Double temperature = app.getTemperature();
        if (temperature != null && (temperature < 0d || temperature > 2d)) {
            throw new BizException(ResultCode.PARAM_ERROR, "temperature 取值范围 0.00 ~ 2.00");
        }
    }
}
