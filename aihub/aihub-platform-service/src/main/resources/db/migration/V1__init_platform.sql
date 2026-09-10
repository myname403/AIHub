-- V1 初始化：平台服务（租户 / 用户 / 角色 / API Key）
-- 所有业务表均以 tenant_id 作为隔离维度，且为复合索引首位。

CREATE TABLE IF NOT EXISTS sys_tenant (
    id           BIGINT       NOT NULL COMMENT '租户ID',
    name         VARCHAR(64)  NOT NULL COMMENT '租户名称',
    code         VARCHAR(64)  NOT NULL COMMENT '租户编码（登录时指定）',
    status       TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    expire_at    DATETIME     NULL,
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted      TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='租户';

CREATE TABLE IF NOT EXISTS sys_user (
    id            BIGINT       NOT NULL COMMENT '用户ID',
    tenant_id     BIGINT       NOT NULL COMMENT '租户ID',
    username      VARCHAR(64)  NOT NULL COMMENT '用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname      VARCHAR(64)  NULL,
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id),
    UNIQUE KEY uk_tenant_username (tenant_id, username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

CREATE TABLE IF NOT EXISTS sys_role (
    id         BIGINT      NOT NULL,
    tenant_id  BIGINT      NOT NULL,
    code       VARCHAR(64) NOT NULL COMMENT '角色编码',
    name       VARCHAR(64) NOT NULL,
    deleted    TINYINT     NOT NULL DEFAULT 0,
    create_time DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色';

CREATE TABLE IF NOT EXISTS sys_user_role (
    id        BIGINT  NOT NULL,
    tenant_id BIGINT  NOT NULL,
    user_id   BIGINT  NOT NULL,
    role_id   BIGINT  NOT NULL,
    deleted   TINYINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant_user (tenant_id, user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色';

CREATE TABLE IF NOT EXISTS sys_api_key (
    id         BIGINT       NOT NULL,
    tenant_id  BIGINT       NOT NULL,
    app_id     BIGINT       NULL COMMENT '绑定的应用（AI 服务侧）',
    name       VARCHAR(64)  NOT NULL,
    key_hash   VARCHAR(128) NOT NULL COMMENT '密钥哈希，禁止明文存储',
    status     TINYINT      NOT NULL DEFAULT 1,
    expire_at  DATETIME     NULL,
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='开放API密钥';

CREATE TABLE IF NOT EXISTS ai_quota_policy (
    id          BIGINT      NOT NULL,
    tenant_id   BIGINT      NOT NULL,
    dimension   VARCHAR(32) NOT NULL COMMENT 'request/token/doc/task',
    period      VARCHAR(16) NOT NULL COMMENT 'day/month',
    limit_value BIGINT      NOT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_tenant (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配额策略';

CREATE TABLE IF NOT EXISTS ai_quota_usage (
    id         BIGINT      NOT NULL,
    tenant_id  BIGINT      NOT NULL,
    app_id     BIGINT      NULL,
    dimension  VARCHAR(32) NOT NULL,
    period_key VARCHAR(16) NOT NULL COMMENT '如 2026-09',
    used_value BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_usage (tenant_id, app_id, dimension, period_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='配额用量';

CREATE TABLE IF NOT EXISTS ai_usage_record (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    app_id      BIGINT       NULL,
    user_id     BIGINT       NULL,
    model_code  VARCHAR(64)  NULL,
    token_in    INT          NULL,
    token_out   INT          NULL,
    cost_ms     BIGINT       NULL,
    cost_amount DECIMAL(12,4) NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用用量明细';

CREATE TABLE IF NOT EXISTS ai_audit_log (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    user_id     BIGINT       NULL,
    action      VARCHAR(64)  NOT NULL,
    target_type VARCHAR(64)  NULL,
    target_id   VARCHAR(64)  NULL,
    detail_json JSON         NULL,
    ip          VARCHAR(64)  NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='审计日志';

-- 初始化演示租户与用户（密码： admin123）
INSERT INTO sys_tenant (id, name, code, status) VALUES (1001, '演示租户', 'demo', 1)
    ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_user (id, tenant_id, username, password_hash, nickname, status)
VALUES (1, 1001, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员', 1)
    ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);
