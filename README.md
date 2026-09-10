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
| M3 ✅ | 工具中心：@Tool 内置工具 TimeTools / KnowledgeTools（模型自主调用）；**MCP Client 真集成**（配置即连，工具自动挂载）；**工具调用审计**落 `ai_tool_call_log`；**MCP Server 三模块**（一套 @Tool 实现，SSE 与 stdio 两种协议复用，把 AIHub 自身能力开放给 Claude Desktop / Cursor，见 3.8） | 第三章 MCP |
| M4 ✅ | Agent 内核：PlanningAgent 任务拆解 → AgentRegistry 按名派发 → Table/Chart/HtmlDoc 生成 Agent → **产物落盘可预览**；agent.step 全程事件流；**三重预算**（子任务数 / Token / 超时）；**服务端可中断**；**任务与步骤落库**；**浏览器控制 Agent**（页面标注 + 真实点击输入，见 3.9） | 第四章 MyManus |
| M5 ✅ | **配额硬限流**（策略+原子累加+超限拦截）、**配额多维度**（request / token / doc / task，一次调用批量原子扣减）、**Token 用量真实统计**、**审计 Advisor（call/stream 双路径）**、**TraceId 全链路**（网关起点 → Feign 透传 → MDC 日志 → 响应体）、**PDF/DOCX 解析**、**WebSocket 通道**（/ws/ai，令牌握手校验）、**知识库管理页** |
| M5 ✅ | **开放 API Key 通道**（HMAC-SHA256 只存哈希、网关校验 + Caffeine 缓存、fail-closed）、**Micrometer 指标**（QPS / 延迟 / Token / 工具调用 / 配额拒绝）、**入库文件落本地磁盘**（重启后仍可重试）、**管理端页面**（模型管理 / 应用配置 / 用量看板） | 可观测性、开放平台 |

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

对话页右上角另有 **知识库管理** 与 **管理后台** 两个入口：
管理后台包含「用量看板 / 模型管理 / 应用配置」三页（模型密钥支持在线配置，明文发送后由服务端加密落库）。

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

### 3.8 把 AIHub 暴露为 MCP Server（M3）

上面 3.7 是"AIHub 作为 MCP 客户端去用别人的工具"。反过来，AIHub 自己也能当 MCP Server，
把「列应用 / 列知识库 / 检索知识库 / 向应用提问」这四个能力交给 Claude Desktop、
Cursor 等任意 MCP 客户端使用。

模块按 ADR-3 拆成三个，**一套工具实现，两种协议复用**：

| 模块 | 职责 |
| --- | --- |
| `aihub-mcp-service` | 工具的纯业务实现（`@Tool`），**协议无关**，可单测 |
| `aihub-mcp-sse` | 以 SSE 对外提供在线服务（远程接入） |
| `aihub-mcp-stdio` | 打成可执行 jar，由宿主进程以 stdio 拉起（本地接入） |

对外暴露的工具：

| 工具 | 参数 | 说明 |
| --- | --- | --- |
| `aihub_list_applications` | — | 列出可用的 AI 应用，返回的 id 即 `aihub_ask` 要填的 `appId` |
| `aihub_list_knowledge_bases` | — | 列出知识库及 id |
| `aihub_search_knowledge` | `kbId` · `query` · `topK` | 知识库语义检索，返回分片 + 来源文档名 + 相似度 |
| `aihub_ask` | `message` · `appId` · `scene` | 向指定应用提问（含该应用的提示词与会话记忆） |

#### 方式一：SSE（在线）

```bash
export AIHUB_API_KEY=ak_xxx            # 管理后台签发的开放 API Key
java -jar aihub-mcp-sse/target/aihub-mcp-sse-1.0.0-SNAPSHOT.jar --server.port=8090
# 握手：GET http://127.0.0.1:8090/sse  →  event:endpoint  data:/mcp/message?sessionId=xxx
```

客户端配置：

