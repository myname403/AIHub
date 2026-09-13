package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiDocumentDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_document 表 Mapper —— 知识库内文档。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiDocumentMapper extends BaseMapper<AiDocumentDO> {
}
