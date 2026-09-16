# 09 · 项目难点与 STAR 故事库

> **坑比功能更能证明这是你写的。** 面试官区分"做过"和"看过"的方式就是追细节——
> 编的故事第三问就崩，真踩过的坑能一直讲到修完之后的验证动作。
>
> 每个故事都按 **S（情境）T（任务）A（行动）R（结果）+ 追问** 组织，并标注**适配岗位**。

---

## 故事 1 · MCP stdio 通道被 banner 污染（★ 王牌故事，Agent 岗必讲）

**S**：把 AIHub 作为 MCP Server 开放给 Claude Desktop，选了 stdio 传输（本地进程方式，最易接入）。
**T**：客户端一连上就崩，报错毫无指向性（JSON 解析失败）。
**A**：
1. 先怀疑是协议版本/初始化时序问题，反复对照规范无果；
2. 换个思路——**手工喂 JSON-RPC**：用 shell 管道直接给进程灌 `initialize` → `notifications/initialized` → `tools/list`，
   拿到 stdout 逐行看（关键点：**必须保持 stdin 打开并留间隔**，一次性灌完立刻 EOF，进程会在处理第二个请求前退出，看起来像"tools/list 无响应"）；
3. 发现 stdout 里混进了 **Spring Boot 的 banner 和控制台日志**——
   **stdio 模式下 stdout 就是 JSON-RPC 通道**，任何非 JSON 输出都会让客户端解析失败；
4. 修复：关 banner（`spring.main.banner-mode: off`）+ 专用 `logback-spring.xml`
   把控制台输出改到 **System.err** 并同时落文件（`%TEMP%/aihub-mcp-stdio/`）。
**R**：客户端稳定连接，四个工具全部可用；排查手法沉淀成 README 里的"排查手法"小节。

**追问应对**：
- *"怎么想到是 stdout 的？"* → 直接用管道手工喂协议，绕开客户端，看原始输出。
- *"还有别的 stdio 坑吗？"* → 有：`spring.ai.mcp.server.stdio: true` 必须显式开，
  否则框架走 HTTP 装配，进程会**安静地起一个 Tomcat 等 HTTP 请求**，stdin 永远没响应（更难查）。
- **适配**：Agent 岗 ★★★、Java 岗 ★★、全栈岗 ★★

---

## 故事 2 · MinIO 集成卡了三轮，最后发现是包名写错

**S**：多实例部署时入库文件不能存本地盘，要做 MinIO 实现。
**T**：编译一直报"程序包 com.minio 不存在"。
**A**：
1. 第一轮怀疑**依赖没下载**→ 校验 jar 完整性（`unzip -t`）、比对两个仓库的 sha1，**jar 是完好的**；
2. 第二轮怀疑**本地仓库不对**（发现 Maven 用的是 mvnd 自带的仓库，不是 `~/.m2`）→ 确认 jar 确实在；
3. 第三轮 **`grep -c "com/minio"` 返回 0，而 `io/minio/MinioClient.class` 存在**——
   **包名压根不是 `com.minio`，是 `io.minio`**，我 import 写错了；
4. 改 14 处 import 后一次编译通过。
**R**：MinIO 实现完成，业务代码零改动（靠 `IngestFileStore` SPI + `aihub.storage.type` 切换），
未知 type 值 fail-fast 拒绝启动（**绝不静默回落本地盘**——那会让多实例悄悄退化成各存各的）。

**这个故事的价值**：它演示了**排查路径**。
面试官想听的不是"我改了个 import"，而是"**我怎么在一堆假象里排除出真因**"。

**追问应对**：*"教训是什么？"* → 编译报"包不存在"时，
**先 grep jar 内部路径和 import 包名**，再怀疑环境。我为了这个坑白排查了五轮依赖仓库。
- **适配**：Java 岗 ★★★

---

## 故事 3 · 分层超时：MCP 层 70s 必须大于 HTTP 层 60s