```json
{ "mcpServers": { "aihub": { "url": "http://127.0.0.1:8090/sse" } } }
```

#### 方式二：stdio（本地，推荐给桌面客户端）

```json
{
  "mcpServers": {
    "aihub": {
      "command": "java",
      "args": ["-jar", "E:/微服务/javaAI/javaai/aihub/aihub-mcp-stdio/target/aihub-mcp-stdio.jar"],
      "env": { "AIHUB_API_KEY": "ak_xxx", "AIHUB_BASE_URL": "http://127.0.0.1:8080" }
    }
  }
}
```

#### 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `aihub.mcp.base-url` | `http://127.0.0.1:8080` | 上游网关地址（走网关，鉴权/限流统一生效） |
| `aihub.mcp.api-key` | 空 | 开放 API Key，**不配则所有工具调用被网关 401** |
| `aihub.mcp.timeout` | `60s` | 调上游的 HTTP 超时 |
| `aihub.mcp.default-app-id` | 空 | `aihub_ask` 不传 `appId` 时的兜底应用 |
| `aihub.mcp.default-top-k` | `5` | 检索默认条数（上限 20） |
| `aihub.mcp.chunk-max-chars` | `800` | 单个分片回给模型的最大字符数 |

#### 四个踩过的坑（都已修掉，改动时别踩回去）

1. **stdio 下 stdout 就是 JSON-RPC 通道**。Spring Boot 的 banner 和默认控制台日志都往
   stdout 写，插进去一条就会让客户端解析失败——现象是"一连上就崩"且报错毫无指向性。
   `aihub-mcp-stdio` 因此关掉了 banner（`spring.main.banner-mode: off`），
   并用专用的 `logback-spring.xml` 把控制台输出改到 **System.err** 并同时落文件
   （`%TEMP%/aihub-mcp-stdio/`）。
2. **stdio 传输要显式开开关**：`spring.ai.mcp.server.stdio: true`。
   缺失或为 false 时框架走的是"有 HTTP 端点"的那套装配，进程会安静地起一个 Tomcat
   并等 HTTP 请求，而 stdin 那边永远没有响应。
3. **关掉用不到的注解扫描器**：`spring.ai.mcp.server.annotation-scanner.enabled: false`。
   我们走的是 `@Tool` + `ToolCallbackProvider` 这条线；若不关，starter 里的
   `mcp-annotations` 库会在启动时扫全类路径找 `@McpTool`，扫不到就打一条
   `No tool methods found ...` 的 WARN——纯噪音，还容易被误读成"工具没注册上"。
4. **MCP 层超时要大于 HTTP 层超时**：`spring.ai.mcp.server.request-timeout` 默认只有 20s，
   小于我们调上游的 60s。不调的话，上游慢查询会先被 MCP 层掐断，日志里只看到
   "MCP 请求超时"，完全指不到 HTTP 那一层。故两处都设为 **70s > 60s**。

> 排查手法：stdio 版可以直接手工喂 JSON-RPC 验证，不依赖客户端——
> ```bash
> { echo '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"p","version":"1"}}}';
>   sleep 10; echo '{"jsonrpc":"2.0","method":"notifications/initialized"}'; sleep 2;
>   echo '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}'; sleep 5; } \
>   | java -jar aihub-mcp-stdio/target/aihub-mcp-stdio.jar
> ```
> 两个要点：**必须保持 stdin 打开并留出间隔**（一次性灌完立刻 EOF，进程会在处理
> 第二个请求前退出，看起来像"tools/list 无响应"）；stdout 里**只应有 JSON 行**。

### 3.9 浏览器控制 Agent（M4 · 页面标注方案）

让模型真的去「看页面、点按钮、填表单」。**零第三方依赖**：不引入 Playwright / Selenium，
直接用 JDK 自带的 `java.net.http.WebSocket` 说 CDP（Chrome DevTools Protocol），
复用本机已装的 Chrome / Edge——不下载任何浏览器内核。

