-- V4：演示租户配额策略（请求维度：每日 1000 次 / 每月 20000 次）
INSERT INTO ai_quota_policy (id, tenant_id, dimension, period, limit_value) VALUES
    (9001, 1001, 'request', 'day', 1000),
    (9002, 1001, 'request', 'month', 20000)
ON DUPLICATE KEY UPDATE limit_value = VALUES(limit_value);
