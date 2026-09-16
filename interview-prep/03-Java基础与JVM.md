# 03 · Java 基础与 JVM

> 这一篇的每一节都挂在项目真实代码上。**"八股 + 我在项目里怎么用的"**是最稳的答法：
> 单纯背八股是应届水平，能落到自己的代码上才是"真会"。

---

## 一、并发（Java 岗必考，且你有很多真材实料）

### 1.1 你项目里哪里用了原子类？为什么不用锁？

**场景**：Agent 的 Token 预算。

`AgentBudget` 里 `usedTokens` 是 `AtomicLong`，累加用 `addAndGet`（CAS 自旋）。
预算三个维度：子任务数、Token 数、超时时间（`deadlineAt` 在构造时算好）。
其中"没有限制"的维度（值 <= 0）统一归一化为 `Long.MAX_VALUE`，避免每次判断。

**为什么用 CAS 不用 `synchronized`**：
- 累加是**极轻量的单一操作**，锁的开销（ monitorenter / 上下文切换 ）远大于一次 CAS；
- 竞争很弱（一个 Agent 任务内部累加），CAS 几乎一次成功；
- 不需要"读-改-写"的复合原子性之外的能力。

**追问**：
- *"AtomicLong 高并发下有什么问题？"* → 大量线程 CAS 失败自旋会打满 CPU，
  此时应该用 `LongAdder`（分段累加，空间换时间）。
  我这个项目用 `AtomicLong` 是因为**竞争强度低**，不是因为它是万能解。
- *"ABA 问题？"* → `AtomicLong` 的 `addAndGet` 场景不存在 ABA（只累加，不改回去）；
  如果要做"Token 上限一次性抢占"这种语义，才需要 `AtomicStampedReference` 或版本号。

### 1.2 `ConcurrentHashMap.newKeySet()` 用在哪？

**场景**：Agent 的**服务端可中断**。

`InMemoryAgentCancelRegistry` 内部是 `ConcurrentHashMap.newKeySet()`（本质是
`ConcurrentHashMap<K, Boolean>` 的包装，值为 `Boolean.TRUE`），存被取消的 `conversationId`。
流程：`AgentController.cancel` 写入 → `PlanningAgent` 在每个子任务循环头和 `run()` 入口
调用 `isCanceled()` 判断 → 命中就返回 `AgentResult.CANCELED`。

**★ 这里必须讲清楚一个本质问题（面试官最爱追问）**：

> **这是"标志位协作"，不是线程中断。**

- 它**打断不了正在飞行的 LLM HTTP 调用**，只在**步骤边界**生效；
- 没用 `Thread.interrupt()` 的原因：中断依赖线程对中断状态的响应，
  而 HTTP 客户端（阻塞 IO）不会因为你 interrupt 就立刻返回，
  且中断状态会污染线程池里的线程（复用时带脏状态）。
- **如果要做到"真中断"，怎么办？** → 两个方向：
  1. 给 HTTP 客户端设置 `readTimeout` + 包装一层可取消的 Future（`Future.cancel(true)`），
     让在飞的调用被超时或取消打断；
  2. 更彻底：把每个子任务跑在独立 `Future` 里，取消时 `cancel(true)` + 关闭底层 Socket。

**这题答到"能用 cancel 会污染线程池"这个层次，基本就是 P6 往上。**

### 1.3 双重检查锁（DCL）——你项目里为什么还在用？

**场景**：`ChatClientFactory` 的 ChatClient 缓存。

```java
// 简化的真实结构
CacheEntry e = cache.get(key);
if (e == null || e.isExpired()) {
    synchronized (cache) {          // 锁的是 cache 对象
        e = cache.get(key);          // 二次检查
        if (e == null || e.isExpired()) {
            e = build(model);        // 构建（可能失败，要降级）
            cache.put(key, e);
        }
    }
}
```

**必须能答出的三个点**：
1. **为什么外层要判空**：避免每次都进 synchronized（性能）。
2. **为什么内层还要判空**：等待锁期间可能已被别的线程构建好。
3. **`volatile` 呢？** —— 这里我用 `ConcurrentHashMap` **代替了 volatile 的作用**：
   CHM 的 `get` 有 happens-before 保证（读能看见完整的构造结果），
   所以不需要给缓存字段加 `volatile`。**答出这一点说明你真的懂 DCL 的原理，而不是背模板。**

**追问**：
- *"TTL 60s 过期怎么处理？"* → `CacheEntry` 里记 `expireAt`，读的时候判断；
  TTL 值来自 `ChatClientProperties.cacheSeconds`，**每次读当前值**（支持 Nacos 热更新），
  不是在构造时拷贝到本地字段。
