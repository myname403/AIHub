# 07 - MCP 工具三件套讲解

> 本章讲解 AIHub 项目中与 MCP（Model Context Protocol，模型上下文协议）相关的三个 Maven 模块：
> `aihub-mcp-service`（核心工具实现）、`aihub-mcp-sse`（远程 SSE 传输）、`aihub-mcp-stdio`（本地 stdio 传输）。
> 文中所有代码片段均摘自项目真实源码，路径相对于仓库根目录。本章自成一体，不依赖其他章节即可阅读。

---

## 1. MCP 协议是什么

### 1.1 一句话定义

MCP（Model Context Protocol，模型上下文协议）是 Anthropic 于 2024 年底开源的一项协议，目标是为 **LLM 应用与外部数据源/工具之间** 提供一个标准化的连接方式。它基于 **JSON-RPC 2.0** 报文格式，定义了客户端（Host 内嵌的 MCP Client，如 Claude Desktop、各类 IDE 插件）与服务端（MCP Server，即能力的提供方）之间如何互相发现能力、如何调用工具、如何传输结果。

### 1.2 为什么 LLM 应用需要它：从 M×N 到 M+N

先想清楚"没有 MCP 时的问题"：

- 大模型本身只会生成文本，**不能查数据库、不能读你的知识库、不能调用你公司的 API**；
- 要让 LLM 应用具备这些能力，只能由应用开发者逐个写"函数调用（Function Calling）"的胶水代码；
- 如果有 M 个 LLM 应用、N 个工具系统，最坏要写 M×N 份适配代码，而且每家的调用格式都不一样。

MCP 把这件事收敛为 **M+N**：工具方只需实现一次 MCP Server，把能力按协议暴露；LLM 应用只需实现一次 MCP Client。两边握手后，任何 Host 都能发现并调用任何 Server 的工具——这就是协议的价值，和 JDBC 统一数据库访问、USB 统一外设连接是同一类思路。

### 1.3 MCP 的三类能力

MCP 协议中，Server 可以向 Client 声明三类能力（capabilities）：

| 能力 | 含义 | 类比 | AIHub 是否提供 |
|---|---|---|---|
| **Tools（工具）** | 模型可"主动调用"的函数，有输入参数 Schema，执行后有结果返回 | REST API 的 POST | ✅ 提供 4 个工具 |
| **Resources（资源）** | 客户端可"拉取"的数据内容，如文件、数据库记录 | GET 静态资源 | ❌ 未提供 |
| **Prompts（提示词模板）** | 预定义的提示词模板，用户可选用 | 快捷指令 | ❌ 未提供 |

AIHub 只做工具这一类。这一点在配置里写得明明白白（`aihub/aihub-mcp-sse/src/main/resources/application.yml`）：

```yaml
capabilities:
  # 本 Server 只提供工具，不提供 prompt / resource / completion。
  # 按实际能力上报，客户端才不会去调根本不存在的东西。
  tool: true
  prompt: false
  resource: false
  completion: false
```

按实际能力上报而不是全报 true，是一个值得学习的细节：Client 的 UI 会依据 capabilities 决定展示哪些交互入口，虚报能力只会让用户点到不存在的东西。

### 1.4 协议交互的基本节拍

MCP Server 的工具能力通过两个核心 RPC 方法暴露：

- **`tools/list`**：Client 启动握手后调用，Server 返回工具清单（工具名、描述、输入参数 JSON Schema）。模型"知道自己能用什么工具"，靠的就是这份清单；
- **`tools/call`**：模型决定用某个工具后，Host 把工具名 + 参数封装成 `tools/call` 请求发给 Server，Server 执行后把结果（文本）返回。

后面第 3 章的"完整旅程"会把这两个节拍串到 AIHub 的真实代码上。

---

## 2. 三件套分工

### 2.1 分工总览表

| 模块 | 角色 | 传输方式 | 端口 | 启动方式 | 关键依赖 |
|---|---|---|---|---|---|
| `aihub-mcp-service` | **核心工具实现**（协议无关） | 无（纯 Java 类库） | 无 | 不独立启动，被另外两个模块依赖 | `spring-ai-model`、`spring-web`、`jackson-databind` |
| `aihub-mcp-sse` | **远程传输**（在线服务） | SSE / Streamable-HTTP（HTTP 长连接） | **8090**（默认绑 127.0.0.1） | `java -jar aihub-mcp-sse.jar` 常驻运行 | `spring-ai-starter-mcp-server-webmvc`、actuator + prometheus |
| `aihub-mcp-stdio` | **本地传输**（被拉起的子进程） | stdio（stdin/stdout 传 JSON-RPC） | 无端口 | 由宿主进程（Claude Desktop 等）以 `java -jar aihub-mcp-stdio.jar` 拉起 | `spring-ai-starter-mcp-server`（无 web） |

### 2.2 为什么拆成三个模块

三个模块对应三种"形态"，但工具只有一套。设计意图在 `aihub/aihub-mcp-service/pom.xml` 的注释里写得很直白：

```xml
<!-- 刻意【不】依赖 aihub-common：它带 spring-boot-starter-web，
     会把整个 servlet 容器拖进 classpath。而 stdio 版是被 `java -jar` 拉起的
     CLI 进程，容器在那儿纯属负担，还会逼着 stdio 模块显式关掉 web 环境。
     本模块只需要「发 HTTP + 解 JSON」，因此按最小集声明依赖。 -->

<!-- 仅取 @Tool / MethodToolCallbackProvider；不引任何传输层 starter，
     MCP 协议依赖由 sse / stdio 两个模块各自引入，本模块保持协议无关。 -->
```

