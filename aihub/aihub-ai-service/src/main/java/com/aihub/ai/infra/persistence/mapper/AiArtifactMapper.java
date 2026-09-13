package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiArtifactDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_artifact 表 Mapper —— 对话产物（Agent 生成的 HTML / 图表等文件记录）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiArtifactMapper extends BaseMapper<AiArtifactDO> {
}