- *"为什么不用 Caffeine？"* → AI 服务的这个缓存 key 基数小（租户×应用×场景×模型），
  用 CHM + DCL 足够，少一个依赖。**但网关的 API Key 缓存用了 Caffeine `AsyncCache`**
  （`expireAfterWrite(60s)` + `maximumSize(10000)`），因为那里的 key 来自外部请求、基数不可控，
  需要 LRU 淘汰和异步刷新。

### 1.4 线程池：参数是怎么定的？

**这是必问题，必须给出"为什么是这个数"**：

| 线程池 | 配置 | 为什么 |
| --- | --- | --- |
| 入库线程池 | `newFixedThreadPool(4)`，daemon 线程，名 `aihub-ingest-` | CPU 密集（解析/分片）+ IO（调 embedding）。单机给 4 是因为入库是**后台重任务**，不能抢占对话线程；且解析 PDF 吃内存，并发太高会 OOM |
| MVC 异步池 | `ThreadPoolTaskExecutor` core 16 / max 64 / queue 200，名 `aihub-async-` | 承载 SSE/NDJSON 长连接。对话是 **IO 密集**（等模型返回），线程数可以远大于 CPU 核数 |
| 流线程池 | Controller 自建 32 线程 | 与 MVC 池隔离，避免流式任务把普通请求的线程吃光（**线程池隔离**思想，和 sentinel 的线程隔离同理） |
| WebSocket | 16 线程 | Agent 长任务专用 |

**追问**：
- *"为什么不用 `Executors.newFixedThreadPool` 一把梭？"* →
  它内部是 `LinkedBlockingQueue` **无界队列**，任务堆积会 OOM。
  生产应该用有界队列 + 明确的拒绝策略。
  （我可以诚实补一句：入库池用的就是 `newFixedThreadPool`，是无界队列——
  这是**已知风险**，靠"上传接口限流 + 重试上限 2 次"来兜，不是最优解。）
- *"拒绝策略？"* → 默认 `AbortPolicy` 抛异常。
  入库这种场景其实更适合 `CallerRunsPolicy`（让提交方自己干，天然反压），
  但会阻塞请求线程，要权衡。

### 1.5 `CompletableFuture` 用在哪？

**场景**：RAG 检索的超时降级。

```java
CompletableFuture.supplyAsync(() -> retriever.retrieve(req))
                 .get(3, TimeUnit.SECONDS);   // 超时则降级为无 RAG 的普通对话
```

**追问**：
- *"`get` 超时后，那个任务会停吗？"* → **不会！** `get(timeout)` 只是让**等待方**抛
  `TimeoutException`，任务还在线程池里跑。
  这是很多人不知道的点。要真取消必须 `cancel(true)`，且任务内部要响应中断。
- *"为什么是 3 秒？"* → 对话的首 token 时间（TTFT）敏感，检索超过 3 秒用户已经觉得卡了，
  宁可降级给一个不带引用的答案。
- *"`supplyAsync` 默认用哪个线程池？"* → `ForkJoinPool.commonPool`（默认并行度 = CPU 核数 - 1）。
  **这里有坑**：commonPool 是全局共享的，被阻塞型任务占满会影响整个 JVM 的 parallelStream。
  生产应该显式传自己的 `Executor`。

### 1.6 `volatile` 用在哪？

**场景**：三种 `StreamSink`（SSE / NDJSON / WebSocket）都用 `volatile boolean closed`
做关闭的双检（判断 + 置位）。

**为什么必须 volatile**：
- 保证**可见性**：关闭动作可能在另一个线程（超时线程 / 异常线程）发生；
- 这里只需要可见性，不需要原子性（判断-置位之间允许极小概率的重复，
  且 `emit` 本身有 try-catch 保护），所以没用 `AtomicBoolean`。

**追问**：*"volatile 能保证原子性吗？"* → 不能，只对**单次读/写**保证可见性和禁止重排序，
`i++` 这种复合操作不行（要用 CAS 或锁）。它的底层是 **内存屏障 + MESI 缓存一致性协议**，
写操作会触发 store barrier 把工作内存刷回主存，并使其它 CPU 的缓存行失效。

---

## 二、ThreadLocal（项目里是核心机制，必考）

### 2.1 你的租户上下文是怎么存的？

`TenantContext` 里两个 `ThreadLocal<Long>`：TENANT 和 USER。
`TenantResolveInterceptor.preHandle` 从 `X-Tenant-Id` / `X-User-Id` 读，
**缺失直接抛异常**（不做"猜租户"）；`afterCompletion` 里 `clear()`。

