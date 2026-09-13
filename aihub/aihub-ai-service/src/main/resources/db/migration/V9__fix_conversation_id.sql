-- V9（ai 服务）：修复会话 ID 列类型。
-- ai_message.conversation_id 建表时是 BIGINT，但会话 ID 是 16 位十六进制字符串
-- （如 9b43b010cec948b3），字符串转数字失败导致所有消息的会话 ID 都存成了 NULL，
-- 历史回放 / 引用溯源（按会话查）功能实际是坏的。改为 VARCHAR(64)。
-- 注意：存量 NULL 无法恢复（数据已丢失），本迁移后新消息正常落库。

ALTER TABLE ai_message
    MODIFY conversation_id VARCHAR(64) NULL COMMENT '会话ID（16位hex字符串）';
