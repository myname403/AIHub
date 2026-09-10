-- V6：会话键修正 + 引用落库（M2）
-- 背景：ai_message.conversation_id 是 BIGINT，而会话 ID 实际是字符串（如 a3f2c1e09b8d4a7f），
-- 此前适配层把非数字会话 ID 一律退化成 0，导致同一租户下所有会话的历史混在一起。
-- 修正：新增 conv_key 列保存真实会话 ID，历史与引用均按 conv_key 检索。

ALTER TABLE ai_message
    ADD COLUMN conv_key VARCHAR(64) NULL COMMENT '真实会话 ID（字符串）';

ALTER TABLE ai_message
    ADD INDEX idx_tenant_convkey (tenant_id, conv_key);

-- conversation_id 已不再承载真实会话 ID（新数据只写 conv_key），放宽为可空
ALTER TABLE ai_message
    MODIFY COLUMN conversation_id BIGINT NULL COMMENT '历史遗留数字会话 ID，新数据见 conv_key';

-- 回填：老数据统一挂到一个明确的键下，避免与新会话混淆
UPDATE ai_message SET conv_key = CONCAT('legacy-', conversation_id) WHERE conv_key IS NULL;

CREATE TABLE IF NOT EXISTS ai_message_reference (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    message_id  BIGINT       NOT NULL COMMENT '所属回答消息',
    conv_key    VARCHAR(64)  NULL,
    seq         INT          NOT NULL COMMENT '引用序号，对应正文中的 [n]',
    kb_id       BIGINT       NULL,
    doc_id      BIGINT       NULL,
    doc_name    VARCHAR(256) NULL,
    score       DOUBLE       NULL COMMENT '相似度',
    content     MEDIUMTEXT   NULL COMMENT '命中分片原文，便于回溯核对',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_message (message_id, seq),
    KEY idx_tenant_conv (tenant_id, conv_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回答引用来源';
