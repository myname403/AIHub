package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiAppKbDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_app_kb 表 Mapper —— 应用 ↔ 知识库 绑定关系（多对多桥表）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiAppKbMapper extends BaseMapper<AiAppKbDO> {
}
