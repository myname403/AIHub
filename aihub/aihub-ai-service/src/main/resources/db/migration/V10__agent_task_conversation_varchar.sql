-- V10：修复 Agent 任务表会话 ID 类型
-- 背景：ai_agent_task.conversation_id 建表时是 BIGINT（V1），但会话 ID 实际是
-- 16 位十六进制字符串（如 a3f2c1e09b8d4a7f），写入直接报
-- "Out of range value / Incorrect integer value"，导致 Agent 任务永远无法落库。
-- V6/V9 已把 ai_message.conversation_id 修正为 VARCHAR，本迁移补齐遗漏的 ai_agent_task。

ALTER TABLE ai_agent_task
    MODIFY COLUMN conversation_id VARCHAR(64) NULL COMMENT '会话ID（16位hex字符串）';
