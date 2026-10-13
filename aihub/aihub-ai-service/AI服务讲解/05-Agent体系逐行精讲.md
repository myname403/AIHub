# 05｜Agent 体系逐行精讲（源码级）

> 覆盖：BaseAgent / PlanningAgent / GenerationAgent / AgentRegistryImpl / AgentBudget / 取消机制。
> 主线问题：**让 LLM 自主干活，怎么保证它不失控？**（预算、取消、降级、留痕四道缰绳）

---

## 0. 全景：这套 Agent 是干嘛的

用户在对话里说"帮我做一份 XX 的销售数据表格页面"——这不是模型回一段文字就完事的，
需要：**拆解目标 → 分派给专职 Agent → 生成 HTML 产物 → 落盘 → 把预览链接推回前端**。
本项目对齐课程 MyManus，用 7 个类搭起这套体系：

```
Agent(domain/spi 接口) ── name() + description() + execute(task, sink)
 ├─ BaseAgent(infra 抽象类)          模板方法：llm()/checkBudget()/step()/fallback()
 │   ├─ PlanningAgent                入口：拆解 + 派发（ReAct 风格）
 │   ├─ GenerationAgent(抽象)        生成类公共流程：LLM出HTML→存产物→广播→返回摘要
 │   │   ├─ TableAgent               ┐
 │   │   ├─ ChartAgent               ├ 各 20 行：只填 systemPrompt() 和 artifactName()
 │   │   └─ HtmlDocAgent             ┘（默认执行 Agent）
 │   └─ BrowserAgent                 CDP 驱动真 Chrome（独立于生成体系）
 └─ AgentRegistryImpl                名字→实例 的注册中心（按名派发）
```

---

## 1. BaseAgent —— 模板方法模式的教科书（98 行）

类注释直说了设计模式：**abstract 类定义骨架，子类只填"具体怎么干"**。
新增一种 Agent = 一个几十行的子类。

### llm()：所有 Agent 调模型的唯一出口（48-67 行）

```java
protected String llm(AgentTask task, String systemPrompt, String userPrompt) {
    checkBudget(task);                                  // ① 调用前查预算
    ChatClient client = chatClientFactory.create(task.tenantId(), 0L, "agent-plan");
    ChatResponse response = client.prompt().system(systemPrompt).user(userPrompt)
                                   .call().chatResponse();
    if (task.budget() != null && ...usage != null) {
        task.budget().addTokens(promptTokens, completionTokens);   // ② 记账！
    }
    checkBudget(task);                                  // ③ 调用后再查一次
    ...
}
```

三个讲究：
- **走场景 `agent-plan` 选模**：Agent 的内部 LLM 调用也过模型网关（享受主备降级），
  而不是自己 new 一个客户端——所有模型流量收口在 ChatClientFactory 一处；
- **调用前后各查一次预算**：超时/Token 耗尽抛 `AgentBudgetExceededException`——
  前查防"进来时已经超了还打"，后查防"这一发就把额度打爆"；
- **token 记账进 budget**：三重预算里的 token 维度靠每一笔 `addTokens` 累加，
  少记一笔就等于没管住钱。

`fallback()`（92-96 行）：统一失败兜底——**返回 FAILED 结果的 AgentResult，而不是让异常穿透**。
预算异常（`AgentBudgetExceededException` 是 RuntimeException）**故意不在这里 catch**，
留给 PlanningAgent 顶层转成 BUDGET_EXCEEDED——"预算耗尽"和"执行失败"是两种终态，不该混。

---

## 2. AgentBudget —— 三重预算（70 行，domain 层，无框架）

```java
private final int maxSubTasks;      // 最大子任务数：限制拆解规模
private final long maxTokens;       // 最大 Token：限制总成本
private final long timeoutMs;       // 最大耗时：deadlineAt = now + timeoutMs
private final AtomicLong usedTokens = new AtomicLong();   // ★
```

四个必讲点：

1. **<=0 即"不限制"**（31-34 行）：构造器把 0/负数翻译成 `Integer.MAX_VALUE`/`Long.MAX_VALUE`——
   判断逻辑里就不用写一堆 null/负数分支（`timeout()` 就是一句 `now > deadlineAt`，干净）；
2. **AtomicLong 为什么必须**（类注释原话）：多个子 Agent 可能**不同线程**同时加 token，
   普通 `usedTokens += delta` 是"读-改-写"三步，会互相覆盖丢账；AtomicLong 用 CPU 级 CAS，**原子且无锁**；
3. **实例在整条子任务链上共享**（注释 ★）：`AgentTask.subTask()` 传的是**同一个 budget 引用**——
   子 Agent 花的每一笔都计入总预算。**防的就是"把大任务拆成小任务绕过预算"**；
