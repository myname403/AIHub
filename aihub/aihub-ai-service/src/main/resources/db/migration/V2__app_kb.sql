-- V2：应用-知识库绑定（RAG 生效前提）
CREATE TABLE IF NOT EXISTS ai_app_kb (
    id        BIGINT  NOT NULL,
    tenant_id BIGINT  NOT NULL,
    app_id    BIGINT  NOT NULL,
    kb_id     BIGINT  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_kb (tenant_id, app_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用-知识库绑定';
