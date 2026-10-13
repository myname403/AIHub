# 04｜对话与 RAG 核心类逐行精讲（源码级）

> 前三篇讲"有哪些类、链路怎么走"，本篇**逐行拆源码**——挑 6 组最核心的类，
> 讲透每一段"为什么这么写、不这么写会出什么事"。阅读姿势：左边开源码，右边开本文。
> Agent 体系单独有一篇（05），本篇聚焦对话 / RAG / 装配 / 流式入口。

---

## 类 1｜ChatClientFactory —— 装配车间（全服务最核心，268 行）

**它解决的问题**：每个 (租户, 应用, 场景, 模型) 组合用的模型、系统提示词、工具都不同 →
ChatClient 不能全局共用一个；但每次对话都重新装配又太贵（查库 + 建对象图）。
**答案：按 key 缓存 + TTL 过期**——"纯函数式装配：配置进、Client 出"。

### ① 先认清 Spring AI 的四层物件（类注释精华，必背）

```
ChatModel    = 电话线（连哪家 API、什么密钥）
ChatClient   = 装了通讯录和助理的电话机（挂系统提示词/记忆/工具）
Advisor      = 录音笔 + 自动拨号规则（请求/响应拦截链，AOP 思想）
ToolCallback = 翻译官（function calling：模型判断需要时执行函数、把结果喂回模型）
```

### ② 双重检查锁缓存（create 方法，134-150 行）

```java
CacheEntry entry = cache.get(key);                    // 第一次查：无锁快路径（绝大多数命中）
if (entry == null || now - entry.createdAt() > ttlMillis) {
    synchronized (cache) {                            // 只有"可能要重建"时才加锁
        entry = cache.get(key);                       // 第二次查：防两线程重复装配
        if (entry == null || ...) { entry = build(...); cache.put(key, entry); }
    }
}
```

- **为什么查两次**：第一次无锁为**性能**（读多写少不加锁）；第二次有锁为**正确性**
  （两线程可能同时判定过期，不加第二次检查就会装配两遍）。这是 **DCL（Double-Checked Locking）**
  标准形态，面试高频；
- **TTL 每次现读**（`chatClientProperties.getCacheSeconds()`）：Nacos 改了 TTL，下一次过期判断就用新值——
  **惰性生效**，不重启。缓存 TTL 的存在意义：DB 里改了模型路由/系统提示词，最多 60 秒后自动轮换生效。

### ③ 缓存 key 为什么把 modelCode 也编进去（162-165 行）

```java
return tenantId + ":" + appId + ":" + scene + ":" + (modelCode == null ? "" : modelCode);
```

前端模型切换器指定了 modelCode 时，同一租户不同模型的 Client **各自缓存互不污染**——
否则切了模型还命中旧 Client 用着老模型。

### ④ 降级选模（resolveModel，244-266 行）—— 高可用关键

```java
for (String code : new String[]{route.primaryModelCode(), route.fallbackModelCode()}) {
    // 主模型 → 备用模型，逐个尝试构建，哪个能用哪个
}
// 两者皆不可用 → 退回 Spring Boot 自动装配的默认模型；还没有 → 抛 MODEL_UNAVAILABLE
```

**主备降级**：主模型挂了自动切备用，用户无感。这是"模型网关"含金量的直接体现。
指定模型（overrideModelCode）时**绕过场景路由**直接按编码解析，找不到**明确报错**而不是静默换模型——
用户点名要的东西不能偷偷替换，和"空 Key 不解密"（类 3）是同一哲学的两面。

### ⑤ 两个"供应商特判"（血泪细节）

- **Ollama 追加 `/no_think`**（185-190 行）：Ollama 的 Qwen3 推理模型默认把答案输出进
  `reasoning` 字段、`content` 恒空，Spring AI 读不到内容 → 在系统提示词尾部追加官方软开关
  `/no_think` 关闭思考链。**只对 `providerCode=ollama` 追加**，DeepSeek 等云供应商不受影响；
