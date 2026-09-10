-- V4：工具调用审计日志（M3 · 工具与 MCP）
-- 记录每一次工具调用的入参、出参、耗时与结果，用于审计、排障与配额统计。

CREATE TABLE IF NOT EXISTS ai_tool_call_log (
    id          BIGINT       NOT NULL,
    tenant_id   BIGINT       NOT NULL,
    app_id      BIGINT       NULL,
    source      VARCHAR(16)  NOT NULL DEFAULT 'local' COMMENT 'local/mcp/http',
    tool_name   VARCHAR(128) NOT NULL,
    input_json  TEXT         NULL COMMENT '入参（脱敏后，超长截断）',
    output_json TEXT         NULL COMMENT '出参（脱敏后，超长截断）',
    success     TINYINT      NOT NULL DEFAULT 1,
    error_msg   VARCHAR(512) NULL,
    cost_ms     BIGINT       NULL,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tenant_time (tenant_id, create_time),
    KEY idx_tenant_tool (tenant_id, tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工具调用日志';