4. **deadlineAt 在构造时定死**：超时是"墙钟截止时刻"，不是"累计执行时间"——
   中途排队、等待都不许续命。

---

## 3. AgentRegistryImpl —— 按名派发的注册中心（40 行）

```java
public AgentRegistryImpl(@Lazy List<Agent> allAgents) {     // ★ 构造器注入所有 Agent 实现
    for (Agent agent : allAgents) {
        agents.put(agent.name(), agent);                     // 名字 → 实例
    }
}
public Optional<Agent> lookup(String name) { return Optional.ofNullable(agents.get(name)); }
```

- **Spring 集合注入**：容器里所有 `Agent` 类型的 Bean 自动汇成 List 注进来——
  新增一个 Agent 只需 `@Component`，注册中心零改动（开闭原则）；
- **@Lazy 是解环的关键**：`PlanningAgent` 自己也是 Agent（会被注进 List），
  而它构造器又需要 `AgentRegistry`——不加 @Lazy 就是
  `Registry → PlanningAgent → Registry` 的**构造器循环依赖**，启动直接失败；
- `lookup` 返回 `Optional`：派发到不存在的 Agent 名（LLM 幻觉出来的名字）时，
  调用方 `orElse(null)` + 广播"未找到执行 Agent，跳过"——**幻觉不致命**。

---

## 4. PlanningAgent —— 入口智能体（234 行，本体系最重的类）

### 4.1 execute()：一次任务的完整生命周期（83-116 行）

```java
AgentBudget budget = task.budget() == null ? defaultBudget() : task.budget();   // 入口填预算
Long taskId = taskRepository.create(root, NAME);                                 // 任务落库（审计/回放）
try {
    result = run(root, sink, index, artifacts, taskId);
} catch (AgentBudgetExceededException e) {
    result = new AgentResult(BUDGET_EXCEEDED, e.getMessage(), artifacts);        // 预算≠失败，单独终态
} catch (Exception e) {
    result = fallback(root, e);                                                  // 其他异常 → FAILED
} finally {
    taskRepository.finish(taskId, finished.status(), ..., budget.usedTokens());  // ① 终态必落库
    cancelRegistry.clear(root.conversationId());                                 // ② 取消位必清理
    aiMetrics.recordAgentTask(finished.status());                                // ③ 指标必上报
}
```

**finally 里的防御注释（101-107 行）是全文最精彩的细节**：
catch 只接 Exception——若 run() 抛出 **Error**（OutOfMemoryError 等），result 仍是 null；
此时直接 `result.status()` 会 **NPE，把真正的原始错误从堆栈里挤掉**（排查时极具误导性）。
所以 finally 先兜一个 `FAILED("任务执行异常终止（非 Exception 错误）")` 再落库——
**保证 ai_agent_task 永远有终态，永远不留"僵尸任务"**。

`cancelRegistry.clear(conversationId)` 放 finally 同理：取消标志位是共享内存里的状态，
不清理=下次同会话任务一进来就被"上次的取消信号"误杀——**ThreadLocal 要 clear，会话级标志位也要 clear**。

### 4.2 run()：拆解 → 循环派发，每步四道检查（118-167 行）

```java
if (cancelRegistry.isCanceled(convId)) → CANCELED        // 派发前查取消
subTasks = decompose(root);                               // LLM 拆解
for (SubTask sub : subTasks) {
    if (isCanceled) → CANCELED                            // ★ 每个子任务前再查（停止的粒度）
    if (budget.timeout() || tokenExceeded()) throw 预算耗尽
    if (done >= maxSubTasks) → BUDGET_EXCEEDED
    Agent agent = registry.lookup(sub.agent());           // 按名派发
    if (agent == null) { 广播"跳过"; continue; }           // 幻觉名字不致命
    AgentResult result = agent.execute(root.subTask(sub.task()), sink);   // 递归入口（budget 同引用）
    taskRepository.appendStep(...);                       // 每步落库
    artifacts.addAll(result.artifacts());                 // 子产物上收
    answer.append("【" + agent.name() + "】" + result.answer());
}
```

**emit() 的小设计**（170-174 行）：广播 `agent.step` 事件的**同时落库**——
注释点破双通道用途："事件给前端时间线，落库给审计与回放"。前端断了、事后排查，账都还在。

### 4.3 decompose()：和 LLM 要一份 JSON（182-216 行）

```java
String user = """
    可用执行 Agent：
    - table：把结构化数据整理成 HTML 表格页面
    - chart：把数据生成图表（ECharts）页面
    - html：生成网页/文档/总结类内容（默认）
    用户目标：%s
    请拆解为不超过 %d 个子任务，只返回 JSON 数组，格式：
    [{"agent":"html","task":"子任务描述"}]
    不要输出任何其他内容。若目标无需拆解，返回单个 html 子任务。
    """;
```