- **data-key 故意用 `@Value` 不热更**（75-77 行）：注释原话——"敏感凭据保留 @Value：
  它是**部署期注入的密钥而非可热调参数，不应随 Nacos 动态变化**"。
  热更新对密钥是风险不是便利。"哪些配置该热更、哪些不该"的取舍，这个字段就是标答。

### ⑥ 工具挂载（buildToolCallbacks，211-234 行）

```java
内置工具（TimeTools + KnowledgeTools 固定挂载；BrowserTools 仅 aihub.browser.enabled=true 时存在）
  → MethodToolCallbackProvider 收集 @Tool 方法
  → 每个套 AuditedToolCallback(callback, store, "local", metrics)     // 来源标签 local
MCP 外部工具（ObjectProvider<ToolCallbackProvider>，只依赖抽象不 import MCP 类型）
  → orderedStream() 逐个套 AuditedToolCallback(..., "mcp", ...)        // 来源标签 mcp
```

两个妙处：
1. **未接 MCP 时 `orderedStream()` 为空，自然降级为"仅内置工具"**——一行 if 都没写；
2. 内置/外部工具**统一套同一个审计包装器**，来源用标签区分——一次装饰，
   `ai_tool_call_log` 落库 + Micrometer 计数全都有（谁调了什么工具、多久、成没成功，一张表可查）。

---

## 类 2｜SpringAiChatExecutor —— 对话引擎室（263 行）

**它是防腐层**：domain 定义 `ChatExecutor` 接口，本类用 Spring AI 实现，
**Spring AI 的类型全被关在 infra.ai 包里**——业务层只依赖接口，将来升级 Spring AI 2.0 只改这里。

### call（同步）与 stream（流式）的骨架对比

两者同构：`AiCallContext.set → create client → augment(RAG) → 挂 advisors 参数 → 调用 → finally 清理`。
差异只在响应怎么收：

```java
call:   .call().chatResponse()                                   // 阻塞等完整响应
stream: .stream().chatResponse()
            .doOnNext(r -> { 拼全文 + sink.emit(token事件) })      // 每个 token 到达就推给前端
            .blockLast();                                        // 最后阻塞等流结束
```

**"响应式数据源 + 阻塞消费"的混合用法**（类注释 32-38 行）：本服务是 MVC（Servlet 线程模型），
`blockLast()` 跑在**自建线程池**里阻塞是安全的（线程从哪来见类 6 ChatController）。

### 流式里对"思考链"的处理（144-155 行，推理模型适配）

```java
Object reasoning = output.getMetadata().get("reasoningContent");   // 智谱等把思考放这
if (reasoning != null) sink.emit(StreamEvent.reasoning(...));      // 独立 thinking 事件
String token = output.getText();
if (token != null) { content.append(token); sink.emit(token事件); } // 正文才计入答案
```

呼应开发规范"流式响应仅处理正文"：**思考增量走独立事件给前端展示，不混进正文、不进记忆、不计入答案**。

### 失败也要埋点（91-96、170-178 行）

catch 里第一件事是 `aiMetrics.recordChat(scene, false, ...)`——注释原话：
"**失败也要计数与计时，否则成功率与耗时分位数都是失真的**"。
流式失败时 `sink.emit(error事件)` 而非抛给前端（连接已建立、响应头已发，抛异常前端根本收不到）。

### RAG 增强（augment + retrieveWithTimeout，189-261 行，本类最值钱的一段）

```
查应用绑定的 kbIds → 空则返回原始问题（plain）
对每个 kb：retrieveWithTimeout(3秒) 检索
  → 命中片段拼编号上下文：[1] 内容 [2] 内容…
  → 加防幻觉指令："请仅依据下方知识库内容回答；不足则明确说明未检索到，禁止编造；
     回答末尾以 [n] 标注引用来源"
  → 发 rag.sources 事件（前端渲染引用角标）+ 存 MessageReference（历史回看）
```

