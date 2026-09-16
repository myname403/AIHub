# AIHub · 通用 AI 能力中台

多租户的 AI 能力中台：模型可换、知识可插、工具可注册、Agent 可编排，一切可按租户计量与审计。

设计文档见上级目录 `docs/`（01 总览 · 02 需求 · 03 架构 · 04 选型 · 05 数据库 · 06 里程碑 · **07 微服务与中间件**）。

---

## 1. 微服务划分

| 服务 | 端口 | 职责 |
| --- | --- | --- |
| `aihub-gateway` | 8080 | 路由、JWT 鉴权、Sentinel 限流 |
| `aihub-platform-service` | 8081 | 登录、租户、用户、角色、API Key、配额、审计 |
| `aihub-ai-service` | 8082 | **模型网关 / 应用 / RAG / 工具 / MCP / Agent / 会话 / 产物（内聚）** |

补充模块：`aihub-common`（通用支撑）、`aihub-api`（跨服务 Feign 契约）、
`aihub-mcp-service`（MCP 工具的业务实现，协议无关）、`aihub-mcp-sse`（SSE / Streamable-HTTP 对外服务）、
`aihub-mcp-stdio`（stdio 本地接入，可执行 jar）。

> **AI 能力刻意不拆分**：知识入库、流式输出、Agent 长任务一旦跨服务，会引入分布式事务、
> 流式跨服务传递、长任务编排三大难题。因此全部内聚在 `aihub-ai-service`。
> 跨服务调用只剩「租户校验 / 配额扣减 / 审计上报」三类无事务调用。

## 2. 版本对齐（严禁混用）

```
JDK 17
Spring Boot            3.5.x
Spring Cloud           2025.0.x
Spring Cloud Alibaba   2025.0.0.0   ← 2025.1.x 是给 Boot 4.0 的，不可用
Nacos Server           3.0.3
Sentinel               1.8.9
Spring AI              1.1.x
MySQL 8 / Redis Stack  7.x（必须 Stack，否则无向量能力）
```

## 3. 启动步骤

### 3.1 拉起依赖

```bash
docker compose up -d
docker compose ps      # 等全部 healthy
```

- Nacos 控制台：<http://127.0.0.1:8848/index.html>
- Sentinel 控制台：<http://127.0.0.1:8858>（默认账号 sentinel / sentinel）
- Redis 健康检查会断言 `MODULE LIST` 含 `search`，缺模块说明镜像不对

### 3.2 初始化 Nacos 配置（可选，缺失也能启动）

在 Nacos 中新建配置（Data ID / Group `DEFAULT_GROUP`）：

| Data ID | 用途 |
| --- | --- |
| `aihub-common.yaml` | 各服务共享的基础设施配置 |
| `aihub-gateway.yaml` | 网关路由与限流阈值 |
| `aihub-platform-service.yaml` | 平台服务配置 |
| `aihub-ai-service.yaml` | 模型地址、超时、工具开关等（**热更新**） |

> 配置归属原则：**运维/基础设施配置 → Nacos；业务/租户配置 → MySQL。**

### 3.3 编译与启动

```bash
mvn clean install          # 会执行 ArchUnit 架构卡口校验

# 依次启动（或各自 IDE 启动 main 方法）
java -jar aihub-gateway/target/aihub-gateway.jar
java -jar aihub-platform-service/target/aihub-platform-service.jar
java -jar aihub-ai-service/target/aihub-ai-service.jar
```

## 4. 冒烟验证（M0 DoD）

```bash
# 1) 登录拿令牌（租户码 demo，账号 admin / admin123）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","username":"admin","password":"admin123"}' | jq -r .data.token)

# 2) 经网关访问 AI 服务，应能看到租户上下文
curl -s http://127.0.0.1:8080/api/ai/smoke -H "Authorization: Bearer $TOKEN"
# 期望：{"code":0,"data":{"tenantId":1001,"userId":1,"service":"aihub-ai-service"}}

# 3) 不带令牌访问应返回 401
curl -i http://127.0.0.1:8080/api/ai/smoke

# 4) 伪造租户头应被网关剥离覆盖（不会越权）
curl -s http://127.0.0.1:8080/api/ai/smoke \
  -H "Authorization: Bearer $TOKEN" -H "X-Tenant-Id: 9999"
# 期望：tenantId 仍为 1001

# 5) 配置模型后即可对话（M1）
#    方式一：application.yml / Nacos 配 spring.ai.openai.api-key + base-url + model（默认模型）
#    方式二：往 ai_model / ai_model_route 插入租户级配置（走模型网关，支持主备降级）
#
# 同步对话
curl -s -X POST http://127.0.0.1:8080/api/ai/chat \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍 Spring AI"}'

# H5 流式（SSE）
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/sse \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"讲个笑话"}'

# 小程序流式（NDJSON Chunked，注意响应头 Content-Encoding: identity）
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/ndjson \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"message":"你好"}'
# 事件帧格式：{"i":0,"e":"msg.start",...} {"i":1,"e":"token","data":{"content":"..."}} {"i":n,"e":"msg.end",...}
```

