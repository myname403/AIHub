package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiModelRouteDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_model_route 表 Mapper —— 场景 → 模型 路由（租户/应用级缺省模型）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiModelRouteMapper extends BaseMapper<AiModelRouteDO> {
}
