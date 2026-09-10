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
| M2 ✅ | RAG 全链路：文档上传→解析→分片（段落+重叠）→向量化→检索→**回答带引用 [n]**；**引用落库可回溯**；**入库异步化（进度 + 重试）**；检索调试台；内置「天机AI助手」示例模板 | 第一章 ETL/VectorStore + 第二章 业务助手 |
| M3 ✅ | 工具中心：@Tool 内置工具 TimeTools / KnowledgeTools（模型自主调用）；**MCP Client 真集成**（配置即连，工具自动挂载）；**工具调用审计**落 `ai_tool_call_log` | 第三章 MCP |
| M4 ✅ | Agent 内核：PlanningAgent 任务拆解 → AgentRegistry 按名派发 → Table/Chart/HtmlDoc 生成 Agent → **产物落盘可预览**；agent.step 全程事件流；**三重预算**（子任务数 / Token / 超时）；**服务端可中断**；**任务与步骤落库** | 第四章 MyManus |
| M5 ✅ | **配额硬限流**（策略+原子累加+超限拦截）、**配额多维度**（request / token / doc / task，一次调用批量原子扣减）、**Token 用量真实统计**、**审计 Advisor（call/stream 双路径）**、**TraceId 全链路**（网关起点 → Feign 透传 → MDC 日志 → 响应体）、**PDF/DOCX 解析**、**WebSocket 通道**（/ws/ai，令牌握手校验）、**知识库管理页** | 综合深化 |

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
# 方式 A：HBuilderX 打开本目录 → 运行到浏览器 / 微信开发者工具
# 方式 B：命令行（需 Node >= 18）
npm i
npm run dev:h5          # H5，默认 http://localhost:5173
npm run dev:mp-weixin   # 微信小程序，产物在 dist/dev/mp-weixin，用开发者工具打开
npm run build:h5        # 生产构建
```

> 两种方式的目录结构完全一致（`vite.config.js` 已把 `UNI_INPUT_DIR` 指回项目根），可随时互换。

登录（demo / admin / admin123）→ 对话页可切换 **对话 / 知识库 / Agent** 三种模式。

### 3.5 体验 RAG（知识库模式）

```bash
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"tenantCode":"demo","username":"admin","password":"admin123"}' | jq -r .data.token)

# 1) 上传知识库文档（支持 txt/md/pdf/docx）——异步提交，立即返回 taskId
TASK=$(curl -s -X POST http://127.0.0.1:8080/api/ai/kb/9001/documents \
  -H "Authorization: Bearer $TOKEN" -F "file=@docs-source/yuque/05-知识库-课程数据.txt" | jq -r .data.taskId)

# 2) 轮询入库进度（stage: parse → split → save → vector；status: 1处理中 2完成 3失败）
curl -s http://127.0.0.1:8080/api/ai/kb/ingest/$TASK -H "Authorization: Bearer $TOKEN" | jq .data

# 3) 失败可重试（服务重启后原始文件已释放时会提示重新上传）
curl -X POST http://127.0.0.1:8080/api/ai/kb/ingest/$TASK/retry -H "Authorization: Bearer $TOKEN"

# 4) 知识库模式提问，回答自动带 [n] 引用与相似度
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/ndjson \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"appId":9001,"message":"推荐一门适合零基础的Java课程","scene":"rag"}'

# 5) 引用溯源：刷新页面后仍可查回该会话的引用来源
curl -X POST http://127.0.0.1:8080/api/ai/chat/references \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"conversationId":"<上一步返回的会话ID>"}'
```

### 3.6 体验 Agent（任务自动拆解 + 产物）

```bash
curl -N -X POST http://127.0.0.1:8080/api/ai/chat/ndjson \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"appId":9001,"message":"统计中国省会城市，按首字母分组生成表格页面","scene":"agent"}'
# 返回 agent.step（think/act/observe）→ artifact（预览地址）事件流
```

### 3.7 接入外部 MCP Server（M3）

MCP Client 依赖已就位，挂载方式是"配置即连"——在 `application.yml` 里加连接即可，
工具会自动注册到 ChatClient，调用记录写入 `ai_tool_call_log`。

```yaml
spring.ai.mcp.client:
  toolcallback.enabled: true     # ★ 不开这个开关，MCP 工具不会生效
  sse.connections.fetch.url: http://127.0.0.1:8931/sse
  # stdio.connections.filesystem.command: npx
  # stdio.connections.filesystem.args: ["-y","@modelcontextprotocol/server-filesystem","."]