**retrieveWithTimeout 的诞生原因**（注释标注"本期真实事故"）：
检索前要先调**嵌入模型**把问题向量化；嵌入模型未配置时 Spring AI 默认连 api.openai.com，
网络不通**无限阻塞** → 绑定过知识库的应用所有对话卡在"思考中"。
修法：`CompletableFuture.supplyAsync(...).get(3, SECONDS)`，超时/失败一律返回空列表 → **降级为普通对话**。
铁律：**AI 主链路绝不能被可选能力（RAG）拖死**。

**引用为什么流结束才 save**（163-164 行）：引用要挂到"那条助手消息"上，
而助手消息是流式结束后才由记忆 Advisor 落库的——**顺序依赖**，早一步挂不上。

---

## 类 3｜OpenAiCompatibleChatModelResolver —— 61 行全是坑

**核心洞察**：openai / DeepSeek / 火山方舟 / 通义兼容模式 / 本地 Ollama **都讲 OpenAI 协议，
只是 base_url 与 model 不同** → 一个构建器通吃，无需为每家写适配。

三个防坑，每个对应一类真实故障：

1. **快速失败**（31-34 行）：`endpoint.openAiCompatible()` 为假就抛
   "供应商 X 暂不支持（当前仅支持 OpenAI 兼容协议族）"——而不是带着错误 baseUrl 发请求，
   得到难以定位的连接/404 错误；
2. **空 Key 不解密**（35-38 行）：Ollama 免鉴权 api_key 为空。若硬要 `decrypt(null)` 会抛异常，
   被上层 catch 成"**构建失败→静默降级回默认 OpenAI**"的隐蔽故障（明明配了 Ollama 却在偷偷连 OpenAI）。
   判空直接用 `""` 占位绕开；
3. **baseUrl 版本号处理**（42-50 行，最隐蔽）：智谱 `/api/paas/v4`、通义 `/compatible-mode/v1`
   把版本号写进了 baseUrl，而 Spring AI 默认补全 `/v1/chat/completions` →
   拼出 `…/v4/v1/chat/completions` → **404**。
   解法：`baseUrl.matches(".*/v\\d+/?$")` 检测到结尾带版本号，就改用**不带版本前缀**的
   `completionsPath("/chat/completions")`；否则（`https://api.openai.com`、`localhost:11434`）沿用默认。
   ——这正是踩坑记录"Spring AI OpenAI 兼容供应商 base_url 含版本号导致 404"的**源码级修法**。

解密后的密钥**只存在于本次构建过程，绝不落日志**（类注释）。

---

## 类 4｜KnowledgeAppService —— RAG 入库编排（345 行，异步工程范本）

### 入库为什么必须异步（submitIngest，137-156 行）

原实现同步：大文件解析 + 向量化会**占满 Tomcat 请求线程**。
现改为"提交即返回 taskId"：落文档记录 + 建任务记录 + 文件落盘，
真活丢进自建 4 线程 daemon 池（`aihub-ingest-*`，注释：**并发不宜过高以免压垮向量库**），前端凭 taskId 轮询。

### 三个"防丢数据"设计

1. **原始文件落盘暂存**（149-150 行）：注释点名旧实现"进程内 Map，**重启即丢**"→
   现在存 IngestFileStore（本地磁盘/Minio 可切），重启后仍能重试；文件名也从文档记录取（持久化）；
2. **TraceContext.wrap**（152 行）：把提交请求的链路 ID 带到入库工作线程——
   ThreadLocal 不跨线程，不 wrap 入库日志就是"孤儿"；
3. **成功后才删暂存文件**（228-229 行）：`ingest()` 里 `markDone` 之后才 `delete`；
   **失败态必须保留文件以便重试**（retryIngest 从 store 重读，文件没了才标"原始文件已丢失"）。

### 带指数退避的自动重试（runWithRetry + backoff，290-316 行）

