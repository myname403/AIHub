# 05 · AI 服务 aihub-ai-service —— 学习文档

> 源码位置：`aihub/aihub-ai-service`（端口 8082，约 149 个 main 类 + 23 个测试类，本项目最大模块）
> 配套代码注释已对核心链路逐行增强（未标注的类遵循同样的模式）。

---

## 1. 这个服务是干什么的？

整个产品的"**能力核心**"：模型网关、对话、RAG 知识库、工具调用、Agent 编排、会话记忆、产物存储，全部在这里。

**为什么这么"胖"却不拆成多个微服务？**（重要架构决策，docs/07 有完整论证）
拆开会引入三大难题：① 流式响应跨服务传递；② Agent 长任务跨服务编排；③ 分布式事务。
所以采用"**模块化单体**"：进程只有一个，但内部用严格的分层 + ArchUnit 测试卡口保持微服务级的整洁——将来要拆，沿着包边界切就行。

---

## 2. DDD 分层结构（先背下来再读代码）

```
web/            表现层：Controller、三种流式通道（SSE/NDJSON/WS）
   ↓ 只依赖
application/    编排层：ChatAppService、KnowledgeAppService（业务流程编排）
   ↓ 只依赖
domain/         领域层：model/（值对象 record）+ spi/（接口，"我要什么能力"）
   ↑ 实现
infra/          基础设施层：
   ├── ai/        Spring AI 实现（ChatClientFactory、执行器、RAG 检索、Agent）
   ├── persistence/  MySQL 持久化（16 个 DO + 15 个 Mapper + Db* 仓库实现）
   ├── browser/   CDP 浏览器驱动（Agent 网页操作）
   ├── storage/   文件存储（本地 / MinIO）
   └── metrics/   监控指标（Micrometer → Prometheus）
```

**依赖方向永远朝内**（web→application→domain←infra）。这不是口头约定——`src/test/.../arch/ArchitectureTest.java` 用 **ArchUnit** 在每次构建时强制检查，谁违反分层测试就红：

| 规则 | 含义 |
|---|---|
| domain 不依赖任何框架/上层 | 领域模型纯净，可脱离 Spring 单测 |
| application 不碰 infra/web | 编排层只认接口 |
| web 不碰 Spring AI/infra | 表现层只做传输适配 |
| infra 各子包不横向依赖 | ai 与 persistence 解耦 |

**为什么要这么严**：分层腐化都是从"图省事跨层调用"开始的，测试卡口让腐化在 CI 就被拦下。

---

## 3. 前置知识：Spring AI 核心概念

| 概念 | 是什么 | 本项目对应 |
|---|---|---|
| `ChatModel` | 底层模型调用封装（指向哪家 API、什么密钥）——"电话线" | `OpenAiCompatibleChatModelResolver` 按配置构建 |
| `ChatClient` | 高级客户端：挂系统提示词、记忆、工具——"装好助理的电话机" | `ChatClientFactory` 按 (租户,应用,场景) 装配+缓存 |
| `Advisor` | 请求/响应拦截器链（AOP 思想） | `MessageChatMemoryAdvisor`（自动注入历史）、`AuditAdvisor`（审计） |
| `ChatMemory` | 对话历史存取接口 | `DbChatMemory`（存 MySQL） |
| `ToolCallback` / `@Tool` | 给模型注册"可调用函数"（function calling） | `TimeTools`/`KnowledgeTools`/`BrowserTools` + MCP 外部工具 |
| `Flux<ChatResponse>` | 流式响应（事件流） | `SpringAiChatExecutor.stream()` |

**function calling 工作原理**（初学者最容易懵的点）：
```
1. 我们告诉模型："你有这几个函数可调"（名称+参数说明）
2. 模型判断需要时，回复的不是答案而是"我要调 searchKnowledge(参数...)"
3. Spring AI 框架执行对应 Java 方法，把结果喂回模型
4. 模型基于工具结果生成最终回答
```
模型自己"决定"何时调工具——这就是 Agent 能自主操作的基础。

---

## 4. 一次对话的完整链路（核心中的核心）

以 H5 的 SSE 流式对话为例：

