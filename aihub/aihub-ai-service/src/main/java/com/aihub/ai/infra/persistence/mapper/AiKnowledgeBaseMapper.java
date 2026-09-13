package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiKnowledgeBaseDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_knowledge_base 表 Mapper —— 知识库。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiKnowledgeBaseMapper extends BaseMapper<AiKnowledgeBaseDO> {
}