和 LLM 做结构化集成的**三件套防御**（少了任何一件，线上都会时不时炸给你看）：
1. **提示词里给足约束**：可选 Agent 清单 + 数量上限 + 输出格式 + "不要任何其他内容"；
2. **extractJsonArray 容错提取**：`indexOf('[')` 到 `lastIndexOf(']')`——
   LLM 在 JSON 前后加"好的，这是拆解结果：…"这类客套话时照样能抠出来；
3. **解析失败退化**：`catch` 后返回**单个 html 子任务**（213-215 行）——
   拆解失败顶多"不拆了直接干"，**任务本身绝不因此失败**。
   另外 `item.path("agent").asText(AgentNames.HTML)`：字段缺失也有默认值。

这就是"面向不可靠的 LLM 输出编程"的标准姿势，和你之前学的 MCP"错误转人话"是同一哲学：
**永远为模型的不听话准备退路**。

---

## 5. GenerationAgent —— 生成类的公共流水线（81 行）

```java
step("think", "准备生成：" + artifactName(task));
String html = llm(task, systemPrompt(), user);     // 子类只贡献两个抽象：提示词 + 产物名
html = stripCodeFence(html);                        // ① 剥掉 ```html 围栏
if (html.isBlank() || !html.contains("<"))          // ② 合法性粗检
    return FAILED("内容生成失败，请检查模型配置");
ArtifactInfo artifact = artifactStore.save(tenantId, null, name, "text/html", bytes);   // ③ 落盘
sink.emit(StreamEvent.ARTIFACT, {name, url});       // ④ 推产物事件
return done("已生成「%s」，点击查看：%s");            // ⑤ 答案只留摘要+链接
```

三个值得抄进自己项目的细节：
- **① stripCodeFence**：让 LLM"只输出 HTML"，它就真的常包一层 ```` ```html ````——
  与模型集成的脏活要**在代码里预设好**，不是祈祷它听话；
- **② `!html.contains("<")`**：一行粗检就能拦住"模型回了一段道歉文字当网页"的事故；
- **⑤ 大内容走引用不走内联**：完整 HTML 进产物存储，对话流里只有摘要+previewUrl
  （ArtifactController 在线预览）——**省上下文，也让前端能把产物渲染成卡片**。

TableAgent/ChartAgent/HtmlDocAgent 各约 20 行：只填 `systemPrompt()`（决定生成什么形态）
和 `artifactName()`（产物命名）——**模板方法的收益在这里兑现：新增一种生成 Agent 只写 20 行**。

---

## 6. 取消链路：前端"停止"到服务端终止（跨三篇文档的闭环）

```
用户点停止
 ├─ 前端①：stream.js abort()            —— 本地断流，不再渲染（但服务端还在跑！）
 └─ 前端②：POST /api/ai/agent/cancel     —— AgentController → cancelRegistry.markCanceled(convId)
                                              ↓
PlanningAgent.run() 每个子任务边界 isCanceled() → 广播"收到取消信号，终止后续子任务"
                                              → return CANCELED（终态落库、预算/取消位清理）
```

三个要点：
1. **"客户端断开 ≠ 服务端停止"**：不发明第二条信令，停止按钮只是"眼不见为净"，token 照烧；
2. **取消是"边界检查"不是"强制中断"**：正在执行的那个子任务会跑完才停——
   在 LLM 调用中途硬杀线程既不礼貌也不可靠，**步骤粒度终止**是工程上的正确折中；
3. `InMemoryAgentCancelRegistry`（infra/support）：`ConcurrentHashMap<convId, AtomicBoolean>`——
   单机内存实现；多实例部署要换 Redis/广播（已知边界，注释同款诚实）。

---

## 7. 收束：四道缰绳各管什么（背这张表）

| 缰绳 | 机制 | 失控场景 | 代码位置 |
|---|---|---|---|
| 钱（token） | budget.addTokens 每笔记 + tokenExceeded 前后查 | 无限循环调模型烧钱 | BaseAgent.llm / AgentBudget |
| 规模（子任务数） | maxSubTasks + done 计数 | LLM 拆出 50 个子任务 | PlanningAgent.run |
| 时间（墙钟） | deadlineAt 构造时定死 | 任务挂死占资源 | AgentBudget.timeout |
| 意愿（用户） | cancelRegistry 步骤边界查 | 用户反悔了停不掉 | run 循环头 + finally clear |

再加两个"体系级"保险：**LLM 输出必配退化路径**（decompose 失败→单任务；幻觉 Agent 名→跳过）、
**终态必落库**（finally 兜 Error 也要 finish）——这套 Agent 框架的全部工程含金量就是这十六个字：**缰绳分明、终态必达**。
