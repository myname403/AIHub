package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiModelDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_model 表 Mapper —— 模型定义（模型代码、参数、所属服务商）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiModelMapper extends BaseMapper<AiModelDO> {
}
