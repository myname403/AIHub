-- V8 修复：sys_user_role 表在 V1 建表时没有 create_time 列，
-- 本期启用该表后（用户分配角色），DO 中的 createTime 字段导致查询报
-- Unknown column 'create_time'。补列对齐其他表的结构约定。
ALTER TABLE sys_user_role
    ADD COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;
