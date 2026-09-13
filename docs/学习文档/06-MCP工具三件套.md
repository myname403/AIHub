# 06 · MCP 工具三件套 —— 学习文档

> 源码位置：`aihub/aihub-mcp-service`（纯业务，11 类）+ `aihub-mcp-sse`（传输 9001）+ `aihub-mcp-stdio`（传输 CLI）
> 配套代码注释已增强（本模块原有注释质量已很高，重点看设计思想）。

---

## 1. MCP 是什么？为什么需要它？

**MCP（Model Context Protocol）**是 Anthropic 提出的开放协议：让 AI 应用（Claude Desktop、IDE 插件、任意 Agent）以**标准方式**发现和调用外部工具。

```
没有 MCP：每个 AI 应用 × 每个工具 = 一份定制集成（N×M 灾难）
有 MCP：  工具方实现一次 MCP Server，任何 MCP 客户端都能用（N+M）
```

**本项目的三件套设计（ADR-3，务必理解）**：

```
┌─────────────────────┐
│  aihub-mcp-service   │  纯业务：4 个 @Tool 工具 + 调 AIHub 的 HTTP 客户端
│  （协议无关的库）      │  ← 不含任何传输代码，可单测
└──────┬──────────────┘
       │ 被两个传输模块复用
   ┌───┴─────────┬───────────────┐
   ▼             ▼               
aihub-mcp-sse  aihub-mcp-stdio  
SSE 传输(9001)  stdio 传输(CLI)  
远程：IDE/网页   本地：Claude Desktop
```

**SSE vs stdio 两种传输**：

| | SSE | stdio |
|---|---|---|
| 形态 | HTTP 服务（端口 9001） | 普通进程，stdin/stdout 传 JSON-RPC |
| 场景 | 远程客户端（浏览器、IDE 插件） | 本地桌面（Claude Desktop 直接拉起子进程） |
| 鉴权 | McpChannelAuthFilter（Bearer token） | 无需（本机管道，随宿主进程生命周期） |

一套工具实现，两种传输复用——新增传输方式时业务代码一行不改。

---

## 2. 一次 MCP 工具调用的完整链路

```
1. 客户端（Claude Desktop）连接 MCP Server → tools/list
   → 框架收集容器里的 ToolCallbackProvider Bean → 返回 4 个工具的名称+描述
2. 用户问 Claude："帮我查一下产品手册里的退货政策"
3. Claude 决定调用 aihub_search_knowledge（凭 description 判断）
4. JSON-RPC tools/call → MCP Server 执行 AiHubTools.searchKnowledge
5. HttpAiHubApi 经网关调 AIHub（带 X-API-Key 头，网关验 Key → 写租户头）
6. AI 服务做 RAG 检索返回片段 → AiHubTools 组织成"给人看"的文本
7. 文本回到 Claude → Claude 基于它回答用户
```

**鉴权链**：MCP Server 不是终端用户，用**开放 API Key** 标识"哪个租户在通过 MCP 使用 AIHub"（X-API-Key 头 → 网关 ApiKeyVerifier → 平台反查租户）。Key 明文只存在于 MCP Server 的配置里。

---

## 3. 核心类精讲

### 3.1 AiHubTools —— 工具集（本模块的心脏）

4 个 `@Tool` 工具：`aihub_list_applications`、`aihub_list_knowledge_bases`、`aihub_search_knowledge`、`aihub_ask`。

**四个刻意的设计选择（类注释原文，值得背下来）**：
1. **全部返回 String**：MCP 结果要被任意客户端消费，纯文本最不会出错（返回 POJO 依赖两端 JSON Schema 实现一致，跨实现易踩坑）；
2. **错误转成文字不抛异常**：抛异常=JSON-RPC error，模型通常直接放弃；"鉴权未通过，请检查 API Key"这类提示能让模型继续推理；
3. **工具描述像说明书**：description 是模型选工具的唯一依据，含糊描述=反复试错浪费 token。甚至写明"低于 60% 相似度应如实告知用户未找到"——**用提示词约束模型行为**；
4. **`@ToolParam(description, required)`**：参数说明直接决定模型传参质量；required=false 的参数代码里给默认值兜底。

