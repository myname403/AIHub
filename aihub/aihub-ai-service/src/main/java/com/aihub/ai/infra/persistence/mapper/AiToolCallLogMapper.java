package com.aihub.ai.infra.persistence.mapper;

import com.aihub.ai.infra.persistence.dataobject.AiToolCallLogDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * ai_tool_call_log 表 Mapper —— 工具调用审计日志（配合 AuditedToolCallback 写入）。
 *
 * <p>空接口继承 {@link BaseMapper}，单表 CRUD 由 MyBatis-Plus 运行时
 * 动态代理提供（@MapperScan 注册，见 AiServiceApplication 说明）。
 */
public interface AiToolCallLogMapper extends BaseMapper<AiToolCallLogDO> {
}