**S**：MCP Server 调上游网关，偶发"MCP 请求超时"。
**T**：日志只显示 MCP 超时，完全指不到真正慢的地方。
**A**：排查发现 `spring.ai.mcp.server.request-timeout` 默认 **20s**，
而调上游 HTTP 的超时是 **60s**——**外层超时比内层短**，
上游慢查询会先被 MCP 层掐断，导致日志里只能看到外层超时，内层真实耗时被吞掉。
**R**：两处统一调整为 **70s > 60s**（外层必须大于内层）。

**提炼成方法论（讲这个）**：
> **多层调用时，超时必须逐层收紧**（外层 > 内层），
> 否则外层先超时会**掩盖**内层的真实问题，让排查失去线索。
> 同理，重试也要小心：**不要在多层同时重试**（会放大流量，形成重试风暴）。

- **适配**：Java 岗 ★★★、Agent 岗 ★★

---

## 故事 4 · 浏览器 Agent 点了个"幽灵元素"

**S**：浏览器 Agent 用页面标注方案（`e1..eN` 写回 `data-testid`）驱动模型点页面。
**T**：模型说"点了 e3"，但页面毫无变化，且**不报错**（静默失败）。
**A**：排查发现是**上一轮的 `data-testid` 没清理**——
已隐藏/已删除的元素还带着旧 ref，`querySelector('[data-testid=e3]')` 命中了残余节点，
点了个看不见的东西。另外遮挡校验里用 `t.contains(w)` 判断祖先关系时，误判了已删除元素。
**R**：标注脚本**先清上一轮的 `data-testid`** 再分配新 ref，问题解决。

**同类细节（可以一起讲）**：
- 点击必须用 `Input.dispatchMouseEvent` 派发**真实鼠标事件**（`el.click()` 不触发 pointer 事件，React 监听不到）；
- 输入必须用 `Input.insertText`（**模拟输入法**，才会触发 input/change，
  直接 `el.value=xxx` 对 Vue/React 受控组件**无效**）。

- **适配**：Agent 岗 ★★★

---

## 故事 5 · 一个会让 TimSort 崩的比较器

**S**：配额策略排序，需要"day 周期优先"。
**T**：元素 ≥3 时偶发 `Comparison method violates its general contract`。
**A**：原因是我（和很多人一样）用三元式冒充比较器：
`(a, b) -> a.isDay() ? -1 : 1` —— **它不满足传递性**
（`compare(a, a)` 返回 -1，且 a<b、b<c 推不出 a<c），
JDK 7+ 的 TimSort 会检测到并直接抛异常。
同时还有一个坑：传入的 list 可能是 `List.of(...)` **不可变列表**，原地 `sort` 会抛
`UnsupportedOperationException`。
**R**：改为 `new ArrayList<>(原list)` 拷贝 + `Comparator.comparing(p -> !"day".equalsIgnoreCase(p.getPeriod()))`，
并沉淀为团队约定：**禁止手写返回 -1/1 的三元式比较器**。

- **适配**：Java 岗 ★★★（八股落到实战，很讨喜）

---

## 故事 6 · 配置热更新静默失效（record 的坑）

**S**：把 Agent 预算、RAG 参数搬到 Nacos 做热更新。
**T**：改了配置，线上不生效，且**没有任何报错**。
**A**：定位到两个原因：
1. `@ConfigurationProperties` 类如果写成 **`record`** 或构造器绑定，
   **无法重新绑定**——刷新事件发生时属性不会更新（静默失效）；
2. 使用方在**启动时把属性值拷贝到了本地字段**，之后一直读旧值。
**R**：属性类改为**可变 JavaBean**（setter + 默认构造器），
使用方改为**调用时读值**；Nacos 模板里给每个配置键标注 🔥（热更）/ ❄️（需重启）。

**提炼**：框架能力的**静默失效**比报错可怕得多——
所以凡是"改配置类"的改动，我都会**手动改一次 Nacos 验证生效**，而不是相信它应该能用。

- **适配**：Java 岗 ★★★

---

## 故事 7 · ArchUnit 漏检：静态常量被编译期内联