```java
for (attempt = 0; attempt <= ingestMaxRetry; attempt++) {
    if (attempt > 0) { prepareRetry; backoff(attempt); }   // 200ms * 2^(n-1)，封顶 2000ms
    try { ingest(...); return; } catch { 记 WARN，下一轮 }
}
```

- **指数退避 + 封顶**：避免重试风暴压垮刚出故障的向量库；
- `backoff` 被中断时 `Thread.currentThread().interrupt()` **恢复中断位**——吞中断信号是并发大忌；
- 重试耗尽**不再抛异常**（后台任务无人可抛），失败状态已由 `ingest()` 的 catch 落库。

### 分阶段计时（execute，256-287 行）

解析(10%) → 分片(30%) → 落库(50%) → 向量化(70%)，每阶段 `updateProgress` + `timed(stage, ...)`——
**能直接看出瓶颈在解析还是向量化**，前端进度条也有真实百分比。

### 又一个"架构约束倒逼取舍"（63-65 行注释）

chunk-size / chunk-overlap / ingest-max-retry 用 `@Value` 而非 infra 侧 RagProperties——
因为 **application 层禁止 import infra**（ArchUnit），代价是这几个键改了要重启。
热更新名单（RagProperties 的 top-k/阈值、AgentProperties 三重预算、ChatClient TTL）与
冷更新名单（入库参数、data-key）的分界，本身就是这个服务的一张"配置治理表"。

### MetricsRecorder.NOOP 的小心思（57-61、244-248 行）

指标端口默认 NOOP，`@Autowired(required=false)` setter 注入——
**未接指标时业务照常跑，指标缺失永不影响功能**；单测也不用为埋点构造依赖。

---

## 类 5｜Redis 索引/检索对 —— 隔离的"数据源头"与"出口把关"

### 索引侧（RedisKnowledgeIndexer，28-43 行）

```java
new Document(chunk.content(), Map.of(
    "tenant_id", String.valueOf(tenantId),    // ★ 强制写租户元数据
    "kb_id", ..., "doc_id", ...));
vectorStore.add(documents);                    // Spring AI VectorStore：embedding 在这一步自动发生
```

入库时**强制打租户标记**——租户隔离的数据源头。注释：缺此元数据的向量，检索侧会被 TenantGuard 丢弃。

### 检索侧两道防线（RedisKnowledgeRetriever，40-64 行）

```java
// 防线①：过滤器强制注入 tenant_id（调用方无法绕过）
filterExpression("tenant_id == '1001'" + (kbId != null ? " && kb_id == '...'" : ""))
// 防线②：TenantGuard 出口逐条再校验
if (!String.valueOf(tenantId).equals(String.valueOf(meta.get("tenant_id")))) {
    log.warn("TenantGuard 拦截跨租户向量! expect={} actual={} docId={}");  continue;  // 丢弃+告警
}
```

**过滤器已经带了 tenant_id，为什么出口还要再查一遍？** 纵深防御——万一过滤器表达式被绕过、
或历史脏数据元数据异常，出口这道人肉校验能兜住并**告警**（把问题暴露出来，而不是静默泄露）。
命中的分片再回查 `docName`（文档仓储），前端引用卡片才有"来源文档名"。

---

## 类 6｜ChatController —— 一套编排三种通道 + 线程隔离

### 三个端点，一个编排（89-148 行）

```
POST /api/ai/chat         同步（调试/集成）
POST /api/ai/chat/sse     H5：返回 SseEmitter（text/event-stream）
POST /api/ai/chat/ndjson  微信小程序：返回 StreamingResponseBody（application/x-ndjson）
```

三者都调 `chatAppService.chatStream(turn, sink)`，**差异只在 sink 实现**
（SseStreamSink / NdjsonStreamSink）——依赖倒置：编排层完全不知道自己在跟谁说话。

### 最值得学的：流式任务与容器线程隔离（58-61、81-85、103-118 行）

