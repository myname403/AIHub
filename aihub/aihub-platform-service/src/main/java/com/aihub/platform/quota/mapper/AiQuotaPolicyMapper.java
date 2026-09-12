package com.aihub.platform.quota.mapper;

import com.aihub.platform.quota.entity.AiQuotaPolicyDO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 配额策略 Mapper —— 空接口，查询策略用 MP 的 selectList + lambdaQuery 条件
 * （见 QuotaService.sortedPolicies），无需手写 SQL。
 * 详见学习文档《04-平台服务-aihub-platform-service.md》。
 */
public interface AiQuotaPolicyMapper extends BaseMapper<AiQuotaPolicyDO> {
}
