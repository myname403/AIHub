-- V1 初始化：AI 服务（应用 / 模型 / 知识库 / 工具 / Agent / 会话 / 产物）
-- 所有业务表均以 tenant_id 作为隔离维度，且为复合索引首位。

CREATE TABLE IF NOT EXISTS ai_app (
    id              BIGINT        NOT NULL,
    tenant_id       BIGINT        NOT NULL,
    name            VARCHAR(128)  NOT NULL,
    system_prompt   TEXT          NULL,
    model_route_id  BIGINT        NULL,
    agent_strategy  VARCHAR(32)   NOT NULL DEFAULT 'none',
    memory_policy   VARCHAR(32)   NOT NULL DEFAULT 'window',
    temperature     DECIMAL(3,2)  NOT NULL DEFAULT 0.70,
    status          TINYINT       NOT NULL DEFAULT 1,
    create_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    KEY idx_tenant_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用(助手)';

CREATE TABLE IF NOT EXISTS ai_model_provider (
    id          BIGINT       NOT NULL,
    code        VARCHAR(64)  NOT NULL COMMENT 'openai/volcengine/dashscope/ollama',
    name        VARCHAR(64)  NOT NULL,
    base_url    VARCHAR(256) NULL,
    config_json JSON         NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型供应商定义(平台级)';

CREATE TABLE IF NOT EXISTS ai_model (
    id            BIGINT       NOT NULL,
    tenant_id     BIGINT       NOT NULL,
    provider_code VARCHAR(64)  NOT NULL,
    model_code    VARCHAR(64)  NOT NULL,
    api_key_enc   VARBINARY(512) NULL COMMENT '加密存储，禁止明文',
    base_url      VARCHAR(256) NULL,
    vector_dim    INT          NULL COMMENT '嵌入模型维度，强校验',
    is_default    TINYINT      NOT NULL DEFAULT 0,
    status        TINYINT      NOT NULL DEFAULT 1,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型实例';

CREATE TABLE IF NOT EXISTS ai_model_route (
    id                BIGINT      NOT NULL,
    tenant_id         BIGINT      NOT NULL,
    scene             VARCHAR(32) NOT NULL COMMENT 'chat/rag/embed/agent-plan',
    primary_model_id  BIGINT      NULL,
    fallback_model_id BIGINT      NULL,
    deleted           TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_scene (tenant_id, scene)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场景选模与降级';

CREATE TABLE IF NOT EXISTS ai_knowledge_base (
    id                 BIGINT       NOT NULL,
    tenant_id          BIGINT       NOT NULL,
    name               VARCHAR(128) NOT NULL,
    embedding_model_id BIGINT       NOT NULL,
    vector_dim         INT          NOT NULL,
    index_name         VARCHAR(128) NOT NULL COMMENT 'Redis 索引名(带 v{n} 版本)',
    status             TINYINT      NOT NULL DEFAULT 1,
    deleted            TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识库';

CREATE TABLE IF NOT EXISTS ai_document (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    kb_id       BIGINT       NOT NULL,
    name        VARCHAR(256) NOT NULL,
    file_type   VARCHAR(32)  NULL,
    size        BIGINT       NULL,
    file_path   VARCHAR(512) NULL,
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0待处理 1处理中 2完成 3失败',
    error_msg   VARCHAR(512) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_kb (tenant_id, kb_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档';

CREATE TABLE IF NOT EXISTS ai_document_chunk (
    id            BIGINT      NOT NULL,
    tenant_id     BIGINT      NOT NULL,
    kb_id         BIGINT      NOT NULL,
    doc_id        BIGINT      NOT NULL,
    seq           INT         NOT NULL,
    content       MEDIUMTEXT  NOT NULL,
    token_count   INT         NULL,
    vector_status TINYINT     NOT NULL DEFAULT 0 COMMENT '0待向量化 1已入库 2失败',
    metadata_json JSON        NULL,
    create_time   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_kb (tenant_id, kb_id),
    KEY idx_doc (doc_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分片';

CREATE TABLE IF NOT EXISTS ai_ingest_task (
    id          BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    kb_id       BIGINT      NOT NULL,
    doc_id      BIGINT      NOT NULL,
    status      TINYINT     NOT NULL DEFAULT 0,
    progress    INT         NOT NULL DEFAULT 0,
    error_msg   VARCHAR(512) NULL,
    started_at  DATETIME    NULL,
    finished_at DATETIME    NULL,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='知识入库任务';

CREATE TABLE IF NOT EXISTS ai_tool (
    id          BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(128) NOT NULL,
    source      VARCHAR(16) NOT NULL COMMENT 'local/http/mcp',
    config_json JSON        NULL,
    schema_json JSON        NULL,
    timeout_ms  INT         NOT NULL DEFAULT 30000,
    status      TINYINT     NOT NULL DEFAULT 1,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具定义';

CREATE TABLE IF NOT EXISTS ai_mcp_server (
    id        BIGINT       NOT NULL,
    tenant_id BIGINT       NOT NULL,
    name      VARCHAR(128) NOT NULL,
    transport VARCHAR(16)  NOT NULL COMMENT 'stdio/sse',
    command   VARCHAR(256) NULL,
    args_json JSON         NULL,
    url       VARCHAR(512) NULL,
    env_json  JSON         NULL,
    status    TINYINT      NOT NULL DEFAULT 1,
    deleted   TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MCP Server 连接';

CREATE TABLE IF NOT EXISTS ai_agent_task (
    id              BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    app_id          BIGINT      NULL,
    conversation_id BIGINT      NULL,
    goal            TEXT        NULL,
    strategy        VARCHAR(32) NULL,
    status          TINYINT     NOT NULL DEFAULT 0,
    current_step    INT         NOT NULL DEFAULT 0,
    budget_json     JSON        NULL,
    started_at      DATETIME    NULL,
    finished_at     DATETIME    NULL,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent任务';

CREATE TABLE IF NOT EXISTS ai_agent_step (
    id          BIGINT     NOT NULL,
    tenant_id   BIGINT     NOT NULL,
    task_id     BIGINT     NOT NULL,
    seq         INT        NOT NULL,
    type        VARCHAR(16) NOT NULL COMMENT 'think/act/observe',
    content_json JSON      NULL,
    cost_ms     BIGINT     NULL,
    PRIMARY KEY (id),
    KEY idx_task (task_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Agent执行步骤';

CREATE TABLE IF NOT EXISTS ai_conversation (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    user_id     BIGINT       NULL,
    app_id      BIGINT       NULL,
    title       VARCHAR(256) NULL,
    status      TINYINT      NOT NULL DEFAULT 1,
    last_msg_at DATETIME     NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话';

CREATE TABLE IF NOT EXISTS ai_message (
    id              BIGINT      NOT NULL,
    tenant_id       BIGINT      NOT NULL,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'user/assistant/tool',
    content         MEDIUMTEXT  NULL,
    token_in        INT         NULL,
    token_out       INT         NULL,
    model_code      VARCHAR(64) NULL,
    cost_ms         BIGINT      NULL,
    status          TINYINT     NOT NULL DEFAULT 1,
    create_time     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_conv (tenant_id, conversation_id),
    KEY idx_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='消息';

CREATE TABLE IF NOT EXISTS ai_artifact (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    message_id  BIGINT       NULL,
    task_id     BIGINT       NULL,
    name        VARCHAR(256) NOT NULL,
    file_path   VARCHAR(512) NOT NULL,
    mime        VARCHAR(64)  NULL,
    size        BIGINT       NULL,
    preview_url VARCHAR(512) NULL,
    deleted     TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='产物(表格/图表/HTML)';

CREATE TABLE IF NOT EXISTS ai_event_outbox (
    id           BIGINT      NOT NULL,
    tenant_id    BIGINT      NOT NULL,
    event_type   VARCHAR(64) NOT NULL,
    payload_json JSON        NOT NULL,
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0待投递 1已投递 2失败',
    retry_count  INT         NOT NULL DEFAULT 0,
    create_time  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME    NULL,
    PRIMARY KEY (id),
    KEY idx_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='领域事件发件箱';

-- 内置模型供应商
INSERT INTO ai_model_provider (id, code, name, base_url) VALUES
    (1, 'openai',     'OpenAI 兼容',   'https://api.openai.com'),
    (2, 'volcengine', '火山方舟 DeepSeek', 'https://ark.cn-beijing.volces.com/api/v3'),
    (3, 'dashscope',  '通义千问',       'https://dashscope.aliyuncs.com/compatible-mode/v1'),
    (4, 'ollama',     '本地 Ollama',    'http://localhost:11434')
    ON DUPLICATE KEY UPDATE name = VALUES(name);
