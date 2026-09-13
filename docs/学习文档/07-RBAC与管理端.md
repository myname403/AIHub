# 07 · RBAC 与管理端 —— 学习文档（第 1 期新增功能）

> 本期交付：租户自助注册 + 芋道完整版 RBAC（角色/菜单/按钮权限）+ Vue3 独立管理端（pure-admin-thin）+ uni-app 注册页
> 新增代码均有零基础友好注释，建议对照源码阅读。

---

## 1. 功能全景

```
新用户 ──注册(uni-app)──→ /auth/register ──→ 创建 租户+管理员+ROLE_ADMIN(全菜单授权)
   │
   ├─ 登录 uni-app（5173）：demo/admin/123456 或新注册的租户
   │
管理员 ──登录管理端(8849)──→ pure-admin-thin
   │                           ├── 用户管理：增删子用户/重置密码/分配角色
   │                           ├── 角色管理：CRUD + 菜单授权（树勾选）
   │                           ├── 菜单管理：维护菜单树（M/C/F）
   │                           ├── API Key / 模型管理 / 应用 / 知识库 / 用量
子用户 ──登录──→ 只见被授权的菜单和按钮（权限码集合控制）
```

## 2. RBAC 数据模型（芋道同款思想）

| 表 | 租户隔离 | 说明 |
|---|---|---|
| sys_role | ✅ tenant_id | 角色是租户内概念，注册自动建 ROLE_ADMIN |
| sys_menu | ❌ 全局 | 菜单/权限标识平台统一定义，租户只"授权"不"定义" |
| sys_user_role | ✅ | 用户↔角色（多对多，权限取并集）|
| sys_role_menu | ❌（经 role 间接隔离）| 角色↔菜单授权 |

**菜单三类型**：M 目录（分组）/ C 菜单（页面，permission 为 xxx:query）/ F 按钮（permission 如 system:user:create）。

**权限解析链路**（`PermissionService`）：
```
用户 → sys_user_role → 角色(启用) → sys_role_menu → sys_menu.permission 集合
```
Caffeine 缓存 10 分钟；授权变更时 `invalidateRole/invalidateUser` 主动失效。

## 3. 关键机制讲解

### 3.1 注册事务（AuthService.register）
`@Transactional` 保证 5 张表同生共死。顺序：验证码 → 格式校验 → 唯一性 → 建租户 → 建管理员(BCrypt) → 建 ROLE_ADMIN → 授权全部菜单 → 绑角色。
`demo` 是保留码防抢注。验证码：Hutool 生成 + Caffeine 2 分钟过期 + 一次性消费（防重放）；`aihub.auth.captcha-enabled=false` 可关（测试用）。

### 3.2 权限校验（@RequirePermission + PermissionInterceptor）
```java
@RequirePermission("system:user:create")   // 标在 Controller 方法
@PostMapping public R<Long> create(...) {}
```
拦截器在进 Controller 前反射读取注解 → `PermissionService.hasPermission(tenantId, userId, 标识)`。
**执行顺序**：TraceId(0) → Tenant(1) → Permission(2)（权限依赖租户上下文）。
未标注解的接口默认只要求登录态（芋道同款宽松策略）。

### 3.3 管理端动态路由
后端 `/api/platform/system/permission/info` 返回 `{routes: 菜单树, permissions: 权限码}`；
前端 `api/routes.ts` 把 MenuNode 转成 pure-admin 路由（component 字符串 → 自动映射 `src/views/<component>.vue`），
登录后 `initRouter()` 动态注册 —— **不同角色登录看到不同菜单**就是这么实现的。

### 3.4 安全红线（本期再次强化）
- 越权防御（IDOR）：`mustGetOwn()` 按 ID 操作前必查 tenant_id 归属；
- 防自锁：不能停用/删除自己；
- 角色编码创建后不可改（代码引用它）；菜单类型创建后不可改；
- 子用户登录权限码为空 `[]` = 什么都看不到（已实测验证）。

## 4. 三个真实 bug 的修复过程（学习价值最高）

| Bug | 现象 | 教训 |
|---|---|---|
| sys_user_role 缺 create_time 列 | V1 占位建表没这列，DO 有字段 → Unknown column | Flyway 迁移用 V8 补列（不改已应用的 V7）|
| resolveTenantId 还是 M0 占位 | 注册的 testco 登录 → NumberFormatException→50000 | TODO 别拖：功能上线前必须还技术债 |
| common 旧包被 spring-boot:run 使用 | 找不到 requireUserId | 改了 common 必须重新 `mvn install` |

另外顺手修复（历史遗留）：两个业务服务加 `scanBasePackages="com.aihub"`，让 `GlobalExceptionHandler` 生效——业务异常现在返回统一 R 格式而非原生 500。

## 5. 启动与访问

| 入口 | 地址 | 说明 |
|---|---|---|
| uni-app（用户端） | http://localhost:5173 | 登录页有"注册新企业"入口 |
| 管理端 | http://localhost:8849 | pure-admin-thin，租户码+账号登录 |
| 管理端启动 | `corepack pnpm@9.15.9 dev`（Node 用 nvm 的 v24.14.0，VITE_PORT=8849）| dev 代理 /auth、/api → 8080 网关 |
| Swagger | http://localhost:8081/swagger-ui.html | 新接口在 system 标签下 |

**验收流程**：uni-app 注册 → 新租户登录 → 管理端登录（同一账号）→ 用户管理加子用户 → 角色管理建角色并授权部分菜单 → 子用户绑定该角色 → 子用户登录管理端只见被授权菜单。

## 6. 注意事项与常见坑

