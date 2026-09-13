package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiIngestTaskDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_ingest_task 表 Mapper —— 文档入库异步任务（解析 → 切片 → 向量化）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiIngestTaskMapper extends BaseMapper<AiIngestTaskDO> {
}
