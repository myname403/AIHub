package com.aihub.ai.application;

import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.domain.model.ModelProvider;
import com.aihub.ai.domain.model.ModelRouteInfo;
import com.aihub.ai.domain.spi.ModelAdminRepository;
import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型管理服务（管理端）。
 *
 * <p>只依赖 domain 与 common（ArchUnit 卡口）。加密与落库都在 infra 实现里，
 * 因此这里拿到与传出的都不含密文——{@link ModelCommand#toString()} 也已遮掉明文。
 */
@Service
@RequiredArgsConstructor
public class ModelAdminService {

    /** 与 ai_model_route.scene 的取值保持一致 */
    private static final Set<String> VALID_SCENES = Set.of("chat", "rag", "embed", "agent-plan");

    private final ModelAdminRepository modelAdminRepository;

    public List<ModelProvider> providers() {
        return modelAdminRepository.listProviders();
    }

    public List<ModelInfo> models(Long tenantId) {
        return modelAdminRepository.listModels(tenantId);
    }

    public Long createModel(Long tenantId, ModelCommand command) {
        validate(command);
        Long modelId = modelAdminRepository.createModel(tenantId, command);
        // 新建即设为默认时，把旧的默认清掉，保证「默认模型」唯一
        if (command.defaultModel()) {
            modelAdminRepository.clearDefaultFlag(tenantId, modelId);
        }
        return modelId;
    }

    /**
     * 更新模型。
     *
     * <p>{@code apiKey} 留空表示不改动已有密钥——管理端拿不到明文，
     * 因此不能要求它每次回填，否则「编辑温度」这个动作会把密钥抹掉。
     */
    public void updateModel(Long tenantId, Long modelId, ModelCommand command) {
        validate(command);
        if (!modelAdminRepository.updateModel(tenantId, modelId, command)) {
            throw new BizException(ResultCode.NOT_FOUND, "模型不存在或无权限修改");
        }
        if (command.defaultModel()) {
            modelAdminRepository.clearDefaultFlag(tenantId, modelId);
        }
    }

    public void deleteModel(Long tenantId, Long modelId) {
        if (!modelAdminRepository.deleteModel(tenantId, modelId)) {
            throw new BizException(ResultCode.NOT_FOUND, "模型不存在或无权限删除");
        }
    }

    public List<ModelRouteInfo> routes(Long tenantId) {
        return modelAdminRepository.listRoutes(tenantId);
    }

    /** 保存场景路由（scene 在租户内唯一，存在则覆盖） */
    public void saveRoute(Long tenantId, String scene, Long primaryModelId, Long fallbackModelId) {
        if (scene == null || !VALID_SCENES.contains(scene)) {
            throw new BizException(ResultCode.PARAM_ERROR, "scene 只支持 " + VALID_SCENES);
        }
        if (primaryModelId == null && fallbackModelId == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "主模型与备用模型不能同时为空");
        }
        // 归属校验放在这里而不是仓储：否则可以把路由指向别的租户的模型，
        // 运行时 findRoute 取到 null，表现为「路由配了但不生效」的隐蔽故障。
        Set<Long> owned = modelAdminRepository.listModels(tenantId).stream()
                .map(ModelInfo::id).collect(Collectors.toSet());
        requireOwned(owned, primaryModelId, "主模型");
        requireOwned(owned, fallbackModelId, "备用模型");
        modelAdminRepository.saveRoute(tenantId, scene, primaryModelId, fallbackModelId);
    }

    private void requireOwned(Set<Long> owned, Long modelId, String label) {
        if (modelId != null && !owned.contains(modelId)) {
            throw new BizException(ResultCode.PARAM_ERROR, label + " " + modelId + " 不属于当前租户");
        }
    }

    /* ---------------- 校验 ---------------- */

    private void validate(ModelCommand command) {
        if (command == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "请求体不能为空");
        }
        if (command.providerCode() == null || command.providerCode().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "providerCode 不能为空");
        }
        Set<String> known = modelAdminRepository.listProviders().stream()
                .map(ModelProvider::code).collect(Collectors.toSet());
        if (!known.isEmpty() && !known.contains(command.providerCode())) {
            // 未知 provider 会静默退化成非 OpenAI 兼容分支，模型直接调不通，必须提前拦住
            throw new BizException(ResultCode.PARAM_ERROR,
                    "未知的 providerCode：" + command.providerCode() + "，可选 " + known);
        }
        if (command.modelCode() == null || command.modelCode().isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "modelCode 不能为空");
        }
        // 新建时不强制要求 Key：本地 ollama 等无需鉴权，走空 Key 分支即可；
        // 修改时不传 Key 则沿用原值。
        Integer dim = command.vectorDim();
        if (dim != null && dim <= 0) {
            // 维度写错会导致向量库索引建得出来但检索永远为空，代价极高
            throw new BizException(ResultCode.PARAM_ERROR, "vectorDim 必须为正整数");
        }
        Integer status = command.status();
        if (status != null && status != 0 && status != 1) {
            throw new BizException(ResultCode.PARAM_ERROR, "status 只能是 0（停用）或 1（启用）");
        }
    }
}