- 改了 `aihub-common` → 必须 `mvn -pl aihub-common install` 再跑服务；
- IDEA 启动**删除手填的环境变量**（yml 已带本地默认值，env 优先级更高）；
- 管理端 8848 与 Nacos 控制台冲突，已改 8849；
- 本地 3306 有你自己的 MySQL，项目默认连 Docker 的 3307；
- 子用户没绑角色 = 权限码空 = 看不到任何菜单（不是 bug）；
- 权限缓存 10 分钟：改了授权没生效时，等缓存过期或重启（后续可加手动刷新接口）。

## 7.5 模型切换与本地模型实战（补充）

### 模型切换链路
- 前端 chat 页模型选择器 → 请求带可选 `modelCode` → `ChatController.ChatRequest` → `ChatTurn.modelCode`
- `ChatClientFactory.create(tenantId, appId, scene, modelCode)`：指定模型时**绕过场景路由**直接按编码解析端点；
  缓存 key 编入 modelCode（`tenant:app:scene:modelCode`），不同模型的 Client 互不污染
- modelCode 为空 = 走管理端配置的 `ai_model_route` 场景路由（管理员定默认、用户可覆盖，两层并存）

### 排查实录①：对话永远"思考中"（两个叠加 bug）
1. **推理模型 content 为空**：qwen3.5 的回答输出在 `reasoning` 字段、`content` 恒为空，Spring AI 读不到 →
   修复：Ollama 供应商自动在系统提示词追加 `/no_think`（Qwen3 系官方软开关）。注意 Ollama 的
   OpenAI 兼容端点会忽略根级 `think:false` 参数，软开关是最可靠方案
2. **RAG 检索无限阻塞**：绑定过知识库的应用（种子应用 9001），检索前要先调**嵌入模型**向量化；
   未配置嵌入模型时 Spring AI 默认连 api.openai.com → 网络不通无限阻塞 →
   修复双管齐下：① `VectorStoreConfig` 显式配置 Ollama 嵌入模型（`aihub.embedding.*`，embeddinggemma 768 维）；
   ② 检索加 3 秒超时（`CompletableFuture.get(3, SECONDS)`），超时降级普通对话——**可选能力绝不能拖死主链路**

**方法论沉淀**：这类问题的定位手法是**线程转储**（jstack）——卡住时抓堆栈，线程停在哪一行，问题就在哪。
本次就是 jstack 直接指向 `RedisKnowledgeRetriever.retrieve` 才锁定的。

### 索引维度注意
Redis 向量索引维度 = 嵌入模型维度（embeddinggemma=768，OpenAI 默认 1536）。**换嵌入模型必须删旧索引**
（`FT.DROPINDEX spring-ai-index`，应用启动自动按新维度重建），否则向量写入/检索报维度不匹配。

## 7. 第 2 期实施记录（进行中）

### 已完成
- **阶段 1 ✅**：登录验证码（双端 UI + 后端强制，`aihub.auth.captcha-enabled` 总开关）；demo 租户 RBAC 补种（V9，admin 恢复全部权限）。
  澄清：ai_message 的会话存储用的是 `conv_key` 列（设计如此），旧 `conversation_id` 列是遗留（V9 已修为 VARCHAR）。
- **服务部署形态升级 ✅**：全部服务改为 `java -jar` 独立进程运行（脱离 IDEA），一键脚本
  `aihub/start-services.bat`（清理旧端口→依次启动→健康检查）；日志在 `aihub/logs/`。

### 阶段 2 MCP 接入（基础设施 ✅，工具调用质量受限）
- 已接入 3 个本地 MCP Server（`aihub-ai-service` yml 的 `spring.ai.mcp.client.stdio.connections`）：
  - `filesystem`：文件读写，工作区限定 **E:\aihub-workspace**（目录外访问被服务端拒绝）
  - `sequential-thinking`：结构化推理
  - `web-fetch`：网页抓取（@kazuph/mcp-fetch）
- **Windows 三条血泪经验**（都是实测踩出来的）：
  1. 不要用 `cmd /c npx` 包装——stdio 握手超时；直接 `node.exe <包入口.js>`（npx 缓存路径是哈希、不可靠）；
     包统一装在 `E:\aihub\mcp-servers`（**必须写入 package.json 依赖**，`npm install --no-save` 会裁掉旧包）
  2. MCP 协议版本要匹配：Java SDK 0.17 只认 2024-11-05；`@mokei/mcp-fetch` 返回 2025-11-25 会被拒，
     换 `@kazuph/mcp-fetch`（2024-11-05）通过
  3. Node 用绝对路径（系统 PATH 是 Node16）
- **验证结论**：连接 ✓ / 工具暴露 ✓ / 模型确实调用工具 ✓ / 工具错误正确返回模型 ✓（日志见
  `SyncMcpToolCallback - Error calling tool: ENOENT...`）。
  **当前限制**：qwen3.5:0.8b 太小，工具调用会编造路径并反复重试导致请求超时——
  工具调用场景建议搭配更强模型（DeepSeek 等，配好即用）。

## 8. 学习自测

1. 画出 RBAC 四表关系与权限解析链路；为什么 sys_menu 不带 tenant_id？
2. M/C/F 三类菜单各自的 permission 约定？前端怎么用 F 的权限码？
3. `@RequirePermission` 的生效机制？没有注解的接口是什么策略？
4. 注册接口为什么要 @Transactional？哪一步失败会造成什么脏数据？
5. 管理端动态路由的实现原理？component 字符串如何映射到 .vue 文件？
6. 验证码为什么要"校验后立即删除"？captcha-enabled 开关的适用场景？
7. 三个真实 bug 各给你什么教训？

能全部答上来，第 1 期毕业 ✅
