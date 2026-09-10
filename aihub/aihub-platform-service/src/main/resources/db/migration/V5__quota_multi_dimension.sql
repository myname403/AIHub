-- V5：配额多维度补齐（M5）
-- 背景：V4 只种了 request 维度，而 ai_quota_policy.dimension 的注释早已声明
-- request/token/doc/task 四种。此处补齐其余三维度的演示策略，
-- 并明确「文档入库按 doc 维度扣、Agent 任务按 task 维度扣、模型调用按 token 维度扣」。

INSERT INTO ai_quota_policy (id, tenant_id, dimension, period, limit_value) VALUES
    -- token：每日 200 万 / 每月 3000 万（按 token_in + token_out 计）
    (9003, 1001, 'token', 'day', 2000000),
    (9004, 1001, 'token', 'month', 30000000),
    -- doc：每月 500 个文档入库（入库是重操作，按天限制过严，只设月度）
    (9005, 1001, 'doc', 'month', 500),
    -- task：每日 200 个 Agent 任务
    (9006, 1001, 'task', 'day', 200)
ON DUPLICATE KEY UPDATE limit_value = VALUES(limit_value);

-- 用量表补维度说明索引（按维度+周期查用量是热路径）
ALTER TABLE ai_quota_usage
    ADD INDEX idx_tenant_dim_period (tenant_id, dimension, period_key);
