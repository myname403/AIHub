package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiDocumentChunkDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_document_chunk 表 Mapper —— 文档切片（RAG 向量化与检索的最小单元）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiDocumentChunkMapper extends BaseMapper<AiDocumentChunkDO> {
}
