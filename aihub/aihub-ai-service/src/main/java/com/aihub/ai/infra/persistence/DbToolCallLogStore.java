package com.aihub.ai.infra.persistence;

import com.aihub.ai.domain.spi.ToolCallLogStore;
import com.aihub.ai.infra.persistence.dataobject.AiToolCallLogDO;
import com.aihub.ai.infra.persistence.mapper.AiToolCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 工具调用日志的持久化实现。
 *
 * <p>写失败只记日志不影响主流程——工具调用审计是旁路能力，
 * 不能因为审计表不可用就让模型调用失败。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DbToolCallLogStore implements ToolCallLogStore {

    /** 入参 / 出参落库的最大长度，避免大文件与大段 HTML 打爆日志表 */
    private static final int MAX_LEN = 4000;

    private final AiToolCallLogMapper mapper;

    @Override
    public void record(ToolCallLog entry) {
        if (entry == null) {
            return;
        }
        try {
            AiToolCallLogDO entity = new AiToolCallLogDO();
            entity.setTenantId(entry.tenantId());
            entity.setAppId(entry.appId());
            entity.setSource(entry.source() == null ? "local" : entry.source());
            entity.setToolName(entry.toolName());
            entity.setInputJson(truncate(entry.inputJson()));
            entity.setOutputJson(truncate(entry.outputJson()));
            entity.setSuccess(entry.success() ? 1 : 0);
            entity.setErrorMsg(truncate(entry.errorMsg()));
            entity.setCostMs(entry.costMs());
            entity.setCreateTime(LocalDateTime.now());
            mapper.insert(entity);
        } catch (Exception e) {
            log.warn("工具调用日志落库失败 tool={} err={}", entry.toolName(), e.getMessage());
        }
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_LEN ? value : value.substring(0, MAX_LEN) + "…(truncated)";
    }
}