```bash
# 1) 开启能力（默认关闭：会拉起本机 Chrome，且让模型访问任意 URL，属重资源 + SSRF 面）
set AIHUB_BROWSER_ENABLED=true
# 生产建议同时配置地址白名单 aihub.browser.allowed-url-prefixes

# 2) 对话里直接说「打开 https://example.com，在搜索框输入 AIHub 并点提交」——
#    模型自主调用 browser_open / browser_snapshot / browser_click / browser_type 四个工具
```

工作方式（页面标注）：
1. 每次观察都往页面注入一段标注脚本：只挑**可见可交互**的元素（a/button/input/…），
   分配短引用 `e1..eN` 并写回页面 `data-testid`；嵌套可点击元素只留内层（点击会冒泡）；
2. 模型只看精简清单（`[e3] button 提交`），不接触原始 HTML——token 可控且不会臆想元素；
3. 点击 = 滚动到元素中心 → 命中校验（防遮挡点空）→ `Input.dispatchMouseEvent`
   派发真实鼠标事件（`el.click()` 不触发 pointer 事件）；
4. 输入 = 聚焦 + 全选 → `Input.insertText`（模拟输入法，正确触发 input/change 事件，
   React/Vue 受控组件才能收到；直接 `el.value=xxx` 是无效的）。

两种用法：
- **工具**：对话模型在普通聊天里随手调用（按 `租户:会话` 复用同一个浏览器）；
- **Agent**：`browser` Agent 走 ReAct 循环（观察→决策→行动→再观察），
  受三重预算与服务端中断约束，`agent.step` 事件全程可见，步骤落库可回放。

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `aihub.browser.enabled` | false | 总开关 |
| `aihub.browser.executable` | 自动探测 | 留空按 Chrome → Edge → PATH 顺序找本机浏览器 |
| `aihub.browser.headless` | true | 调试时设 false，可亲眼看到模型在点什么 |
| `aihub.browser.max-elements` | 120 | 单次标注元素上限（token 封顶） |
| `aihub.browser.idle-timeout` | 5m | 会话空闲回收（一个 Chrome 几百 MB，必须还） |
| `aihub.browser.max-sessions` | 3 | 并发会话上限 |
| `aihub.browser.allowed-url-prefixes` | 空 | URL 前缀白名单，生产强烈建议配置 |

安全边界（缺一不可）：
- 只放行 http/https：`file://` 能读本地文件、`javascript:` 能执行脚本，一律拒绝；
- 模型拿不到「执行任意 JS」的口子，能做的只有 open / snapshot / click / type 四个结构化动作；
- 浏览器用独立临时 user-data-dir 启动（不碰用户日常配置）；空闲自动回收；
  服务停机与异常路径都按整棵进程树回收，不留孤儿 Chrome。

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

**管理端（模型 / 应用 / 用量）**

| 接口 | 说明 |
| --- | --- |
| `GET /api/ai/model/provider` | 供应商字典（下拉框数据源） |
| `GET /api/ai/model` | 模型列表（**响应不含密钥**，只有 `hasApiKey` 布尔值） |
| `POST /api/ai/model` · `PUT /api/ai/model/{id}` · `DELETE /api/ai/model/{id}` | 模型增删改（明文 Key 由服务端 AES-GCM 加密落库；编辑时留空表示保持原值） |
| `GET /api/ai/model/route` · `PUT /api/ai/model/route` | 场景路由（chat / rag / embed / agent-plan，主备模型） |
| `GET /api/ai/app` · `POST /api/ai/app` · `PUT /api/ai/app/{id}` · `DELETE /api/ai/app/{id}` | 应用（助手）配置 |
| `GET /api/ai/app/{id}/knowledge-bases` | 应用已绑定的知识库（多选框回显） |
| `GET /api/platform/usage/overview?days=7` | 概览：调用次数 / Token / 平均耗时 / 活跃模型数 |
| `GET /api/platform/usage/trend?days=7` | 按天趋势（**空白日期补零**，可直接画折线） |
| `GET /api/platform/usage/by-model?days=7` | 按模型聚合（调用量倒序，最多 20 条） |
| `GET /api/platform/usage/quota` | 配额快照（每维度的限额 / 已用 / 余量 / 使用率） |
| `POST /api/platform/apikey` · `GET /api/platform/apikey` · `DELETE /api/platform/apikey/{id}` | 签发 / 列出 / 吊销 API Key（明文**只在签发响应里出现一次**） |