```
前端 POST /api/ai/chat/sse {appId, conversationId, message}
  ↓ 网关鉴权写租户头 → AI 服务拦截器写 TenantContext
ChatController.chatSse
  ├── turn(): 从上下文取 tenantId/userId，组装 ChatTurn（record）
  ├── streamExecutor.submit(TraceContext.wrap(...))   ← 流任务丢独立线程池，容器线程立刻释放
  └── 返回 SseEmitter（Spring 知道"这个响应稍后慢慢写"）
ChatAppService.chatStream（编排层）
  ├── checkQuotaOrEmit → platformClient.checkAndConsume（扣 1 次数；失败降级放行）
  └── chatExecutor.stream(turn, sink)
SpringAiChatExecutor.stream（infra 层）
  ├── AiCallContext.set(...)           ← 给 Advisor/工具用的线程上下文
  ├── augment(): RAG 检索（应用绑了知识库才做）
  │     knowledgeRetriever.retrieve(topK, threshold) → 拼"开卷考试"提示词 → 发 rag.sources 事件
  ├── client.prompt().user(...).advisors(记忆ID/审计参数).stream()
  │     ├── MemoryAdvisor 自动把历史对话注入 → 调模型 → token 逐个到达
  │     └── doOnNext: 每 token 拼全文 + sink.emit(token 事件)  ← 前端逐字看到回答
  ├── blockLast() 等流结束 → 取 usage（token 数）
  ├── referenceStore.save（引用溯源）
  ├── aiMetrics.recordChat（Prometheus 指标）
  └── sink.emit(msg.end) / finally sink.close()
回到 ChatAppService：reportUsage（流水上报）→ consumeTokens（token 补扣）
```

**流式事件时间线**（`StreamEvent` 常量）：`msg.start → [rag.sources] → token×N → [tool.start/tool.end]×N → [agent.step]×N → [artifact] → msg.end`，任何时刻可发 `error`，空闲发 `ping`。

---

## 5. 核心类精讲（按包）

### 5.1 web 包 —— 表现层

| 类 | 讲解要点 |
|---|---|
| `ChatController` | 三种通道同一编排：SSE（`SseEmitter`）/ NDJSON（`StreamingResponseBody` + 小程序禁 gzip 三件套）/ 同步。**流任务与容器线程隔离**（自建线程池）+ `TraceContext.wrap` 跨线程带链路 ID |
| `SseStreamSink` | StreamSink 的 SSE 实现。`volatile closed` 保证多线程可见性；写失败静默标记关闭（客户端断开是常态不是错误） |
| `NdjsonStreamSink` | 每行一个 JSON + `\n`，flush 每帧；小程序 enableChunked 按 \n 切分处理半包 |
| `WsStreamSink` / `AgentWebSocketHandler` / `WebSocketConfig` | WebSocket 通道（Agent 长任务双向通信）。WS 握手绕过网关，所以握手时要自己用 `JwtVerifier` 验令牌 |
| `AgentController` / `KnowledgeController` / `AppController` / `ModelController` / `ArtifactController` | Agent 任务管理 / 知识库与文档 / 应用配置 / 模型配置 / 产物下载，均为标准 REST 三行式 |

### 5.2 application 包 —— 编排层

**`ChatAppService`（最值得精读）**：
- 只依赖接口（ChatExecutor/PlatformClient/AgentRegistry/MessageReferenceStore）；
- 两条路径：chat（配额→调模型→上报→补扣）、agent（配额→扣任务费→PlanningAgent）；
- **降级哲学**：配额/统计类远程调用失败一律放行（AI 能力不因依赖故障不可用），闸门只在平台"明确拒绝"时生效；
- token 是**事后补扣**（调完模型才知道用量），补扣失败只记日志。

**`KnowledgeAppService`**：知识库 CRUD + 文档入库编排（异步任务：上传→切片→向量化→入库，`IngestTask` 记录状态，前端轮询进度）。

### 5.3 domain 包 —— 领域层（全是 record 和接口）

- `model/`：30 个 record（ChatTurn、StreamEvent、AgentTask、AgentBudget…）——**不可变值对象**，无框架依赖，ArchUnit 保证纯净；
- `spi/`：20+ 接口（ChatExecutor、StreamSink、KnowledgeRetriever、ArtifactStore…）——"领域需要什么能力"，infra 来实现。**这套接口就是防腐层**：换掉 Spring AI / Redis / MinIO 时领域层一行不改。

