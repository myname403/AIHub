# AIHub · 通用 AI 能力中台（成品说明）

把《Java+AI 智能应用开发 v1.0》课程知识落地成的**多租户微服务 AI 中台**：
模型可换、知识可插、工具可注册、Agent 可编排，一切可按租户计量与审计。

```
本目录结构
├── docs/           体系化设计文档（01 总览 ~ 07 微服务与中间件）
├── docs-source/    课程知识库抓取原文（9 篇）
├── aihub/          后端（Java 17 微服务，Maven 多模块）
└── aihub-app/      前端（uniapp + Vue3，H5 / 微信小程序双端）
```

---

## 一、系统架构

```
                     ┌────────────────────────┐
   H5 ──────────────▶│  aihub-gateway  :8080  │ JWT 鉴权 + Sentinel 限流 + 路由
   微信小程序 ───────▶└───────┬────────┬───────┘
                             │        │
                 ┌───────────▼──┐  ┌──▼──────────────────────────┐
                 │ platform     │  │ ai-service :8082（★ 内聚）    │
                 │ :8081        │  │ 对话 / RAG / 工具 / MCP      │
                 │ 租户/配额/审计│  │ Agent 内核 / 会话记忆 / 产物  │
                 └──────┬───────┘  └──────────┬──────────────────┘
                        │                     │
         ┌──────────────▼─────────────────────▼──────────────┐
         │ Nacos :8848   MySQL(两库)   Redis Stack(向量)       │
         │ Sentinel Dashboard :8858                           │
         └────────────────────────────────────────────────────┘
```

**关键设计**（详见 `docs/`）：
- **AI 能力内聚为一个服务**——规避分布式事务、流式跨服务传递、Agent 跨服务编排三大难题；服务间只留「租户校验 / 配额扣减 / 审计上报」三类无事务调用。
- **分层解耦由 ArchUnit 构建期强制**：web → application → domain ← infra，domain 零框架依赖，Spring AI 全部类型被限制在 `infra.ai` 包内。
- **租户隔离四道防线**：检索唯一入口 → 强制注入 tenant_id 过滤 → 出口 TenantGuard 校验 → 租户只从 JWT 解析。
- **流式三通道**：H5=SSE、小程序=NDJSON Chunked（禁 gzip）、Agent=同一套 StreamSink 抽象。

## 二、已实现能力（对应里程碑）

| 里程碑 | 能力 | 课程知识点 |
| --- | --- | --- |
| M0 ✅ | 三服务骨架、JWT 鉴权、租户上下文、ArchUnit 卡口、docker-compose | 工程化、多租户 |
| M1 ✅ | 模型网关（OpenAI 兼容协议族：openai/火山方舟/通义）、场景选模+主备降级、ChatClient 工厂（TTL 缓存）、**持久化会话记忆**、三种流式通道 | 第一章：ChatClient/Advisor/ChatMemory |
| M2 ✅ | RAG 全链路：文档上传→解析→分片（段落+重叠）→向量化→检索→**回答带引用 [n]**；检索调试台；内置「天机AI助手」示例模板 | 第一章 ETL/VectorStore + 第二章 业务助手 |
| M3 ✅ | 工具中心：@Tool 内置工具（模型自主调用）；MCP Client starter 已接入（配置即连外部 MCP Server） | 第三章 MCP |
| M4 ✅ | Agent 内核：PlanningAgent 任务拆解 → AgentRegistry 按名派发 → Table/Chart/HtmlDoc 生成 Agent → **产物落盘可预览**；agent.step 全程事件流；子任务预算 | 第四章 MyManus |
| M5 ✅ | **配额硬限流**（策略+原子累加+超限拦截）、用量落库、**PDF/DOCX 解析**、**WebSocket 通道**（/ws/ai，令牌握手校验）、**知识库管理页** | 综合深化 |

## 三、快速启动

### 3.1 依赖中间件（Docker 不是必须的！）

**方式 A：Docker 一键拉齐（推荐）**

```bash
cd aihub
docker compose up -d      # MySQL(两库) + Redis Stack + Nacos 3.0.3 + Sentinel Dashboard
docker compose ps         # 全部 healthy
```

- Nacos 控制台：<http://127.0.0.1:8848/index.html>；Sentinel：<http://127.0.0.1:8858>
- ⚠️ Redis 健康检查断言 `MODULE LIST` 含 `search`——**普通 Redis 没有向量能力**

**方式 B：免 Docker 单机模式（standalone）**

只装 MySQL 8（建库 aihub_platform / aihub_ai），不装 Nacos 也能跑：

```bash
# 网关加 standalone profile：路由直连 8081/8082，不走注册中心
java -jar aihub-gateway/target/aihub-gateway.jar --spring.profiles.active=standalone
java -jar aihub-platform-service/target/aihub-platform-service.jar --spring.profiles.active=standalone
java -jar aihub-ai-service/target/aihub-ai-service.jar --spring.profiles.active=standalone
```