### 开放 API Key（第三方对接）

```bash
# 1) 用用户 JWT 签发一个 Key（明文只返回这一次）
curl -X POST http://127.0.0.1:8080/api/platform/apikey \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"生产环境"}' | jq -r .data.apiKey

# 2) 之后用 X-API-Key 头调用，无需 JWT
curl -X POST http://127.0.0.1:8080/api/ai/chat \
  -H "X-API-Key: ak_xxxxxxxx" -H 'Content-Type: application/json' \
  -d '{"appId":9001,"message":"你好"}'
```

- 库中只存 `HMAC-SHA256(key, api-key-secret)`，**明文不落库**，遗忘只能重置
- 网关校验走 `POST /internal/apikey/verify` + Caffeine 本地缓存（TTL 60s）
- **平台服务不可用时拒绝而非放行**：鉴权失败必须 fail-closed
- API Key 不携带用户身份，因此只注入租户、不注入 `X-User-Id`

### 可观测性

- **指标**：`GET /actuator/prometheus` 暴露 `aihub.chat.calls` / `aihub.chat.latency` /
  `aihub.tokens` / `aihub.tool.calls` / `aihub.agent.tasks` / `aihub.ingest.latency` / `aihub.quota.rejected`
- 标签只包含低基数枚举值（status / model / stage / tool），**绝不放 tenantId / userId / conversationId**——
  否则时序库会被租户数量级放大

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

1. **Nacos 配置迁移**：基础设施配置（超时/限流/开关）迁入 Nacos 热更新（`spring.config.import: optional:nacos:` 已就绪，standalone 模式下自动跳过）
2. **Sentinel 规则持久化**：规则写入 Nacos DataSource
3. **对象存储替换本地磁盘**：入库原始文件当前落在 `{AIHUB_INGEST_DIR}/{tenantId}/{docId}.bin`
   （临时文件 + 原子改名写入），**服务重启后仍可重试**；多实例部署时因各节点本地盘不共享，
   重试可能落到没有该文件的节点——生产建议换 MinIO / OSS
4. **指标接入可视化**：Micrometer 已埋点并暴露 `/actuator/prometheus`，接 Prometheus + Grafana 即可出图
5. **MCP Server 的多租户与鉴权**：当前 SSE 通道自身不做鉴权（鉴权在它背后的开放 API 上），
   默认只绑 `127.0.0.1`；若要跨机暴露，需在前面加一层带鉴权的反向代理
6. **MCP Server 的 streamable-http 传输**：`spring.ai.mcp.server.protocol` 已支持
   `streamable` / `stateless`，需要时改配置即可，工具实现不用动

> 已完成（原遗留事项）：**浏览器控制 Agent**（未接 Playwright MCP，改为裸 CDP 直连
> 本机浏览器——零下载、零第三方依赖，能力完全一致，见 3.9）、
> **MCP Server 三模块**（service / sse / stdio，见 3.8）、
> 管理端页面（模型 / 应用 / 用量看板）、入库文件落盘、Micrometer 指标、API Key 认证。

## 六、安全红线（务必遵守）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id`
3. 模型密钥 AES-GCM 加密落库，日志与响应永不出现明文
4. 跨租户数据零可见：向量检索强制过滤 + 出口逐条校验
5. 两库独立，禁止跨库 JOIN