**`AgentBudget`（三重预算）**：maxSubTasks（防任务爆炸）+ maxTokens（防成本失控）+ timeoutMs（防僵死）。用 `AtomicLong` 做无锁原子累加（多线程并发安全）。实例沿子任务链共享引用，子 Agent 消耗计入总预算。超限抛 `AgentBudgetExceededException`——**Agent 没有预算控制就是一台碎钞机**。

### 5.4 infra/ai 包 —— AI 基础设施

| 类 | 讲解要点 |
|---|---|
| `ChatClientFactory` | ★ 按 (tenant,app,scene) 装配+缓存（TTL 过期支持配置热更新）；主备模型自动降级；工具统一挂载并套 `AuditedToolCallback` 审计包装；`ObjectProvider<X>` 处理"可选 Bean"（浏览器工具未启用时自然缺席） |
| `SpringAiChatExecutor` | call（同步）/stream（Flux+doOnNext+blockLast）；RAG augment（检索→受限上下文→防幻觉约束→引用事件）；失败也记指标 |
| `DbChatMemory` | 实现 Spring AI 的 ChatMemory 接口，历史存 MySQL（按 tenant+conversation 键） |
| `DbModelGateway` | 模型路由（主/备模型码）从库读取 |
| `OpenAiCompatibleChatModelResolver` | 用租户配置的 endpoint（DeepSeek 等 OpenAI 兼容 API）构建 ChatModel，密钥用 AesGcmTextCipher 解密 |
| `RedisKnowledgeIndexer/Retriever` | RAG 的写/读两侧：文档切片 → 向量化 → 存 Redis Stack（向量库）；查询时向量相似度检索 topK |
| `AuditAdvisor` | Advisor 拦截请求/响应，把每次模型调用写审计日志（工具调用、租户、耗时） |

**RAG 原理图（零基础版）**：
```
入库：文档 → TextChunker 切片(带重叠) → Embedding 模型向量化 → Redis Stack 向量库
检索：用户问题 → 向量化 → 余弦相似度找 topK 最相关片段 → 拼进提示词
生成：模型"开卷考试"，只依据片段作答，标注 [n] 引用 → 前端渲染引用角标
```

### 5.5 infra/ai/agent 包 —— Agent 家族

`BaseAgent`（模板方法模式：llm/预算/step/fallback 统一在基类）之下 6 个实现：

| Agent | 职责 |
|---|---|
| `PlanningAgent` | 入口：LLM 把目标拆成 `[{agent,task}]` JSON 子任务链 → 逐个派发 → 汇总。每步检查取消信号与预算 |
| `TableAgent` | 数据 → HTML 表格页面 |
| `ChartAgent` | 数据 → ECharts 图表页面 |
| `HtmlDocAgent` | 网页/文档/总结（默认兜底） |
| `GenerationAgent` | 纯文本生成 |
| `BrowserAgent` | 驱动真实 Chrome（CDP 协议）读网页、截图 |

`AgentRegistryImpl` 按名字注册/查找（Spring 注入所有 Agent Bean 建 map）。

**PlanningAgent 值得学的细节**：让 LLM 只返回 JSON（system prompt 强约束）+ `extractJsonArray` 容错截取 + 解析失败退化为单任务（**永远给 LLM 输出留降级路径**）；每步 emit 事件同时落库（前端时间线 + 事后审计回放）。

### 5.6 infra 其余包

- `persistence/`：16 个 DO（`@TableName/@TableId/@TableLogic`，与 platform 的 DO 同模式）+ 15 个 Mapper（BaseMapper 白嫖 CRUD）+ `Db*` 类实现 domain SPI（**"DB 仓库适配器"**）；
- `browser/`：ChromeLauncher 启动本机 Chrome → WebSocketCdpTransport 走 CDP 协议（Chrome DevTools Protocol）→ PageAnnotator 给页面元素编号让模型能"看到"并操作；
- `storage/`：`StorageConfiguration` 按配置选 Local 或 MinIO 实现（条件装配）；
- `metrics/`：Micrometer 计数器/计时器 → /actuator/prometheus → Prometheus + Grafana。

