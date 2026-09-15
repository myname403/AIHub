-- 修复 ai_knowledge_base 表缺失 create_time 列的问题。
-- 背景：V1 建表时遗漏 create_time，而 AiKnowledgeBaseDO 含 createTime 字段
--       （DbKnowledgeBaseRepository.create() 还会显式写入），导致知识库列表
--       （GET /api/ai/kb）与新建知识库接口全部因
--       "Unknown column 'create_time' in 'field list'" 报 500。
ALTER TABLE ai_knowledge_base
    ADD COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP AFTER deleted;
