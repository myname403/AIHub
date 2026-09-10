-- V5：Agent 任务与步骤字段扩展（M4 · 可追溯、可回放、可审计）
-- 在 V1 建表基础上补齐执行结果字段，ALTER 均为增量，不影响既有数据。

ALTER TABLE ai_agent_task
    ADD COLUMN user_id     BIGINT    NULL COMMENT '发起用户',
    ADD COLUMN used_tokens BIGINT    NULL COMMENT '累计消耗 Token（三重预算之一）',
    ADD COLUMN cost_ms     BIGINT    NULL COMMENT '总耗时（毫秒）',
    ADD COLUMN answer      MEDIUMTEXT NULL COMMENT '最终汇总回答';

ALTER TABLE ai_agent_step
    ADD COLUMN agent_name VARCHAR(64) NULL COMMENT '执行该步骤的 Agent';
