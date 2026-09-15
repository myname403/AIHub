-- 修复种子管理员密码哈希与注释不一致的问题。
-- 背景：V1__init_platform.sql 注释声称密码为 admin123，但其中硬编码的 BCrypt 哈希
--       实际对应的是 123456（生成脚本贴错哈希），导致按文档登录必然报
--       "用户名或密码错误"。
-- 处理：不动 V1（Flyway 校验已应用脚本的 checksum，改历史脚本会导致启动失败），
--       用本增量迁移把 admin 的哈希更新为 admin123 的正确 BCrypt 值。
-- 安全提示：演示环境的默认口令仅限本地开发，部署前务必修改。
UPDATE sys_user
SET password_hash = '$2a$10$4a6Yl4qwbtuOs8vD5j0yZ.lgnSSeh1jKyROA116dG366EUc7Bf5gG'
WHERE id = 1 AND username = 'admin';