**S**：用 ArchUnit 把分层写成构建期卡口，规则之一"application 不得依赖 infra"。
**T**：某次违规**没被拦住**。
**A**：`static final String` 常量会被**编译期内联**——
字节码里根本没有对 `PlanningAgent` 类的符号引用，静态分析自然检测不到。
**R**：在 domain 层建 `AgentNames` 常量类统一定义。

**这个故事说明什么（要讲出来）**：
> 任何架构卡口都有盲区。**不能因为"加了 ArchUnit"就觉得架构不会腐化**，
> 要理解它的检测原理（字节码静态分析）和边界。

- **适配**：Java 岗 ★★★

---

## 故事 8 · 流式渲染的半包与小程序 UTF-8（全栈岗王牌）

**S**：小程序端做对话流式渲染。
**T**：文本偶发解析失败，偶尔出现乱码（尤其 emoji）。
**A**：两个独立问题：
1. **半包**：网络分块不保证按行边界切分，
   直接 `JSON.parse(块)` 会失败 → 引入累积 buffer，
   `buffer.split('\n')` 后 **`buffer = lines.pop()` 把最后一段留到下一块**；
2. **乱码**：小程序 `uni.request` 的 `onChunkReceived` 返回 `ArrayBuffer`，
   而小程序**没有 `TextDecoder`** → 手写 UTF-8 解码，
   且 4 字节字符（emoji）必须用 `String.fromCodePoint`（`fromCharCode` 只处理 UTF-16 码元）。
**R**：两端都稳定。

**顺带讲选型**：H5 用 `fetch` + `ReadableStream` 而**不用 EventSource**，
因为 **EventSource 不能携带自定义 Header**（带不了 `Authorization`）。
后端配合设置 `Content-Encoding: identity` + `X-Accel-Buffering: no`，防止被压缩/缓冲后收不到增量。

- **适配**：全栈岗 ★★★

---

## 故事 9 · 入库文件从内存搬到持久化存储

**S**：文档入库是异步任务，需要支持失败重试。
**T**：早期把原始文件暂存在内存，**服务重启后文件丢失，重试必然失败**，
只能提示用户重新上传。
**A**：改为落盘（本地磁盘 `{tenantId}/{docId}.bin`），任务成功后才删除；
多实例场景又抽象出 domain SPI 接口 `IngestFileStore`，
提供 `LocalIngestFileStore` 与 `MinioIngestFileStore` 两套实现。
**R**：重启后可重试；多实例切换存储只需改一个配置，**业务代码零改动**。

**设计原则（讲出来）**：
> 异步任务要能重试，**中间产物必须持久化**，不能依赖进程内存。
> 同样的思路适用于：任务状态、进度、重试次数都要落库。

- **适配**：Java 岗 ★★★、Agent 岗 ★★

---

## 故事 10 · 为什么同时存在三种流式通道（设计决策类故事）

**S**：要给 H5、小程序、Agent 长任务都做流式。
**T**：一开始想统一用 SSE。
**A**：发现两个硬约束：
1. **微信小程序不支持 SSE**，只能 `uni.request({enableChunked:true})` 分块传输；
2. **Agent 长任务需要双向**（客户端要能发取消），SSE 是单向的。
**R**：三种通道并存——H5 用 SSE、小程序用 NDJSON Chunked、Agent 用 WebSocket；
但**共用同一套 `StreamEvent` 信封和 `StreamSink` 抽象**（domain 层只有 `emit/close` 两个方法），
业务层无需区分通道。

**这是"抽象设计"类故事**：证明你能**用抽象隔离平台差异**。

- **适配**：全栈岗 ★★★、Java 岗 ★★

---

## 故事 11 · 单元测试抓到真 bug（体现测试观）

