package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiAgentTaskDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_agent_task 表 Mapper —— Agent 任务主记录（状态、预算、进度）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiAgentTaskMapper extends BaseMapper<AiAgentTaskDO> {
}
