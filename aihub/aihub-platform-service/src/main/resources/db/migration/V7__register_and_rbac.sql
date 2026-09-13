-- V7 注册与 RBAC：对齐芋道的权限模型（角色 / 菜单 / 用户角色 / 角色菜单）。
-- 设计要点：
--   1) sys_role 带 tenant_id —— 角色是"租户内"的概念，每个租户注册后自动获得 ROLE_ADMIN；
--   2) sys_menu 不带 tenant_id —— 菜单/权限标识是平台全局统一定义的，租户只做"授权"不做"定义"；
--   3) 按钮级权限用 menu_type=F 的记录 + permission 标识（如 system:user:create）表达，
--      与芋道的权限标识体系一致，前端按集合做按钮显隐，后端按集合做接口校验。

-- ---------- 1. 补全 V1 占位的 sys_role（M0 阶段只建了骨架列） ----------
ALTER TABLE sys_role
    ADD COLUMN sort       TINYINT      NOT NULL DEFAULT 0 COMMENT '显示顺序',
    ADD COLUMN status     TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    ADD COLUMN remark     VARCHAR(255) NULL COMMENT '备注',
    ADD COLUMN update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

-- ---------- 2. 菜单表（平台全局） ----------
CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGINT       NOT NULL COMMENT '菜单ID（用固定段位便于种子数据引用）',
    name        VARCHAR(64)  NOT NULL COMMENT '菜单名称',
    permission  VARCHAR(128) NULL COMMENT '权限标识（如 system:user:create），按钮必填',
    menu_type   CHAR(1)      NOT NULL COMMENT 'M目录 C菜单 F按钮',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID，0=根',
    path        VARCHAR(128) NULL COMMENT '前端路由路径',
    component   VARCHAR(128) NULL COMMENT '前端组件路径',
    icon        VARCHAR(64)  NULL COMMENT '图标',
    sort        INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    visible     TINYINT      NOT NULL DEFAULT 1 COMMENT '1显示 0隐藏',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    deleted     TINYINT      NOT NULL DEFAULT 0,
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单（平台全局）';

-- ---------- 3. 角色-菜单授权表 ----------
CREATE TABLE IF NOT EXISTS sys_role_menu (
    id          BIGINT  NOT NULL,
    role_id     BIGINT  NOT NULL,
    menu_id     BIGINT  NOT NULL,
    deleted     TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-菜单授权';

-- ---------- 4. 内置菜单树（固定 ID 便于 ROLE_ADMIN 全量授权） ----------
INSERT INTO sys_menu (id, name, permission, menu_type, parent_id, path, component, icon, sort) VALUES
-- 首页
(1,   '首页',       NULL,                'C', 0,    '/welcome',       'welcome/index',    'HomeFilled', 1),
-- AI 管理
(10,  'AI 管理',    NULL,                'M', 0,    '/ai',            NULL,               'MagicStick', 10),
(11,  '应用管理',   'ai:app:query',      'C', 10,   '/ai/app',        'ai/app/index',     'Files',      11),
(12,  '模型管理',   'ai:model:query',    'C', 10,   '/ai/model',      'ai/model/index',   'Cpu',        12),
(13,  '知识库管理', 'ai:kb:query',       'C', 10,   '/ai/kb',         'ai/kb/index',      'Collection', 13),
-- 系统管理
(20,  '系统管理',   NULL,                'M', 0,    '/system',        NULL,               'Setting',    20),
(21,  '用户管理',   'system:user:query', 'C', 20,   '/system/user',   'system/user/index','User',       21),
(211, '用户新增',   'system:user:create','F', 21,   NULL,             NULL,               NULL,         211),
(212, '用户修改',   'system:user:update','F', 21,   NULL,             NULL,               NULL,         212),
(213, '用户删除',   'system:user:delete','F', 21,   NULL,             NULL,               NULL,         213),
(214, '重置密码',   'system:user:reset-pwd','F', 21, NULL,             NULL,               NULL,         214),
(22,  '角色管理',   'system:role:query', 'C', 20,   '/system/role',   'system/role/index','UserFilled', 22),
(221, '角色新增',   'system:role:create','F', 22,   NULL,             NULL,               NULL,         221),
(222, '角色修改',   'system:role:update','F', 22,   NULL,             NULL,               NULL,         222),
(223, '角色删除',   'system:role:delete','F', 22,   NULL,             NULL,               NULL,         223),
(224, '菜单授权',   'system:role:assign-menu','F', 22, NULL,           NULL,               NULL,         224),
(23,  '菜单管理',   'system:menu:query', 'C', 20,   '/system/menu',   'system/menu/index','Menu',       23),
(231, '菜单新增',   'system:menu:create','F', 23,   NULL,             NULL,               NULL,         231),
(232, '菜单修改',   'system:menu:update','F', 23,   NULL,             NULL,               NULL,         232),
(233, '菜单删除',   'system:menu:delete','F', 23,   NULL,             NULL,               NULL,         233),
(24,  'API Key',    'system:apikey:query','C', 20,  '/system/apikey', 'system/apikey/index','Key',      24),
-- 用量统计
(30,  '用量统计',   'usage:query',       'C', 0,    '/usage',         'usage/index',      'TrendCharts', 30);