其他细节：`truncate` 截断超长分片（防撑爆上下文）；ask 返回带 conversationId（支持追问续会话）。

### 3.2 AiHubApi / HttpAiHubApi —— 能力端口

接口抽象（可脱离网络单测）+ RestClient 实现。**exchange() 统一处理两类失败**：传输层异常（连不上/超时/非 2xx）与业务码非 0。`describe()` 把 HTTP 状态码翻成人话（模型看"401"会瞎猜，看"请检查 API Key"才知道怎么办）——**错误信息是为模型读者写的**。

`ParameterizedTypeReference`：RestClient 反序列化泛型（`List<AppBrief>`）必须用它绕过 Java 类型擦除——直接 `body(List.class)` 拿到的是 `List<Map>`。

### 3.3 AiHubMcpConfiguration —— 装配

三个 @Bean：AiHubApi（RestClient 组装+脱敏日志）、AiHubTools、**ToolCallbackProvider**（把 @Tool 方法注册为 MCP 工具——容器里有这个 Bean，工具才会出现在 tools/list）。
`@EnableConfigurationProperties`：激活 @ConfigurationProperties 配置类。

### 3.4 McpChannelAuthFilter —— SSE 通道门禁

Servlet `OncePerRequestFilter`（比拦截器更靠前，因为 /mcp/** 是框架端点不走 Controller）。要点：Bearer token + **MessageDigest.isEqual 常量时间比较**（防时序攻击，与 ApiKeyCodec 同款手法）；401 + JSON 明确报错（连接期就失败，不挂着等工具调用才炸）；enabled=false 完全放行（本地默认零行为变化）。

### 3.5 两个启动类

- **SSE**：`@SpringBootApplication(scanBasePackages = "com.aihub.mcp")` —— 工具实现在 mcp-service 模块，**必须往上扫一层**否则拿不到配置（初学者最易犯）；
- **stdio**：`WebApplicationType.NONE` 显式声明非 Web 应用——stdio 靠 stdin/stdout，进程里不能有 HTTP 服务器抢 stdout（否则 JSON-RPC 流被污染）。

---

## 4. 本模块注解词典

| 注解 | 作用 | 常见坑 |
|---|---|---|
| `@Tool(name, description)` | 方法注册为 MCP 工具 | description 决定模型是否选用，务必写清"何时用" |
| `@ToolParam(description, required)` | 参数说明 | required=false 时代码要处理 null |
| `@Configuration` + `@Bean` | 装配类 | ToolCallbackProvider Bean 是工具暴露的开关 |
| `@EnableConfigurationProperties` | 激活配置绑定类 | 忘写 → @ConfigurationProperties 不生效 |
| `@SpringBootApplication(scanBasePackages)` | 自定义扫描范围 | 多模块复用时默认只扫启动类所在包 |
| `WebApplicationType.NONE` | 非 Web 应用 | stdio 必须设，否则 HTTP 容器污染 stdout |

---

## 5. 编码规范与注意事项

1. **工具结果面向模型写作**：错误信息、截断说明、相似度提示，都假设读者是 LLM；
2. **工具永不抛异常**，错误转可读文本；
3. **返回 String**，不用复杂结构；
4. **密钥脱敏**：日志只打 maskApiKey 前缀，绝不完整回显；
5. **业务与传输分离**（ADR-3）：工具写一份，传输随便换。

---

## 6. 学习自测

1. MCP 解决什么问题？N×M 变 N+M 怎么理解？
2. SSE 和 stdio 两种传输各适合什么场景？为什么 stdio 必须设 WebApplicationType.NONE？
3. 为什么工具返回 String 而不是对象？为什么错误转文字而不是抛异常？
4. `@Tool` 的 description 为什么值得花大力气写？举出本模块用描述约束模型行为的例子。
5. MCP Server 如何标识"我是哪个租户"？这条链路上 Key 明文出现在哪些位置？
6. `MessageDigest.isEqual` 在本项目出现了几次？各防什么攻击？
7. `scanBasePackages` 为什么必须写 `com.aihub.mcp` 而不是默认值？
