package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiModelProviderDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_model_provider 表 Mapper —— 模型服务商配置（baseUrl、API Key 加密存储）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiModelProviderMapper extends BaseMapper<AiModelProviderDO> {
}