## 5. 目录结构

```
aihub/
├── aihub-common/            统一响应、错误码、异常、租户上下文
├── aihub-api/               跨服务 Feign 契约 + 租户透传拦截器
├── aihub-gateway/           网关（WebFlux）：鉴权、路由、限流
├── aihub-platform-service/  平台服务：登录/租户/配额/审计
├── aihub-ai-service/        AI 服务（内聚）
│   └── src/test/.../ArchitectureTest.java   ★ 架构依赖卡口
├── aihub-mcp-service/       MCP 工具业务实现（协议无关，可单测）
├── aihub-mcp-sse/           MCP Server：SSE / Streamable-HTTP 传输
├── aihub-mcp-stdio/         MCP Server：stdio 传输（可执行 jar）
├── monitoring/              Prometheus 抓取配置 + Grafana 预装配仪表板
├── docs/nacos/              Nacos 配置模板与 Sentinel 规则样例
├── docker-compose.yml       MySQL + Redis Stack + Nacos + Sentinel + MinIO + Prometheus + Grafana
└── docker/mysql/init.sql
```

## 6. 安全约定（★ 务必遵守）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id` 头
3. 下游服务通过 `TenantResolveInterceptor` 读取上下文，缺失即拒绝
4. 服务间调用由 Feign 拦截器自动透传租户头
5. 两个服务的库独立，**禁止跨库 JOIN**

### 6.1 租户 SQL 兜底（第五道防线，默认关闭）

上面第 1~4 条保证"租户是谁不可伪造"，但**"查询有没有带租户条件"仍靠各 Repository 手写**
—— 漏写一行就是跨租户越权，且评审时很难发现。为此内置了 MyBatis-Plus 的
`TenantLineInnerInterceptor`（ai-service `TenantSqlGuardConfig` / platform `MybatisPlusConfig`），
开启后由框架自动为租户表追加 `tenant_id = 当前租户`：

```bash
export AIHUB_TENANT_SQL_GUARD_ENABLED=true
```

- 无租户上下文时（登录、异步入库线程、内部接口）**一律跳过**，行为与关闭时一致；
- 白名单（不含 `tenant_id` 列的全局表）：ai-service 为 `ai_model_provider`；
  platform 为 `sys_tenant` / `sys_menu` / `sys_role_menu`；
- **★ 默认关闭的原因**：它改的是每一条 SQL，而自动化测试以 Mockito 单测为主，
  **覆盖不到真实 SQL 的解析与改写**。请先用真实 MySQL 跑通
  「登录 → 对话 → 知识库上传/检索 → Agent → 用量看板 → 管理端 CRUD」，
  确认日志无 SQL 异常后再开启。

## 7. 跨服务最终一致（发件箱）

用量上报是 ai → platform 的跨服务调用，平台抖动时若只打日志就会**永久丢账**。
现由发件箱兜底（Outbox 模式，表 `ai_event_outbox` 在 V1 就已建好，本节起真正启用）：

| 组件 | 职责 |
| --- | --- |
| `domain/spi/EventOutbox` | 端口：`enqueue(tenantId, type, payload)` |
| `infra/outbox/DbEventOutbox` | 序列化落库（status=0 待投递）；失败只记日志，绝不向上抛 |
| `infra/outbox/EventOutboxDispatcher` | 守护线程定时重投（默认 30s 一轮、批量 50、最多重试 3 次后放弃） |

- **投递前必须补齐租户上下文**：后台线程没有请求上下文，而 Feign 拦截器只在租户非空时
  写 `X-Tenant-Id`，平台侧又强制要求该头存在 —— 不补上下文的重投会 100% 失败（详见两个类的注释）；
- 配置：`aihub.outbox.enabled`（默认 true）/ `batch-size` / `max-retry` / `fixed-delay-seconds`；
- 状态语义：`0` 待投递 · `1` 已投递 · `2` 已放弃（重试超限）。
