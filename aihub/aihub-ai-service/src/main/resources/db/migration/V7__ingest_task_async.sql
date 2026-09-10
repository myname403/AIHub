-- V7：知识入库任务异步化（M2）
-- 背景：ai_ingest_task 表在 V1 已建好但未接入，文档入库此前是同步阻塞（大文件会拖垮请求线程）。
-- 本次把它用起来：入库改为异步任务 + 进度上报 + 失败重试；此处补齐重试与阶段所需的列。

ALTER TABLE ai_ingest_task
    ADD COLUMN stage        VARCHAR(32)  NULL COMMENT '当前阶段：parse/split/save/vector',
    ADD COLUMN retry_count  INT          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    ADD COLUMN chunk_total  INT          NULL COMMENT '分片总数',
    ADD COLUMN chunk_done   INT          NULL COMMENT '已处理分片数';

-- 同一文档只保留一条任务记录，重试时原地更新
ALTER TABLE ai_ingest_task
    ADD INDEX idx_tenant_doc (tenant_id, doc_id);

-- 文档表补充分片统计，前端列表可直接展示
ALTER TABLE ai_document
    ADD COLUMN chunk_count INT NULL COMMENT '分片数量';