```java
private final ExecutorService streamExecutor = Executors.newFixedThreadPool(32, ...daemon);

public SseEmitter chatSse(...) {
    SseEmitter emitter = new SseEmitter(120_000L);
    streamExecutor.submit(TraceContext.wrap(() -> {          // ★ 丢进自建线程池
        chatAppService.chatStream(turn, new SseStreamSink(emitter));
    }));
    return emitter;                                          // 容器线程立刻释放
}
```

- **为什么换线程**：模型生成一句话要几秒到几十秒，不能让 Tomcat 请求线程干等；
- **跨线程必带 `TraceContext.wrap`**：ThreadLocal 不跨线程，不 wrap 流式日志断链
  （ndjson 分支同理：先 `ensure()` 拿 traceId，lambda 里手动 set/clear）；
- 这也解释了类 2 的 `blockLast()` 为什么安全——**它跑在这 32 个自建线程上，不是容器线程**。

### NDJSON 的三个防缓冲头（141-146 行）

```java
headers.set(CONTENT_ENCODING, "identity");              // 禁 gzip
headers.set("X-Accel-Buffering", "no");                 // 禁 nginx 攒包
headers.set(CACHE_CONTROL, "no-cache, no-transform");   // 禁中间层转换
```

少任何一个，逐 token 的流就被缓冲成"憋一大坨"（用户看到：等半天，一次性全出来）。

### 会话 ID 从哪来（turn 方法，180-182 行）

前端没传 conversationId → 服务端生成 16 位短码（UUID 去横杠截断）。
**会话 ID 是字符串**，配合 DbChatMemory 的键 `t{tenantId}:{conversationId}`——租户隔离直接编进键里。

---

## 附｜DbChatMemory —— 一个"会话混写"历史 bug 的修复样本（105 行）

### 键设计：隔离由键本身保证

```java
public static String key(Long tenantId, String conversationId) {
    return "t" + tenantId + ":" + conversationId;      // t1001:a3f2c1e09b8d4a7f
}
```

- `get()` 取最近 `windowSize`（默认 20）条——**滑动窗口记忆**；
- 系统提示词（roleOf 返回 null）**不作为记忆持久化**（48-49 行）——否则每轮都把长提示词重复存进历史；
- 存储走 domain SPI（ConversationMemoryStore），本类只是 Spring AI `ChatMemory` 接口的适配器。

### 类注释里的历史 bug（22-25 行，值得警惕）

> 原实现把非数字 ID 一律退化成 `0L` 写入 BIGINT 列，
> 导致同一租户下**所有会话的历史被混写在同一份记忆里**。

会话 ID 是前端生成的字符串（`a3f2c1e09b8d4a7f`），早期当数字处理 → 解析失败退化成 0 →
全部会话挤进 `t1001:0` 一份记忆。修法：原样透传字符串，由 `conv_key` 列承载（V9 迁移专修此坑）。

**教训**：ID 类型（数字 vs 字符串）是接口契约的一部分，想当然 `Long.parseLong` 会制造
"记忆串台"这种诡异 bug——和"雪花 ID 前端精度丢失"同属**ID 处理要格外小心**这一大类。

---

## 本篇收束（一张表）

| 类 | 解决的难题 | 一句话方案 |
|---|---|---|
| ChatClientFactory | 多租户多模型装配贵 | DCL 缓存 + TTL 惰性热更 + 主备降级 |
| SpringAiChatExecutor | Spring AI 污染业务 / RAG 拖死主链路 | 防腐层 + 3 秒超时必降级 |
| ChatModelResolver | 供应商协议五花八门 | OpenAI 兼容一把梭 + 每个已知差异特判兜底 |
| KnowledgeAppService | 入库慢、易丢、会失败 | 异步线程池 + 落盘暂存 + 指数退避重试 |
| Redis Indexer/Retriever | 向量库租户隔离 | 入库强制打标记（源头）+ TenantGuard（出口）|
| ChatController + DbChatMemory | 流式占线程 / 记忆隔离 | 自建线程池 + wrap 链路 + 租户编进键 |