### 2.2 追问：为什么一定要 clear？

**Web 容器线程是复用的**（Tomcat 默认 200 线程）。
不 clear 会导致：
1. **数据串号**：下一个请求复用到这个线程，读到了上一个请求的租户——**这是越权事故**；
2. **内存泄漏**：`ThreadLocalMap` 的 key 是**弱引用**、value 是**强引用**，
   key 被 GC 后 value 还在，线程不死（池化）就一直泄漏。

### 2.3 ★ 追问：异步线程里怎么传递？这是你的加分题

**诚实版本（也是真实实现）**：
- 只有 `TraceContext.wrap(Runnable)` 做了"快照 + 还原"的包装（给 TraceId 用）；
- **租户上下文没有 wrap**。异步场景（入库线程池）靠的是**在提交前把 tenantId 取出来，
  作为方法参数显式传递**——`submitIngest(tenantId, docId, ...)`。

**为什么这么设计（这是可以主动讲的观点）**：
> 我认为"显式传参"比"隐式 ThreadLocal 透传"**更安全**：
> ThreadLocal 的隐式传递会让"这个线程属于哪个租户"变得不可见，
> 一旦有人在线程池里 new 了一个新任务忘了传，就会静默出错。
> 显式传参让编译器帮你检查。

**如果面试官追问"那你知道怎么优雅地透传吗"**：
- 阿里开源 **TTL（TransmittableThreadLocal）**：包装线程池的 `Runnable`，
  在提交时抓取上下文、执行时还原、结束后清理；
- 或者用 `InheritableThreadLocal`——**但它在线程池场景下失效**
  （只在线程创建时拷贝一次，池化线程不会被重新拷贝）。
  能说出"TTL 解决的是池化线程复用问题，InheritableThreadLocal 解决不了"，这句很加分。

---

## 三、集合与常见坑（你会讲"我踩过"的版本）

### 3.1 `ArrayDeque` 用在浏览器 Agent 的历史

`BrowserAgent` 用 `ArrayDeque` 保存观察历史，最多 `MAX_HISTORY = 6` 条（超出就 `pollFirst`）。
**为什么用它不用 `LinkedList`**：`ArrayDeque` 是循环数组，缓存局部性好、
无节点对象开销，且明确用作**双端队列**（头出尾进）比 `LinkedList` 更表意。

### 3.2 ★ 不可变集合排序的坑（真实踩过）

`QuotaService` 里对配额策略排序，代码长这样：

```java
List<Policy> policies = new ArrayList<>(policyList);   // ★ 先拷贝一份
policies.sort(Comparator.comparing(p -> !"day".equalsIgnoreCase(p.getPeriod())));
```

**两个坑，都能讲**：
1. **不可变 list 不能原地 sort**：如果传入的是 `List.of(...)`（不可变），
   直接 `.sort()` 会抛 `UnsupportedOperationException`。所以先 `new ArrayList<>` 拷贝。
2. **比较器不能用三元式冒充**：写成 `(a, b) -> a.isDay() ? -1 : 1` 是**错的**——
   它不满足比较器的**传递性**（a<b 且 b<c 推不出 a<c，甚至 a 和自己比返回 -1），
   元素 ≥3 时 JDK 7+ 的 TimSort 会直接抛
   `Comparison method violates its general contract`。
   正确做法是用 `Comparator.comparing(...)` 或显式比较。

> 这个项目里就是因为这个规则踩过坑，后来的约定是：**一律用 `Comparator.comparing` 系列，
> 禁止手写返回 -1/1 的三元式比较器**。讲出这条团队约定，说明你有工程沉淀。

### 3.3 HashMap / ConcurrentHashMap 常考

- JDK 8 后 `HashMap` 链表长度 > 8 且数组 ≥ 64 才转红黑树（< 64 时先扩容）；
- `ConcurrentHashMap` JDK 8 起是 **CAS + synchronized 锁链表头**（不再是分段锁 Segment），
  粒度更细；
- ** key / value 都不能为 null **（`HashMap` 可以）——因为 `get(key)` 返回 null 无法区分
  "key 不存在"和"value 是 null"，在并发下语义有歧义。

---

## 四、JVM

### 4.1 内存结构与 OOM

| 区域 | 存什么 | OOM 场景 |
| --- | --- | --- |
| 堆 | 对象实例 | `Java heap space`：内存泄漏、一次加载超大文件 |
| 元空间（本地内存） | 类元信息 | `Metaspace`：动态生成类过多（CGLIB、反射） |
| 虚拟机栈 | 栈帧 | `StackOverflowError`：递归过深 |
| 直接内存 | NIO Buffer | `Direct buffer memory`：Netty 未释放 |