**S**：给 MinIO 实现写单测。
**T**：`loadReturnsBytes` 断言失败（期望有值，实际 `Optional.empty`）。
**A**：日志里一条 warn 暴露了真相——
`ByteArrayInputStream cannot be cast to io.minio.GetObjectResponse`。
原因是 **Mockito 的 inline mock maker 会把 stub 的 Answer 返回值按方法声明的返回类型强转**，
我返回了裸 `InputStream` 导致 ClassCastException，被 catch 吞掉后表现为"空"。
用 `javap` 确认构造签名后改用真实的 `GetObjectResponse` 包装。
**R**：测试通过。

**测试观（可以讲）**：
> 测试失败时**先怀疑实现，不要先改断言**。
> 我这个项目里测试抓到过好几个真实 bug（convOf 边界、retry 绕过终态写入）。

- **适配**：Java 岗 ★★★

---

## 故事 12 · 接口文档里"消失的参数"

**S**：接入 springdoc-openapi 生成 Swagger 文档。
**T**：`@RequestParam` 参数在文档里不显示。
**A**：查官方文档 FAQ——**Boot 3.2+ 需要在编译器开 `-parameters`**，
否则字节码里没有参数名，springdoc 拿不到。父 pom 的 `maven-compiler-plugin` 加上
`<parameters>true</parameters>` 后正常。
另外租户拦截器会要求 `X-Tenant-Id` 头，导致打开 swagger 就被拒绝，
需要把 `/v3/api-docs/**`、`/swagger-ui/**` 加入排除名单。

- **适配**：Java 岗 ★★、全栈岗 ★★

---

## ★ 别吹过头清单（照妖镜）

**分为两部分：**
- **A. 已经修掉的**（2026-09 收尾轮）——现在可以当成果讲，但要能说清"修的时候验证到哪一步"；
- **B. 仍未做的**——面试时不要当成成果讲，被追问时**主动承认 + 说明改进方案**，反而加分。

### A. 已修复（可以讲）

| 项 | 修复内容 | 讲话要点（含验证边界） |
| --- | --- | --- |
| `ai_event_outbox` | 从"零实现"补齐为完整 Outbox：`domain/spi/EventOutbox` 端口 + `DbEventOutbox` 落库 + `EventOutboxDispatcher` 守护线程重投（30s/批 50/重试 3 次后放弃），并在 `reportUsage` 失败时转交 | "我把上报从'尽力而为'升级为'至少一次'。**关键细节是重投前必须补齐租户上下文**——后台线程没有上下文，而 Feign 只在租户非空时写 `X-Tenant-Id`，平台侧又强制要求该头，不补就 100% 失败。"（有 9 个单测钉住） |
| `TenantLineInnerInterceptor` | 已实现（ai-service `TenantSqlGuardConfig` / platform `MybatisPlusConfig`），含白名单与"无租户上下文即跳过"的安全语义 | "**我实现后默认关闭，因为验证不到位**：它改的是每一条 SQL，而单测是 mock 的、覆盖不到真实 SQL 改写。要开就是先起 MySQL 跑通全部数据访问链路再看日志。**我不想拿一个没在真库上跑过的东西当默认行为。**" |
| `task` 维度配额 | 超限由"忽略 allowed"改为与其他维度一致：同步抛 `QUOTA_EXCEEDED`、流式发 error 事件 | "Agent 是高成本调用，配额形同虚设等于没有闸门。同时保留了降级放行语义：平台挂了照常可用，只有平台明确说超限才拦。" |
| `PlanningAgent` finally NPE | 兜底终态，避免 `Error`（非 `Exception`）路径上 NPE 覆盖原始错误 | "catch 只覆盖 Exception，抛 Error 时 finally 里的 `result.status()` 会 NPE，把真正的错误从堆栈里挤掉——排查时极具误导性。" |
| 浏览器 `max-steps` | 步数上限改由 `aihub.agent.browser.max-steps` 决定（不再被规划层共享预算压成 3 步） | "`maxSubTasks` 是'规划层能拆几步'的语义，拿来当 ReAct 循环步数是概念混用。改完加了一条回归用例：共享预算 3 步 + 实际需要 4 步动作，必须能跑完。" |
| `initialize-schema` 无效配置 | 配置项真正生效（不再硬编码 `true`） | "配置项存在却不生效是最阴的一类 bug——改配置没反应，排查时会怀疑一切。" |
| 供应商协议族判定 | `openAiCompatible()` 补上 ollama、消除与 `ChatClientFactory` 的分裂，并在解析器入口做快速失败 | "字典里有 5 个供应商，判定只认 4 个——'能选'和'能用'不一致。"（有 `ModelEndpointTest` 钉住全部 5 个） |
| ArchUnit 覆盖 | 4 条 → 7 条：domain 收紧到禁全部 Spring/Jackson、application 禁 Spring AI/ORM/Redis、web 禁 ORM、新增"底层 infra 不得反向依赖 AI 层"等 | "架构卡口的价值在于**它拦住过东西**——收紧后构建仍全绿，说明规则不是摆设。" |
| 前端 `mock/` 与 fake-server | 移除 `vitePluginFakeServer` 注册（原配置带 `enableProd: true`，会把 mock 打进生产包），并加 `mock/README.md` 说明 | "这三个文件对接的是模板自己的 `/login`，和真实后端对不上，留着只会让后来者误判接口是假数据。" |
| 前端容器化 | 补 `deploy/nginx/default.conf.template` 并在 Dockerfile 里 COPY（envsubst 注入 `BACKEND_URL`） | "旧 Dockerfile 只 COPY 了 dist，容器里 `/api`、`/auth` 没有反向代理——页面能开、接口全 404。" |
| 前端 traceId | axios 响应拦截器读取 `X-Trace-Id` 并拼进错误提示 | "用户报障直接贴 traceId，三份日志一 grep 就能还原链路。" |
| H5 中断 | `abort()` 在 H5 支用 `AbortController` 真正断开（原来该分支是空的），并补 `flush()` 处理"最后一帧无换行" | "客户端断开只停止接收，服务端还在跑——所以还要配合 `/api/ai/agent/cancel` 在步骤边界终止。" |

