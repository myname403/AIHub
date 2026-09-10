-- V8：补齐 DeepSeek 官方供应商。
--
-- 背景：com.aihub.ai.domain.model.ModelEndpoint.openAiCompatible() 已把 deepseek 视为
-- OpenAI 兼容协议族，但 ai_model_provider 字典里没有这条记录。
-- 结果是管理端下拉框选不到 deepseek，而运行时其实支持——两边不一致。
-- 这里把字典补齐，让「可选」与「可用」对齐。

INSERT INTO ai_model_provider (id, code, name, base_url) VALUES
    (5, 'deepseek', 'DeepSeek 官方', 'https://api.deepseek.com/v1')
    ON DUPLICATE KEY UPDATE name = VALUES(name), base_url = VALUES(base_url);
