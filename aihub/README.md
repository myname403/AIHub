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

补充模块：`aihub-common`（通用支撑）、`aihub-api`（跨服务 Feign 契约）。

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
├── docker-compose.yml       MySQL + Redis Stack + Nacos + Sentinel
└── docker/mysql/init.sql
```

## 6. 安全约定（★ 务必遵守）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id` 头
3. 下游服务通过 `TenantResolveInterceptor` 读取上下文，缺失即拒绝
4. 服务间调用由 Feign 拦截器自动透传租户头
5. 两个服务的库独立，**禁止跨库 JOIN**