### B. 仍未做（别当成果讲）

| 项 | 真实情况 | 该怎么说 |
| --- | --- | --- |
| ECharts | 装了但 `main.ts` 里注册被**注释掉**，没用上 | 用量看板用的是 `el-table` + `el-progress` |
| Pinia store | 6 个模块**全是模板原版**，业务状态基本没放 store | 直接说"是模板残留" |
| 前端单测 | **没有** | 改进方向（vitest + 组件测试） |
| 系统化的 AI 评测集 | **没有** | 改进方向（RAGAS / 任务成功率 / 人工反馈闭环） |
| 管理端业务接口未下沉 `api/` 层 | 在 `.vue` 里裸调 `http.request` | "是我的简化，规范做法是按领域拆 `api/usage.ts`、`api/model.ts` 并补 TS 类型" |
| 多租户 SQL 兜底未在真库验证 | 代码就绪、**默认关闭** | 见上表 A 的讲话要点（这条要主动说，不能装作已生效） |
| 分库分表 | **没做**（数据量未到瓶颈） | 能讲方案即可 |
| SkyWalking / 完整链路追踪 | **没上**，用自研 TraceId | 权衡：够用且零运维 |
| 跨租户端到端回归测试 | **没有**（各 Repository 单测只覆盖"租户条件进没进查询"） | "设计文档要求过，需要真实库集成测试才能做，是明确的待补项" |

---

## 讲故事的四个技巧

1. **先说结果再说过程**："这个问题最后发现是 XXX，过程是这样的……"
   面试官注意力只有 30 秒。
2. **一定要有"我怎么定位的"**：工具（javap / 手工喂协议 / grep jar / 管道）、
   思路（二分、绕开上层直接看原始输出）。**定位方法比问题本身值钱。**
3. **要有可量化的结果**：从"必崩"到"稳定"、"白排查五轮"到"改 14 行一次通过"。
4. **每个故事结尾加一句提炼**：把个例上升为方法论（分层超时、中间产物持久化、
   静默失效比报错可怕……）。**没有提炼的故事只是流水账。**