由此得到一条清晰的依赖链（ASCII）：

```
                ┌──────────────────────────────┐
                │      aihub-mcp-service       │  工具实现（协议无关）
                │  AiHubTools + AiHubApi + ... │  只含 @Tool 注解与 HTTP 出口
                └──────────────┬───────────────┘
                               │ 被两个传输模块依赖
              ┌────────────────┴────────────────┐
              ▼                                 ▼
   ┌─────────────────────┐          ┌─────────────────────┐
   │    aihub-mcp-sse    │          │   aihub-mcp-stdio   │
   │ spring-ai-starter-  │          │ spring-ai-starter-  │
   │ mcp-server-webmvc   │          │ mcp-server (无web)  │
   │ 端口 8090，常驻服务  │          │ 子进程，stdin/stdout │
   └─────────────────────┘          └─────────────────────┘
```

这样拆的核心收益：**新增一种传输方式（如 streamable-http）时，`aihub-mcp-service` 一行代码都不用改**。事实上 `mcp-sse` 的配置注释里就说明了这一点——streamable 协议的自动配置类就在 `spring-ai-starter-mcp-server-webmvc` 里，改一个环境变量 `MCP_PROTOCOL=streamable` 即可切换，无需新依赖、新模块。

### 2.3 aihub-mcp-service：核心工具实现（11 个类）

这是工具的"业务心脏"，分四个包。

#### 2.3.1 tools 包：AiHubTools —— 4 个工具的定义处

来源：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/tools/AiHubTools.java`

**作用与原理**：这个类用 Spring AI 的两个注解把普通 Java 方法变成 MCP 工具：

- `@Tool(name, description)`：方法级注解。`name` 是模型在 `tools/list` 里看到的工具名（统一带 `aihub_` 前缀，防止与其他来源的工具重名冲突）；`description` 是模型决定"用不用这个工具、怎么传参"的**唯一依据**；
- `@ToolParam(description, required)`：参数级注解。`required=false` 的参数模型可以不传，方法里用默认值兜底。

四个工具一览（行号为源码中 `@Tool` 注解所在行）：

| 工具名 | 定义位置 | 功能 | 关键参数 |
|---|---|---|---|
| `aihub_list_applications` | AiHubTools.java#L54 | 列出租户下可用的 AI 应用，返回的 id 供 `aihub_ask` 使用 | 无 |
| `aihub_list_knowledge_bases` | AiHubTools.java#L83 | 列出租户下的知识库及 ID | 无 |
| `aihub_search_knowledge` | AiHubTools.java#L109 | 知识库语义检索，返回文档片段 + 相似度 | kbId、query、topK（可选，默认 5 上限 20） |
| `aihub_ask` | AiHubTools.java#L147 | 向某个 AI 应用提问，拿回完整回答（同步） | message、appId（可选）、scene（可选） |

**项目真实用例**：以 `aihub_search_knowledge` 为例，看注解如何写、参数如何兜底（AiHubTools.java#L109-L145）：

```java
@Tool(name = "aihub_search_knowledge",
        description = """
                在 AIHub 知识库中做语义检索，返回最相关的文档片段（含来源文档名与相似度）。
                适用于「查资料 / 找依据 / 引用原文」这类需求，比让模型自己回忆可靠得多。
                返回的每条结果都带相似度百分比：低于 60% 通常意味着知识库里没有相关内容，
                此时应如实告知用户「知识库中未找到相关内容」，不要强行作答。""")