---

## 6. 本模块注解词典（新增部分）

| 注解 | 出现位置 | 作用 | 常见坑 |
|---|---|---|---|
| `@MapperScan("包名")` | 启动类 | 批量注册 Mapper 为 MyBatis 代理 Bean | 包路径写错 → Mapper 注入报 NoSuchBean |
| `@EnableFeignClients(basePackages)` | 启动类 | 激活 Feign 契约接口 | 忘写 → PlatformClient 注入失败 |
| `@Lazy` | PlanningAgent 构造器参数 | 延迟注入，打破循环依赖（PlanningAgent ↔ AgentRegistry） | 循环依赖报错时的正解之一 |
| `@Autowired(required=false)` | 可选依赖 | Bean 不存在时注入 null 而不报错 | 更现代写法是构造器 `ObjectProvider<T>` |
| `@ConfigurationProperties` | XxxProperties | 批量配置绑定 + Nacos 热重绑定 | 与 @Value 区别：@Value 不参与热更新 |
| `@Tool` | 工具方法 | 注册为模型可调用函数 | 参数要有 @ToolParam 描述，模型靠描述决定怎么传参 |
| `@ConditionalOnProperty` | 存储配置类 | 配置开关决定装配哪个 Bean（Local/MinIO） | — |
| `@ServerEndpoint`/WebSocket 相关 | ws 包 | WS 端点（本项目用 Spring WebSocketHandler 风格） | 握手需自行验 JWT |
| `@Test` + ArchUnit | ArchitectureTest | 架构规则测试 | 违反规则 = 构建失败 |

---

## 7. 编码规范（本模块示范）

1. **依赖方向朝内**，ArchUnit 强制；新代码先想清楚放哪一层；
2. **领域模型用 record**，接口放 spi，实现放 infra（防腐层）；
3. **Controller 三行式**；业务编排进 application；技术细节锁在 infra；
4. **LLM 输出永远不信任**：JSON 解析要 try-catch + 降级路径；
5. **Agent 必须有预算**：新建 Agent 不接 AgentBudget 等于埋雷；
6. **可选依赖用 ObjectProvider**，不搞硬依赖；
7. **指标埋点**：成功失败都要记（否则成功率失真）。

---

## 8. 常见坑速查

| 坑 | 现象 | 解法 |
|---|---|---|
| ArchUnit 测试红 | CI 失败 | 你跨层了，把依赖改成接口 |
| 流式任务丢线程池没带 traceId | 日志断链 | TraceContext.wrap() |
| LLM 返回的 JSON 带 markdown 围栏 | 解析失败 | extractJsonArray 截取 + 降级 |
| Agent 忘了预算 | 无限循环烧 token | AgentBudget 三重预算 |
| SseEmitter 超时 | 长回答被掐断 | 合理设置 emitter 超时 + ping 心跳 |
| 换模型供应商 | 大量改动 | 走 OpenAI 兼容协议 + resolver 隔离 |
| Redis 没开 search 模块 | 向量检索报错 | 必须用 Redis Stack（普通 Redis 不行） |

---

## 9. 学习自测

1. 画出本服务 DDD 四层及依赖方向；ArchUnit 四条规则分别防什么？
2. ChatModel 和 ChatClient 的区别？为什么 ChatClientFactory 要按 (tenant,app,scene) 缓存？
3. function calling 的完整交互过程？`@Tool` 方法靠什么让模型知道怎么传参？
4. RAG 的入库和检索两侧分别做了什么？为什么提示词要加"禁止编造"约束？
5. 流式对话为什么要把任务丢进独立线程池？TraceContext.wrap 解决什么问题？
6. token 为什么"事后补扣"而不是前置？补扣失败为什么只记日志？
7. AgentBudget 三重预算分别防什么？AtomicLong 解决什么并发问题？
8. PlanningAgent 拿到 LLM 的 JSON 后做了哪些容错？为什么必须容错？
9. domain/spi 的接口体系（防腐层）带来什么实际好处？举一个"换组件"的例子。

答完进入 `aihub-mcp` 三件套。