- 不装 Redis：对话 / Agent 可用，仅 RAG 知识库功能不可用（程序自动降级）
- Nacos / Sentinel 是增强项：配置热更新与限流可视化，后补不影响现有功能

### 3.2 编译与启动后端

```bash
# 本机 PATH 的 java 是 1.8，必须显式指定 JDK17
cd aihub
JAVA_HOME="D:/Java_JDK/jdk-17.0.1" \
  "D:/Java_JDK/maven-mvnd-1.0.2-windows-amd64/bin/mvnd.cmd" clean install

java -jar aihub-gateway/target/aihub-gateway.jar
java -jar aihub-platform-service/target/aihub-platform-service.jar
java -jar aihub-ai-service/target/aihub-ai-service.jar
```

启动时 Flyway 自动建表并写入：演示租户 `demo`（账号 admin / admin123）、
模型供应商、**内置示例应用「天机AI助手」(appId=9001) + 演示知识库 (kbId=9001)**。

### 3.3 配置模型（任选其一）

```bash
# 方式一：环境变量（OpenAI 兼容协议，同样适用于火山方舟 / 通义兼容端点）
export AI_OPENAI_API_KEY=sk-xxx
export AI_OPENAI_BASE_URL=https://ark.cn-beijing.volces.com/api/v3
export AI_DEFAULT_CHAT_MODEL=deepseek-v3

# 方式二：租户级配置（走模型网关，密钥 AES-GCM 加密落库，支持主备降级）
#   插入 ai_model / ai_model_route 两张表即可，无需改代码
```

> 密钥加密密钥：`AIHUB_DATA_KEY`（默认 aihub-dev-data-key，生产放 Nacos）。

### 3.4 启动前端

```bash
cd aihub-app
# 用 HBuilderX 打开本目录 → 运行到浏览器 / 微信开发者工具
# 或 CLI：npm i && npm run dev:h5
```

登录（demo / admin / admin123）→ 对话页可切换 **对话 / 知识库 / Agent** 三种模式。

### 3.5 体验 RAG（知识库模式）

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","username":"admin","password":"admin123"}' | jq -r .data.token)

# 1) 上传课程数据文档（支持 txt/md；PDF 随后接入）
curl -X POST http://127.0.0.1:8080/api/ai/kb/9001/documents \
  -H "Authorization: Bearer $TOKEN" -F "file=@docs-source/yuque/05-知识库-课程数据.txt"

# 2) 知识库模式提问，回答自动带 [n] 引用与相似度
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/ndjson \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"appId":9001,"message":"推荐一门适合零基础的Java课程","scene":"rag"}'
```

### 3.6 体验 Agent（任务自动拆解 + 产物）

```bash
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/ndjson \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"appId":9001,"message":"统计中国省会城市，按首字母分组生成表格页面","scene":"agent"}'
# 返回 agent.step（think/act/observe）→ artifact（预览地址）事件流
```

## 四、接口速查（经网关，需 Bearer Token）

| 接口 | 说明 |
| --- | --- |
| `POST /api/ai/chat` | 同步对话 |
| `POST /api/ai/chat/sse` | H5 流式（SSE） |
| `POST /api/ai/chat/ndjson` | 小程序流式（NDJSON，禁 gzip） |
| `POST /api/ai/kb` · `GET /api/ai/kb` | 创建 / 列出知识库 |
| `POST /api/ai/kb/{id}/documents` | 上传文档并入库 |
| `POST /api/ai/kb/search` | 检索调试台（命中分片 + 相似度） |
| `POST /api/ai/kb/bind` | 应用绑定知识库 |
| `GET /api/ai/artifact/{id}` | Agent 产物在线预览 |
| `WS /ws/ai?token=JWT` | WebSocket 通道（Agent 长任务，双向） |

事件帧：`{"i":序号,"e":"msg.start|token|rag.sources|agent.step|artifact|msg.end|error","ts":..,"data":{..}}`

## 五、遗留事项（可选增强，架构已就位）

1. **MCP Server 三模块**（service/sse/stdio）：把平台对话与知识检索暴露为 MCP 服务
2. **浏览器控制 Agent**：接入 Playwright MCP（课程第四章的页面标注方案）
3. **Nacos 配置迁移**：基础设施配置（超时/限流/开关）迁入 Nacos 热更新（`spring.config.import: optional:nacos:` 已就绪，standalone 模式下自动跳过）
4. **Sentinel 规则持久化**：规则写入 Nacos DataSource
5. **管理端更多页面**：模型管理 / 应用配置 / 用量看板（后端接口已具备，知识库管理页已完成）

## 六、安全红线（务必遵守）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id`
3. 模型密钥 AES-GCM 加密落库，日志与响应永不出现明文
4. 跨租户数据零可见：向量检索强制过滤 + 出口逐条校验
5. 两库独立，禁止跨库 JOIN