public String searchKnowledge(
        @ToolParam(description = "知识库 ID，可先用 aihub_list_knowledge_bases 获取")
        Long kbId,
        @ToolParam(description = "检索问题或关键词，用自然语言即可")
        String query,
        @ToolParam(description = "返回条数，默认 5，最多 20", required = false)
        Integer topK) {
    ...
    int limit = topK == null || topK <= 0 ? properties.getDefaultTopK() : Math.min(topK, 20);
```

**三个刻意的设计选择**（类的 Javadoc 中总结，也是面试的好素材）：

1. **全部返回 String，不返回对象**。MCP 工具的结果要能被任意客户端（Claude Desktop、IDE 插件、自研 Agent）消费，纯文本是最不会出错的表示；返回 POJO 则依赖两端 JSON Schema 生成实现一致，跨实现时容易踩坑；
2. **错误转成文字而不是抛异常**。`AiHubCallException` 在工具方法里被捕获并整理成可读文字。若让它穿透到 MCP 协议层，会变成 JSON-RPC error，模型的反应通常是直接放弃；而"鉴权未通过，请检查 API Key"这类提示能让模型继续推理或如实向用户汇报；
3. **工具描述写得像给人看的说明书**。描述里明确"何时用、何时不该用、返回值怎么理解"，例如 `aihub_ask` 的描述里特别提示"这是同步调用，长回答耗时较久；如果只是要查资料，用 aihub_search_knowledge 更直接"——这些提示直接影响模型选工具的准确率。

另一个容易忽略的实现细节是 `truncate` 方法（AiHubTools.java#L190-L199）：单条知识分片超过 `chunkMaxChars`（默认 800 字符）时截断并标注"共 N 字"。一次工具调用若把几万字塞进上下文，会把 token 预算打满、挤掉后续对话——**宁可截断并告知，也不静默撑爆上下文**。

**常见误用**：把工具方法的返回类型写成自定义 POJO 并期待客户端解析成结构化数据；或在工具方法里直接抛业务异常。前者跨客户端兼容性差，后者会让模型看到一条无法理解的 JSON-RPC error 而直接放弃任务。

**面试怎么说**：MCP 工具的 description 是写给模型看的"API 文档"，工具质量的一半在描述质量；工具结果统一转纯文本、错误信息写成"模型可行动的提示"，是与 LLM 协作区别于传统服务端开发的两大要点。

#### 2.3.2 api 包：AiHubApi 端口 + HttpAiHubApi 实现

来源：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/AiHubApi.java`、`HttpAiHubApi.java`、`AiHubCallException.java`、`ApiEnvelope.java`

**作用与原理**：工具本身不实现任何业务，所有能力都通过 HTTP 回调 AIHub 网关的开放 API 获取。`AiHubApi` 是四个回调方法的接口：

```java
public interface AiHubApi {
    List<AppBrief> listApplications();
    List<KbBrief> listKnowledgeBases();
    List<ChunkHit> searchKnowledge(Long kbId, String query, Integer topK);
    ChatAnswer chat(Long appId, String message, String scene);
}
```

抽成接口的目的（源码 Javadoc 原话）：让 `AiHubTools` 可以**脱离网络做单元测试**——工具的价值在"参数校验 + 结果组织"，那部分不该依赖能不能连上网关。

`HttpAiHubApi` 是唯一实现，有三个值得逐条讲的点：

**① 鉴权方式：`X-API-Key` 请求头**（HttpAiHubApi.java#L29）。MCP Server 不代表某个终端用户，用一个固定 Key 标识"哪个租户在通过 MCP 使用 AIHub"最贴切：

```java
/** 与服务端 AuthGlobalFilter 约定一致 */
private static final String HEADER_API_KEY = "X-API-Key";
```

**② 统一错误翻译 `describe` 方法**（HttpAiHubApi.java#L164-L182）。把 HTTP 状态码翻成模型能理解的中文提示：

```java
if (text.contains("401")) {
    return "鉴权未通过（HTTP 401），请检查 aihub.mcp.api-key 是否有效或已吊销";
}
if (text.contains("429")) {
    return "触发限流（HTTP 429），请稍后重试";
}
```

模型看到"401"只会瞎猜，看到"API Key 无效或已停用"才知道该怎么办——这与"错误转文字"的设计一脉相承。

**③ `maskApiKey` 静态方法**（HttpAiHubApi.java#L191-L196）：启动日志只回显 Key 的前 7 位加 `***`，绝不打印完整 Key。这是安全基线：日志文件往往比配置文件流转得更广。

**`ApiEnvelope` 为什么不复用服务端的 `R`**（ApiEnvelope.java Javadoc 给出的两条理由，非常典型）：

1. 服务端 `R` 只有私有构造器、没有 `@JsonCreator`，Jackson 反序列化不了；
2. `R` 住在 `aihub-common` 里，而那个模块带了 servlet 容器（正是第 2.2 节刻意避开的东西）。

所以 MCP 模块自带一份客户端镜像类型 `ApiEnvelope<T>`（code/message/data，`code==0` 即成功），并全部打上 `@JsonIgnoreProperties(ignoreUnknown = true)`——服务端重构内部 DTO 时不会连带打破 MCP 侧。这是"消费公开 HTTP 契约的客户端应像 SDK 一样自带类型"的正确示范。

**常见误用**：为了"代码复用"把服务端 common 模块的响应体/DO 直接引过来用，结果把 servlet 容器拖进 stdio 的 classpath，或被服务端字段变更连锁打挂。

#### 2.3.3 config 包：装配与配置项

来源：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/config/AiHubMcpConfiguration.java`、`AiHubMcpProperties.java`

`AiHubMcpConfiguration` 声明三个 Bean，是"能力对外暴露"的开关：

```java
@Bean
public ToolCallbackProvider aiHubToolCallbackProvider(AiHubTools tools) {
    return MethodToolCallbackProvider.builder()
            .toolObjects(tools)
            .build();
}
```

MCP Server 的自动装配会收集容器里的 `ToolCallbackProvider`，并据此生成 `tools/list` 结果——没有这个 Bean，四个 `@Tool` 方法就只是普通方法。另外两个 Bean 分别是 `AiHubApi`（构造 RestClient 并打启动日志、未配 Key 时 WARN 提醒）和 `AiHubTools`。

`AiHubMcpProperties` 绑定 `aihub.mcp.*` 配置前缀，五项配置的含义：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `base-url` | `http://127.0.0.1:8080` | AIHub 网关地址，一切业务调用都经它转发 |
| `api-key` | 空 | 开放 API Key（`ak_` 开头），为空则网关全部 401 |
| `timeout` | 60s | 单次上游调用超时，**必须小于 MCP 层的 request-timeout（70s）** |
| `default-app-id` | 无 | `aihub_ask` 未指定 appId 时的兜底应用 |
| `default-top-k` / `chunk-max-chars` | 5 / 800 | 检索默认条数 / 单分片截断长度 |

**常见误用**：把 `aihub.mcp.timeout` 配得比 `spring.ai.mcp.server.request-timeout` 还大——上游慢查询会先被 MCP 层掐断，日志里只看到"MCP 请求超时"，排查时完全指不到 HTTP 那一层（这是 `application.yml` 注释里特别标注的坑）。

#### 2.3.4 model 包：4 个 DTO

来源：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/`（AppBrief、KbBrief、ChunkHit、ChatAnswer）

全部是 Lombok `@Data` + `@JsonIgnoreProperties(ignoreUnknown = true)` 的瘦客户端模型，只声明 MCP 侧真正需要的字段。两个有意思的细节：

- `AppBrief.enabled()` / `KbBrief.enabled()`：`status == 1` 即启用，工具在输出文本里会标注"（已停用，调用会失败）"，帮模型提前避坑；
- `ChunkHit.scorePercent()`：把 0~1 的相似度转成百分比整数。源码注释："模型对 0.83 这种小数的把握不如 83%"——看似小事，实际影响模型判断检索结果可信度的准确率。

#### 2.3.5 与上游 API 的对照表

| MCP 工具 | 调用的开放 API | 方法 |
|---|---|---|
| `aihub_list_applications` | `GET /api/ai/app` | HttpAiHubApi.java#L76 |
| `aihub_list_knowledge_bases` | `GET /api/ai/kb` | HttpAiHubApi.java#L86 |
| `aihub_search_knowledge` | `POST /api/ai/kb/search` | HttpAiHubApi.java#L96 |
| `aihub_ask` | `POST /api/ai/chat` | HttpAiHubApi.java#L114 |

注意：所有调用都走**网关 8080**而不是直连 ai-service，这样鉴权、限流、TraceId 透传等横切能力统一在网关生效。

### 2.4 aihub-mcp-sse：远程 SSE 传输（4 个类）

#### 2.4.1 启动类

来源：`aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/AiHubMcpSseApplication.java`

```java
@SpringBootApplication(scanBasePackages = "com.aihub.mcp")
public class AiHubMcpSseApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiHubMcpSseApplication.class, args);
    }
}
```

关键在 `scanBasePackages = "com.aihub.mcp"`：工具装配在 `aihub-mcp-service` 模块的 `com.aihub.mcp.config` 包里，而启动类自己住在 `com.aihub.mcp.sse`。Spring Boot 默认只扫启动类所在包，**不往上扩一层就拿不到 `AiHubMcpConfiguration`，四个工具一个都注册不上**。

#### 2.4.2 端口与端点配置

来源：`aihub/aihub-mcp-sse/src/main/resources/application.yml`

| 配置 | 值 | 说明 |
|---|---|---|
| `server.port` | `${MCP_SSE_PORT:8090}` | 8090 避开网关 8080 / 平台 8081 / AI 服务 8082 |
| `server.address` | `${MCP_SSE_ADDRESS:127.0.0.1}` | 默认只绑回环地址，跨机暴露前先开通道门禁 |
| `spring.ai.mcp.server.protocol` | `${MCP_PROTOCOL:sse}` | 可切 `streamable`（端点默认 `/mcp`），无需新依赖 |
| `sse-endpoint` | `/sse` | SSE 建连端点 |
| `sse-message-endpoint` | `/mcp/message` | 消息回传端点 |
| `request-timeout` | `70s` | **必须大于 `aihub.mcp.timeout`（60s）** |
| `keep-alive-interval` | `30s` | SSE 长连接定期 ping 保活，防中间设备断链 |
| `annotation-scanner.enabled` | `false` | 关掉 `@McpTool` 注解扫描，消除启动期误导性 WARN |

`annotation-scanner.enabled=false` 值得单独说：本项目用的是 Spring AI 原生 `@Tool` + `ToolCallbackProvider` 路线，不用 `@McpTool` 那套注解。若不关，starter 里的 mcp-annotations 库会在启动时扫描全类路径找 `@McpTool` 方法，找不到就抛一条 "No tool methods found" 的 WARN——纯噪音，且会让人误以为"工具没注册上"。关掉它既省一次扫描，也让启动日志干净可信。

#### 2.4.3 通道门禁鉴权（auth 包三个类）

来源：`aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/`（McpChannelAuthFilter、McpChannelAuthProperties、McpChannelAuthConfiguration）

**为什么需要它**：MCP 工具能读写业务数据（列应用、检索知识库、发起对话），而这条通道历史上不做鉴权（默认只绑 127.0.0.1，鉴权在它背后的 AIHub 开放 API 上）。跨机暴露前，先在这里开启"通道门禁"，避免**端口暴露即工具裸奔**。

**作用与原理**：`McpChannelAuthFilter` 继承 `OncePerRequestFilter`，是 Servlet Filter 层组件——比拦截器（HandlerInterceptor）更靠前，在请求进入 DispatcherServlet 之前执行。**为什么必须用 Filter 而不是拦截器**：`/mcp/**` 是 Spring AI MCP 框架注册的端点，不走自己的 Controller，拦截器根本拦不到。

核心逻辑（McpChannelAuthFilter.java#L43-L61）：

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                FilterChain chain) throws ServletException, IOException {
    if (!properties.isEnabled() || !isProtected(request.getRequestURI())) {
        chain.doFilter(request, response);
        return;
    }
    String authorization = request.getHeader("Authorization");
    if (authorization != null && matchesToken(authorization)) {
        chain.doFilter(request, response);
        return;
    }
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(
            "{\"error\":\"unauthorized\",\"message\":\"MCP 通道需要鉴权：Authorization: Bearer <aihub.mcp.auth.token>\"}");
}
```

三个安全细节：

- **常量时间比较**（McpChannelAuthFilter.java#L74-L82）：令牌比较用 `MessageDigest.isEqual` 而不是 `String.equals`，不给时序攻击逐字节试探的口子；
- **拒绝即明确报错**：返回 401 + JSON，MCP 客户端在建立连接时就能看到明确原因，而不是连接挂着等到工具调用才神秘失败；
- **路径匹配兼顾会话后缀**：`uri.equals(path) || uri.startsWith(path + "/")`，因为 `/mcp/*` 这类端点带会话后缀。

**装配设计**（McpChannelAuthConfiguration.java）：

```java
@Configuration
@ConditionalOnProperty(prefix = "aihub.mcp.auth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(McpChannelAuthProperties.class)
public class McpChannelAuthConfiguration {
    public McpChannelAuthConfiguration(McpChannelAuthProperties properties) {
        if (properties.getToken() == null || properties.getToken().isBlank()) {
            throw new IllegalStateException(
                    "aihub.mcp.auth.enabled=true 但未配置 aihub.mcp.auth.token：通道门禁没有令牌可校验，拒绝启动");
        }
        ...
    }
```

- `@ConditionalOnProperty` 开关：默认关闭（`aihub.mcp.auth.enabled=false`），保持既有本地行为零变化；
- **fail-fast**：`enabled=true` 却没配 token 是部署错误，启动期直接抛异常拒绝启动，而不是默默让所有人 401 或（更糟）全部放行；
- 过滤器 `order=0` 挂在最前，赶在 MCP transport 处理请求之前完成门禁。

**多租户语义说明**（McpChannelAuthProperties.java Javadoc）：当前形态是**一个 MCP 实例服务一个租户**——工具调用走 AIHub 开放 API 时携带的是 `aihub.mcp.api-key`（某租户的 Key），数据边界由网关的 Key 鉴权保证。通道门禁解决的是"通道本身要不要登录"，而不是"一条连接切多个租户"；多租户共享实例需按会话传递 Key，待 MCP 客户端生态支持 headers 透传后再演进。

### 2.5 aihub-mcp-stdio：本地 stdio 传输（1 个类）

来源：`aihub/aihub-mcp-stdio/src/main/java/com/aihub/mcp/stdio/AiHubMcpStdioApplication.java`

```java
@SpringBootApplication(scanBasePackages = "com.aihub.mcp")
public class AiHubMcpStdioApplication {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(AiHubMcpStdioApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        ConfigurableApplicationContext context = application.run(args);
        context.registerShutdownHook();
    }
}
```

三个关键点：

1. **`WebApplicationType.NONE`**：stdio 传输靠 stdin/stdout 传 JSON-RPC，进程里不该有 HTTP 服务器。当前 classpath 上确实没有 servlet 容器（`aihub-mcp-service` 刻意不依赖 `aihub-common`），但显式声明能防止将来有人往依赖里加个 web 模块就把这个 CLI 工具变成半吊子 Web 应用——那时它会安静地启动一个 Tomcat 并等 HTTP 请求，而 stdin 那边永远没有响应；
2. **不主动关闭上下文**：stdio 会话的生命周期由宿主进程（Claude Desktop / IDE 插件）决定，它关闭管道时 MCP 框架会自行结束；
3. **`registerShutdownHook()`**：宿主进程终止时保证资源有序释放。

stdio 模式下配置文件（`aihub/aihub-mcp-stdio/src/main/resources/application.yml`）里有两处"为 stdout 而生"的关键配置：

```yaml
spring:
  main:
    # ★ Spring Boot 的 banner 默认往 System.out 打印一串 ASCII 图，
    #   足以让客户端的第一条 JSON-RPC 响应解析失败。必须关掉。
    banner-mode: "off"
    web-application-type: none
  ai:
    mcp:
      server:
        # ★★ 这是 stdio 传输的开关：必须显式设为 true
        stdio: true
```

**banner-mode 必须关**：这个进程的 stdout 就是 MCP 的 JSON-RPC 通道，任何非 JSON 输出（banner、日志）都会让客户端解析失败，现象是"一连上就崩"且报错毫无指向性。因此该模块还专门配了 `logback-spring.xml` 把日志引离 stdout。

**stdio 传输的开关**：`spring.ai.mcp.server.stdio=true` 必须显式设置——McpServerStdioDisabledCondition 判定该配置缺失或 false 时，走的是"有 HTTP 端点"的那套装配。

**宿主进程接入方式**（Claude Desktop 配置示例，来自 application.yml 注释）：

```json
{
  "mcpServers": {
    "aihub": {
      "command": "java",
      "args": ["-jar", "aihub-mcp-stdio.jar"],
      "env": { "AIHUB_API_KEY": "ak_xxx", "AIHUB_BASE_URL": "http://127.0.0.1:8080" }
    }
  }
}
```

宿主进程通过 `env` 注入 `AIHUB_API_KEY` 与 `AIHUB_BASE_URL`——换 Key 即切换租户，这正是多租户在 MCP 场景下的落点。

### 2.6 启动速查

```bash
# 构建三件套
mvn -pl aihub-mcp-service,aihub-mcp-sse,aihub-mcp-stdio -am clean package

# 启动远程 SSE 版（常驻服务，端口 8090）
AIHUB_API_KEY=ak_xxx java -jar aihub-mcp-sse/target/*.jar
# 验证：curl http://127.0.0.1:8090/actuator/health

# 本地 stdio 版：不手动启动，由宿主进程（Claude Desktop 等）按需拉起
# 手动验证可用 MCP Inspector 或直接观察 stdin/stdout 的 JSON-RPC 交互
```

前提：AIHub 网关（8080）已在运行，且已在管理后台签发 API Key（`ak_` 开头）。

---

## 3. 一次工具调用的完整旅程

以用户在 Claude Desktop 里问"帮我查一下入职流程相关的公司文档"为例，走完从 LLM 决策到继续生成的五步。假设宿主通过 stdio 拉起了 `aihub-mcp-stdio`。

### 3.0 全链路时序图

```
 用户            Claude Desktop(Host)         模型(LLM)        MCP Server(stdio进程)        AIHub网关(8080)
  │                    │                        │                     │                        │
  │  "查入职流程文档"   │                        │                     │                        │
  │───────────────────>│── 对话请求 ───────────>│                     │                        │
  │                    │                        │                     │                        │
  │                    │      （启动时已完成 tools/list，                     │                  │
  │                    │        模型已知 4 个 aihub_* 工具）                   │                  │
  │                    │                        │                     │                        │
  │                    │                        │ ① 决策：调用                       │       │
  │                    │                        │   aihub_search_knowledge(kbId=?,  │       │
  │                    │                        │   query="入职流程")               │        │
  │                    │<── tool_use 响应 ──────│                     │                        │
  │                    │                        │                     │                        │
  │                    │  ② Host 封装 tools/call (JSON-RPC, stdin) ───>│         │                  │
  │                    │                        │                     │ ③ 方法执行：            │
  │                    │                        │                     │   参数校验(topK兜底/截断)│
  │                    │                        │                     │── POST /api/ai/kb/search│
  │                    │                        │                     │   X-API-Key: ak_xxx ───>│
  │                    │                        │                     │<── R{code:0,data:[...]}─│
  │                    │                        │                     │  拼装纯文本结果          │
  │                    │<── tool result (stdout) ────────────────────│                        │
  │                    │                        │                     │                        │
  │                    │── 结果回填上下文 ─────>│                     │                        │
  │                    │                        │ ⑤ 继续生成最终回答   │                        │
  │                    │<── 最终回答 ───────────│                     │                        │
  │<──── 回答 ─────────│                        │                     │                        │
```

### 3.1 第 ① 步：LLM 决策——模型怎么知道该用哪个工具

Host 与 MCP Server 握手时已完成 `tools/list`，四个工具的 **name + description + 参数 JSON Schema** 已注入模型的系统上下文。模型读到用户问题后，依据各工具 description 做匹配："查资料/找依据"命中 `aihub_search_knowledge` 的描述。

注意 description 里的引导设计（AiHubTools.java#L116）：kbId 参数的描述是"知识库 ID，可先用 aihub_list_knowledge_bases 获取"——如果模型不知道 kbId，它会**先调用列表工具**再检索。这就是描述如何引导模型完成多步规划。

### 3.2 第 ② 步：选择工具与传参——Host 侧封装

模型输出 `tool_use`（工具名 + 参数对象）。注意参数可能"缺斤少两"：模型完全可以不传可选参数 topK、甚至传一个字符串形式的 kbId。Host 不做业务校验，原样封装成 JSON-RPC 的 `tools/call` 请求发给 MCP Server（stdio 场景经 stdin，SSE 场景 POST 到 `/mcp/message`）。

### 3.3 第 ③ 步：传参执行——工具方法内的四道工序

参数进入 `AiHubTools.searchKnowledge`（AiHubTools.java#L115-L145），依次经过：

1. **参数校验**：`query` 为空直接返回 `"检索失败：query 不能为空。"`（文字，不是异常）；
2. **默认值兜底**：`topK` 为 null 或 ≤0 时取 `properties.getDefaultTopK()`（5），并 `Math.min(topK, 20)` 封顶；
3. **上游回调**：`api.searchKnowledge(kbId, query, limit)` → `HttpAiHubApi` 组装 `{"kbId":..,"query":..,"topK":..}`（只放有值的字段），POST `/api/ai/kb/search`，带 `X-API-Key` 头；
4. **结果组织**：把 `List<ChunkHit>` 拼成带序号、来源文档名、相似度百分比的纯文本，单条超 800 字截断。

若上游失败，`exchange` 方法把异常翻译成中文（如"鉴权未通过（HTTP 401）…"），工具方法 catch 住 `AiHubCallException` 返回 `"检索失败：<原因>"`。**模型收到这段文字后有能力自行换参数重试或向用户解释**，而不是整个任务崩溃。

### 3.4 第 ④ 步：结果回填上下文

MCP Server 把纯文本结果包装成 `tools/call` 的成功响应返回给 Host。Host 把它作为一条"工具结果消息"追加进对话上下文——对模型而言，就像自己刚才"看到了"知识库里的内容。

### 3.5 第 ⑤ 步：继续生成

模型基于回填的检索结果生成最终回答（引文、总结），Host 呈现给用户。若描述里约定的信号出现（如相似度低于 60%），模型会按描述的指引"如实告知知识库中未找到相关内容，不要强行作答"。

**这趟旅程的三个设计要点**（面试可用）：

- 决策靠 description，执行靠 @ToolParam Schema，两者共同决定工具调用成功率；
- 工具返回的文字是"给模型看的"：可读、可行动、有兜底提示；
- 一次用户问题可能触发 N 轮工具调用（列表→检索→ask），循环直到模型认为可以回答为止——这就是 Agent 循环的最小形态。

---

## 4. 自定义工具开发步骤

目标示例：新增一个工具 `aihub_list_documents`（列出指定知识库下的文档清单），需要改动/新增的文件按顺序列出。

### 第 1 步：在 AiHubApi 接口加方法（mcp-service 模块）

文件：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/AiHubApi.java`

```java
List<DocumentBrief> listDocuments(Long kbId);
```

### 第 2 步：新增/复用模型类（mcp-service 模块）

新文件：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/DocumentBrief.java`

```java
@Data
@JsonIgnoreProperties(ignoreUnknown = true)   // 与同包其他 DTO 保持一致，防服务端加字段打挂
public class DocumentBrief {
    private Long id;
    private String fileName;
    private Integer status;
}
```

### 第 3 步：在 HttpAiHubApi 实现回调（mcp-service 模块）

文件：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/HttpAiHubApi.java`

三处改动：声明一个 `ParameterizedTypeReference<ApiEnvelope<List<DocumentBrief>>>` 常量（模仿 `APP_LIST`，HttpAiHubApi.java#L31-L42）；实现接口方法，按上游真实路径发请求（如 `GET /api/ai/kb/{kbId}/doc`，以网关实际开放 API 为准）；失败处理完全复用现成的 `exchange` 方法，无需新写错误翻译。

### 第 4 步：在 AiHubTools 加 @Tool 方法（mcp-service 模块）

文件：`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/tools/AiHubTools.java`

```java
@Tool(name = "aihub_list_documents",
        description = """
                列出指定知识库下已上传的文档清单（含文档 ID 与状态）。
                上传/删除文档请到 AIHub 管理后台操作，本工具只读。""")
public String listDocuments(
        @ToolParam(description = "知识库 ID，可先用 aihub_list_knowledge_bases 获取")
        Long kbId) {
    try {
        List<DocumentBrief> docs = api.listDocuments(kbId);
        if (docs.isEmpty()) {
            return "该知识库下没有文档。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(docs.size()).append(" 个文档：\n");
        for (DocumentBrief doc : docs) {
            sb.append("- id=").append(doc.getId()).append("，名称：").append(doc.getFileName());
            if (!Integer.valueOf(1).equals(doc.getStatus())) {
                sb.append("（处理中/已停用）");
            }
            sb.append('\n');
        }
        return sb.toString();
    } catch (AiHubCallException e) {
        return "查询文档列表失败：" + e.getMessage();
    }
}
```

遵循既定约定：工具名带 `aihub_` 前缀；描述写"何时用/不该用"；空结果与失败都返回可读文字；catch `AiHubCallException`。

### 第 5 步：不需要改的文件（重要）

- `AiHubMcpConfiguration`：不用改。`MethodToolCallbackProvider` 扫描的是整个 `AiHubTools` 对象，新方法自动进入 `tools/list`；
- `aihub-mcp-sse`、`aihub-mcp-stdio` 两个模块：**一行都不用改**。它们只依赖 `aihub-mcp-service`，这正是三件套拆分的价值；
- 两个 `application.yml`：不用改，除非新工具需要新配置项（那就在 `AiHubMcpProperties` 加字段，并在两个 yml 各加一行带环境变量占位的默认值）。

### 第 6 步：上游核对清单

新工具调用的开放 API 必须真实存在于网关路由之后，且 API Key 对应租户有权限访问；同时确认响应字段与 `DocumentBrief` 声明兼容（多余字段会被 `ignoreUnknown` 忽略，但缺字段会得到 null）。

### 第 7 步：验证

重启 sse 版后用 MCP Inspector 或任一 MCP 客户端连 `http://127.0.0.1:8090/sse`，确认 `tools/list` 中出现新工具，再手动 `tools/call` 验证返回文本；stdio 版由宿主进程重启拉起后同样验证。有条件时为 `AiHubTools` 补一个用假 `AiHubApi` 实现的单测——接口抽象在第一步就为此铺好了路。

**改动文件汇总表**：

| 序 | 文件 | 动作 |
|---|---|---|
| 1 | aihub-mcp-service/.../api/AiHubApi.java | 接口加方法 |
| 2 | aihub-mcp-service/.../model/DocumentBrief.java | 新增 DTO |
| 3 | aihub-mcp-service/.../api/HttpAiHubApi.java | 实现回调 |
| 4 | aihub-mcp-service/.../tools/AiHubTools.java | 新增 @Tool 方法 |
| — | AiHubMcpConfiguration / 两个传输模块 | 无需改动 |

---

## 5. SSE 传输与 stdio 传输对比

两个传输模块共享同一套工具实现，差异全部在"JSON-RPC 消息怎么走"。

| 维度 | aihub-mcp-sse | aihub-mcp-stdio |
|---|---|---|
| 传输载体 | HTTP 长连接（SSE 下行 + POST 上行） | 进程的 stdin/stdout |
| 网络端口 | 8090（默认绑 127.0.0.1） | 无端口 |
| 进程形态 | 常驻 Web 服务 | 宿主进程按需拉起的子进程 |
| 启动方式 | `java -jar aihub-mcp-sse.jar` 手动启动 | 由 Claude Desktop / IDE 等宿主配置 command + env 自动拉起 |
| MCP starter | `spring-ai-starter-mcp-server-webmvc` | `spring-ai-starter-mcp-server`（无 web） |
| 关键配置 | `protocol: sse`、`sse-endpoint: /sse`、`sse-message-endpoint: /mcp/message`、`keep-alive-interval: 30s` | `stdio: true`、`banner-mode: off`、`web-application-type: none` |
| 通道鉴权 | 可开 Bearer 令牌门禁（`aihub.mcp.auth.*`，常量时间比较 + fail-fast） | 无需通道鉴权——管道由宿主进程创建，天然私密 |
| stdout 纪律 | 无所谓（stdout 不是协议通道，banner 可保留） | **绝对干净**：banner/日志混入 stdout 即协议崩溃 |
| 多客户端 | 一个服务实例可同时服务多个远程客户端 | 一个子进程实例对应一个宿主会话 |
| 典型场景 | 团队共享的远程 MCP 服务、跨机接入 | 个人本地接入 Claude Desktop / IDE 插件 |
| 生命周期 | 独立部署、独立升级、有 actuator/prometheus 监控 | 随宿主进程起止，宿主关管道即结束 |
| 部署前提 | 网络可达 AIHub 网关（可跨机） | 与宿主同机，通常与网关同机（base-url 默认 127.0.0.1:8080） |

**如何选择**：给一台机器上的个人 Claude Desktop 用 → stdio（零端口暴露、零通道鉴权成本）；给团队/跨网络的多客户端共享 → SSE（先开 `aihub.mcp.auth` 门禁再考虑绑 0.0.0.0）。两者共用 `aihub-mcp-service`，工具逻辑与传输形态完全解耦，甚至可以两个都部署。

---

## 6. 本章面试三问与参考回答

### Q1：MCP 和 Function Calling 是什么关系？有了 Function Calling 为什么还要 MCP？

**参考回答**：Function Calling 是模型厂商提供的"模型输出结构化调用意图"的能力，它解决的是**模型这一端**怎么表达"我想调某个函数"；MCP 解决的是**工具生态那一端**怎么把函数标准化地暴露出来。两者是互补而非竞争：MCP Server 把工具按 `tools/list`（名称、描述、参数 Schema）暴露，MCP Client 把这份清单转成模型可理解的 Function 定义注入上下文，模型发起 Function Calling 后，Host 把调用意图翻译成 MCP 的 `tools/call` 发给 Server。没有 MCP，M 个应用对接 N 个工具要写 M×N 份胶水；有了 MCP 收敛为 M+N。以我们项目为例：`AiHubTools` 用 `@Tool` 注解声明四个工具，Spring AI 的 MCP Server starter 负责把 `ToolCallbackProvider` 转成 `tools/list` 结果和 `tools/call` 处理器，具体的模型侧 Function Calling 由宿主（如 Claude Desktop）完成——我们的代码里没有任何一行依赖某个具体模型厂商。

### Q2：你的 MCP 三件套为什么拆成三个模块？直接在一个模块里加个开关不行吗？

**参考回答**：拆分的核心是把"工具实现"与"传输方式"彻底解耦。`aihub-mcp-service` 只含 `@Tool` 工具、HTTP 出口与配置，不引任何传输 starter，也不依赖带 servlet 容器的 `aihub-common`；`aihub-mcp-sse` 引 webmvc 版 starter 提供远程 SSE 接入（8090 端口），`aihub-mcp-stdio` 引无 web 版 starter 供宿主进程以子进程方式拉起。如果塞在一个模块里，stdio 进程会被拖进 servlet 容器（要么背着一个用不上的 Tomcat，要么处处写条件开关），SSE 服务则不得不与 CLI 依赖纠缠；而且新增传输方式（比如 streamable-http）要动同一个模块，回归面大。拆开后，新增工具只改 `aihub-mcp-service` 四处（接口、DTO、实现、@Tool 方法），两个传输模块一行不动；两个传输模块的启动类 `scanBasePackages = "com.aihub.mcp"` 上扫一层就复用了同一套装配。代价是模块数量多一点，但换来的是依赖纯净、可单测（`AiHubApi` 是接口，工具层可以脱离网络测试）与演进自由。

### Q3：stdio 传输的 MCP Server 一连上客户端就崩，你会怎么排查？

**参考回答**：stdio 模式下 stdout 就是 JSON-RPC 通道，第一怀疑对象就是**任何混进 stdout 的非协议输出**。排查路径：① 检查是否关了 Spring Boot banner（我们项目里 `aihub/aihub-mcp-stdio/src/main/resources/application.yml` 显式配了 `banner-mode: "off"`，注释写明 banner 的 ASCII 图足以让客户端第一条 JSON-RPC 响应解析失败）；② 检查日志配置是否把日志引到了 stderr 或文件——我们专门写了 logback-spring.xml 处理这件事；③ 启动类是否显式 `WebApplicationType.NONE`，防止 classpath 变化后悄悄起 Tomcat（Tomcat 的启动日志也会打到 stdout）；④ 用 MCP Inspector 手动连一次，观察 stderr 与 stdout 的原始输出定位混入内容。这个问题的底层认知是：**stdio 传输中"谁可以往 stdout 写"必须被严格管控**，凡是框架默认打印行为（banner、日志、进度条）都要逐个审一遍。这也是我们在两个传输模块的配置注释里重点标注的坑。

---

## 附：本章引用文件清单

| 文件 | 说明 |
|---|---|
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/tools/AiHubTools.java | 4 个 @Tool 工具定义 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/config/AiHubMcpConfiguration.java | 工具装配（3 个 Bean） |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/config/AiHubMcpProperties.java | aihub.mcp.* 配置 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/AiHubApi.java | 上游能力端口接口 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/HttpAiHubApi.java | RestClient 实现 + 错误翻译 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/AiHubCallException.java | 非受检业务异常 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/ApiEnvelope.java | R\<T\> 客户端镜像 |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/AppBrief.java | 应用概要 DTO |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/KbBrief.java | 知识库概要 DTO |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/ChunkHit.java | 检索命中 DTO |
| aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/ChatAnswer.java | 对话结果 DTO |
| aihub/aihub-mcp-service/pom.xml | 协议无关依赖声明 |
| aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/AiHubMcpSseApplication.java | SSE 版启动类 |
| aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/McpChannelAuthFilter.java | 通道门禁过滤器 |
| aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/McpChannelAuthProperties.java | 门禁配置 |
| aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/McpChannelAuthConfiguration.java | 门禁装配（fail-fast） |
| aihub/aihub-mcp-sse/pom.xml | webmvc starter 依赖 |
| aihub/aihub-mcp-sse/src/main/resources/application.yml | 8090 端口与 MCP 端点配置 |
| aihub/aihub-mcp-stdio/src/main/java/com/aihub/mcp/stdio/AiHubMcpStdioApplication.java | stdio 版启动类 |
| aihub/aihub-mcp-stdio/pom.xml | 无 web starter 依赖 |
| aihub/aihub-mcp-stdio/src/main/resources/application.yml | stdio 开关与 stdout 纪律配置 |
| aihub/aihub-mcp-stdio/src/main/resources/logback-spring.xml | 日志引离 stdout |
