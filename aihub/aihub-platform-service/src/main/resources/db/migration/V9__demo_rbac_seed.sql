-- V9（platform）：demo 租户（1001）RBAC 补种。
-- 背景：demo 租户建 tenant/admin 时 RBAC 体系还不存在，admin 用户没有任何角色，
-- 登录管理端看不到任何菜单、接口全部 403。补种：建 ROLE_ADMIN → 授权全部菜单 → 绑定 admin。

INSERT INTO sys_role (id, tenant_id, code, name, sort, status, remark)
VALUES (100100, 1001, 'ROLE_ADMIN', '管理员', 1, 1, '历史租户补种的管理员角色');

-- 授权全部菜单（ID 偏移段 9 亿，避免与雪花 ID 冲突）
INSERT INTO sys_role_menu (id, role_id, menu_id)
SELECT 900000000000000000 + m.id, 100100, m.id FROM sys_menu m;

-- 绑定 admin（sys_user.id = 1）
INSERT INTO sys_user_role (id, tenant_id, user_id, role_id)
VALUES (900100, 1001, 1, 100100);
