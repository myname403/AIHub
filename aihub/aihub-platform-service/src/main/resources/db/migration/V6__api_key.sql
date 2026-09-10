-- V6：开放 API Key 认证（M5）
-- 背景：sys_api_key 表在 V1 已建好但从未接入。对外提供能力（第三方系统调用
-- 对话/知识库接口）不能分发用户 JWT——JWT 携带用户身份与登录态，
-- 一旦泄露影响面远超单个集成方。
--
-- 设计要点：
-- 1) 只存哈希，不存明文：Key 明文仅在签发时返回一次，之后无法找回，只能重置；
-- 2) 前缀明文存库（key_prefix）便于运维在列表页辨认，且不足以反推完整 Key；
-- 3) 租户仍只从凭证解析——Key 映射到租户，绝不接受前端传 tenantId（安全红线 1）。

ALTER TABLE sys_api_key
    ADD COLUMN key_prefix   VARCHAR(16)  NULL COMMENT '明文前缀，便于辨识（如 ak_9f3c）',
    ADD COLUMN last_used_at DATETIME     NULL COMMENT '最近使用时间，用于识别僵尸 Key',
    ADD COLUMN used_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计调用次数';

-- 鉴权是每请求热路径：按哈希查必须走索引
ALTER TABLE sys_api_key
    ADD UNIQUE KEY uk_key_hash (key_hash),
    ADD INDEX idx_tenant_status (tenant_id, status);
