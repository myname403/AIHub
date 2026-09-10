package com.aihub.ai.domain.spi;

import com.aihub.ai.domain.model.ModelCommand;
import com.aihub.ai.domain.model.ModelInfo;
import com.aihub.ai.domain.model.ModelProvider;
import com.aihub.ai.domain.model.ModelRouteInfo;

import java.util.List;

/**
 * 模型管理仓储 SPI（管理端 CRUD）。
 *
 * <p>与 {@link ModelConfigRepository} 分开是刻意的：
 * <ul>
 *   <li>{@code ModelConfigRepository} 跑在对话热链路上，只读、只要 modelCode 级信息；</li>
 *   <li>本接口只在管理端被调用，会写库、需要更完整的字段。</li>
 * </ul>
 * 混在一起会让热路径的实现被迫依赖一堆管理端才需要的方法。
 *
 * <p><b>实现约定</b>：{@code ModelCommand.apiKey()} 传入的是明文，
 * 由 infra 实现负责加密落库；本接口的任何方法都不得返回明文或密文。
 */
public interface ModelAdminRepository {

    /** 供应商清单（平台级字典，供前端下拉框） */
    List<ModelProvider> listProviders();

    List<ModelInfo> listModels(Long tenantId);

    /** 新建模型，返回新 ID */
    Long createModel(Long tenantId, ModelCommand command);

    /** 按 (tenantId, modelId) 更新；Key 为空表示保持原值 */
    boolean updateModel(Long tenantId, Long modelId, ModelCommand command);

    /**
     * 把本租户除 {@code exceptModelId} 之外的默认标记清掉。
     *
     * <p>「默认模型」语义上必须唯一：留两个的话，{@code ai_model} 里
     * 哪条被取到取决于查询顺序，表现为「重启后默认模型自己变了」这类幽灵问题。
     */
    void clearDefaultFlag(Long tenantId, Long exceptModelId);

    /** 软删，并清理引用它的路由，避免留下悬空外键 */
    boolean deleteModel(Long tenantId, Long modelId);

    List<ModelRouteInfo> listRoutes(Long tenantId);

    /**
     * 保存场景路由（存在则更新，不存在则新建）。
     *
     * <p>路由必须校验主/备模型都属于本租户——否则可以指向别人的模型。
     */
    void saveRoute(Long tenantId, String scene, Long primaryModelId, Long fallbackModelId);
}