**项目相关的真实风险**（可以讲）：
- **PDF 解析**：`PDFBox` 的 `Loader.loadPDF` 会把整个文档读进内存，
  大文件（几百 MB）会直接打满堆。现在的缓解手段是限制上传大小。
- **浏览器会话**：每个 Chrome 进程几百 MB（外部进程，不算 JVM 堆，但吃机器内存），
  所以做了 `max-sessions=3` + 空闲 5 分钟回收 + 进程树强制回收。
- **流式长连接**：SSE 每个连接占一个线程 120 秒，并发高时线程栈累积
  （默认 1MB/线程 × 几百线程 = 几百 MB）。所以异步池要有界。

### 4.2 GC

- **JDK 17 默认 G1**。`-XX:MaxGCPauseMillis`（默认 200ms）是**软目标**。
- G1 的**混合回收**：先回收全部 Young + 部分收益高的 Old（不像 CMS 全量 Old）。
- **CMS 已废弃**（JDK 9 起标记，JDK 14 移除）。
- 项目里我没调 GC 参数——**这是诚实的答案**，不要编造调优经历。
  如果要讲，讲"我会先看 GC 日志（`-Xlog:gc*`）和 `jstat -gcutil`，用 G1 默认参数基本够"。

**追问**：*"怎么排查线上 Full GC？"*
> 1. `jstat -gcutil <pid> 1000` 看各区使用率和 GC 次数；
> 2. `jmap -dump:format=b,file=heap.hprof <pid>` 抓堆（**注意会 STW，生产慎用**）；
> 3. MAT 分析 dominator tree，找占用最大的对象和 GC Root 引用链；
> 4. 常见元凶：静态集合缓存、ThreadLocal 未清理、大对象（byte[]）。
>
> 项目根目录里有个 `jstack.txt`——那是排查线程问题时留下的现场，说明我有用 jstack 看线程栈的习惯。

### 4.3 类加载与双亲委派

- 三层：Bootstrap（rt）→ Platform（JDK 9 后替代 Ext）→ Application → 自定义。
- **为什么用双亲委派**：保证 JDK 核心类不被篡改（安全性）+ 避免重复加载。
- **破坏双亲委派的场景**：SPI（JDBC 驱动用线程上下文类加载器 TCCL）、
  Tomcat 的 WebAppClassLoader（每个应用独立，优先自己加载）、OSGi、热部署。

**项目关联**：Spring Boot 的 fat jar 用 `LaunchedURLClassLoader` 加载 `BOOT-INF/lib`，
这就是对双亲委派的自定义扩展——**可以顺带讲，很自然**。

---

## 五、其它高频基础（快速过）

| 考点 | 你要能说的 |
| --- | --- |
| `String` 为什么不可变 | `final char[]`（JDK 9 后 `byte[]` + coder）、安全（类名/URL 参数）、哈希缓存、字符串常量池复用 |
| `==` vs `equals` | `==` 比地址（基本类型比值）；`equals` 默认同 `==`，重写后比内容；**重写 equals 必须重写 hashCode**（HashMap 一致性） |
| `StringBuilder` vs `StringBuffer` | 前者非线程安全快，后者 `synchronized` 慢；**单线程局部变量一律用 StringBuilder** |
| 接口默认方法 | JDK 8 引入，用于接口演进不破坏实现类；冲突时必须在实现类重写 |
| 异常体系 | `Exception`（受检/非受检）vs `Error`；**不要在 finally 里 return**（会吞掉 try 的返回值和异常） |
| 序列化 | `transient` 跳过；`serialVersionUID` 不一致会 `InvalidClassException` |
| 反射与注解 | Spring 的基础；反射慢的原因是**方法内联失效 + 安全检查 + 动态解析** |

**★ 一个可以主动讲的真坑（已经修掉了）**：
`PlanningAgent.execute()` 的 `finally` 块里访问 `result.status()`——
如果 `run()` 抛的是 `Error`（不是 `Exception`），catch 不住，`result` 为 null，`finally` 会 NPE
**覆盖掉原始错误**（排查时只看得到一个次生异常）。
修法是 finally 里兜一个失败态（`result != null ? result : new AgentResult(FAILED, ...)`），
保证 `ai_agent_task` 始终有终态落库。
> 讲这个的价值：它证明你会盯"异常处理边界"，而不是写完能跑就交。
> 补一句"我是 review 的时候发现的"更真实。