```

两个已知坑（课程已踩）：
- 只配 url 而不开 `toolcallback.enabled`，模型看不到任何 MCP 工具
- stdio 传输传中文参数会乱码，需保证子进程以 UTF-8 启动

内置工具目前有两个：`TimeTools`（时间/日期计算）与 `KnowledgeTools`（让模型自主检索知识库，
即 Agentic RAG——与被动注入上下文的 RAG 模式互为补充）。

## 四、接口速查（经网关，需 Bearer Token）

| 接口 | 说明 |
| --- | --- |
| `POST /api/ai/chat` | 同步对话 |
| `POST /api/ai/chat/sse` | H5 流式（SSE） |
| `POST /api/ai/chat/ndjson` | 小程序流式（NDJSON，禁 gzip） |
| `POST /api/ai/kb` · `GET /api/ai/kb` | 创建 / 列出知识库 |
| `POST /api/ai/kb/{id}/documents` | 上传文档并入库（异步，返回 taskId） |
| `GET /api/ai/kb/ingest/{taskId}` | 查询入库进度（前端轮询） |
| `POST /api/ai/kb/ingest/{taskId}/retry` | 重试失败的入库任务 |
| `POST /api/ai/kb/search` | 检索调试台（命中分片 + 相似度） |
| `POST /api/ai/kb/bind` | 应用绑定知识库 |
| `POST /api/ai/chat/references` | 会话引用溯源（按 conversationId 查回引用来源） |
| `GET /api/ai/artifact/{id}` | Agent 产物在线预览 |
| `POST /api/ai/agent/cancel` | 取消进行中的 Agent 任务（按 conversationId，服务端真正终止后续子任务） |
| `WS /ws/ai?token=JWT` | WebSocket 通道（Agent 长任务，双向） |

事件帧：`{"i":序号,"e":"msg.start|token|rag.sources|agent.step|artifact|msg.end|error","ts":..,"data":{..}}`

### 链路追踪（TraceId）

每个请求都有唯一链路 ID，贯穿网关 → AI 服务 → 平台服务：

- **请求头**：`X-Trace-Id`（客户端可自带，否则由网关生成）
- **响应头**：同名回写，前端控制台可直接看到
- **响应体**：`R.traceId` 字段（含异常响应）
- **日志**：MDC 已注入，格式 `[traceId] [thread] logger - msg`
- **跨线程**：流式线程池、入库存档线程、WebSocket 任务、Feign 调用均已显式传递

排查示例：拿一个 traceId，在三份日志里 grep 即可还原完整调用链。

## 五、遗留事项（可选增强，架构已就位）

1. **MCP Server 三模块**（service/sse/stdio）：把平台对话与知识检索暴露为 MCP 服务
   （Client 侧已打通，Server 侧尚未实现）
2. **浏览器控制 Agent**：接入 Playwright MCP（课程第四章的页面标注方案）
3. **Nacos 配置迁移**：基础设施配置（超时/限流/开关）迁入 Nacos 热更新（`spring.config.import: optional:nacos:` 已就绪，standalone 模式下自动跳过）
4. **Sentinel 规则持久化**：规则写入 Nacos DataSource
5. **管理端更多页面**：模型管理 / 应用配置 / 用量看板（后端接口已具备，知识库管理页已完成）
6. **对象存储替换内存缓存**：入库的原始文件目前暂存在进程内 ConcurrentHashMap，
   服务重启后无法重试（已在重试接口中给出明确提示），生产建议落 MinIO / OSS
7. **Micrometer 指标**：QPS / 延迟 / Token 消耗曲线（TraceId 已就位，可直接挂 Observation）
8. **API Key 认证**：当前只支持 JWT，对外提供能力需补 API Key 通道

## 六、安全红线（务必遵守）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id`
3. 模型密钥 AES-GCM 加密落库，日志与响应永不出现明文
4. 跨租户数据零可见：向量检索强制过滤 + 出口逐条校验
5. 两库独立，禁止跨库 JOIN
