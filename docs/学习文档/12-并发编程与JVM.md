# 12 · 并发编程与 JVM —— 线程池、锁、内存模型、GC

> 锚点：AIHub 里至少四处并发——AgentBudget 的 AtomicLong（无锁累加）、chat.vue 背后
> 的流式线程池（ChatController 自建 32 线程）、MVC 异步执行器（aihub-async-）、Caffeine
> 缓存的并发读写。本篇从这四个锚点讲透并发，再进 JVM。

---

## 1. 并发的三大问题——一切坑的根源

```
可见性：线程 A 改了变量，线程 B 可能看不到（CPU 缓存/编译器优化）
原子性：count++ 实际是 读→加→写 三步，中间可被打断
有序性：代码可能被重排执行（单线程内结果不变，多线程下翻车）
```

**最小 demo——亲眼看见竞态**：

```java
public class RaceDemo {
    static int count = 0;                        // 普通变量：不安全
    static AtomicLong safe = new AtomicLong();   // 原子变量：安全

    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(() -> { for (int i = 0; i < 100000; i++) { count++; safe.incrementAndGet(); } });
        Thread t2 = new Thread(() -> { for (int i = 0; i < 100000; i++) { count++; safe.incrementAndGet(); } });
        t1.start(); t2.start(); t1.join(); t2.join();
        System.out.println(count + " / " + safe.get());
        // 期望 200000，实际 count 经常是 13 万~19 万（每次都不同！）
        // safe 永远是 200000 —— 这就是 AgentBudget 用 AtomicLong 的原因
    }
}
```

三种修复对应三种手段：`synchronized`（互斥锁，最重）、`AtomicLong`（CAS 无锁，最轻）、`LongAdder`（分段计数，超高并发写更快）。

---

## 2. 线程池——参数决定生死

### 2.1 七个参数（背下来，面试必问）

```java
// AIHub 流式线程池的真实配置（ChatController）
new ThreadPoolExecutor(
    16,                    // corePoolSize  核心线程：常驻不回收
    64,                    // maxPoolSize   最大线程：队列满了才扩到这个数
    60, TimeUnit.SECONDS,  // keepAlive     非核心线程空闲 60s 回收
    new LinkedBlockingQueue<>(200),   // workQueue      等待队列
    r -> new Thread(r, "aihub-stream-"),// threadFactory  起名字（排障神器）
    new AbortPolicy()      // handler 拒绝策略：队列满+线程满 → 抛异常
);
```

**执行顺序必须记牢**（90% 的人答错）：来任务 → 核心线程 → **队列** → 扩到最大线程 → 拒绝策略。**先排队再扩容**，不是先扩容。

**四种拒绝策略**：AbortPolicy 抛异常（默认，适合"快速失败"）、CallerRunsPolicy 谁提交谁执行（反压，适合"不能丢任务"）、Discard 静默丢、DiscardOldest 丢最老。

### 2.2 项目实战决策

- **流式任务为什么自建线程池**：Tomcat 工作线程要留给 HTTP 请求接收；流式任务动辄几十秒，占着容器线程会拖垮整个服务；
- **AI 服务 MVC 异步执行器**（WebMvcConfig）：core 16 / max 64 / 队列 200 —— 流式回答是长任务，队列给大防止直接拒绝；
- **为什么线程要起名字**：线程转储（jstack）里看到 `aihub-stream-3a2f` 就知道是流式任务卡住了——无名线程是排障噩梦。

**拒绝策略实战**：流式对话用 AbortPolicy（拒绝就给前端报错，宁可失败不可堆积）；日志采集类用 Discard（丢了无所谓）。

---

## 3. 锁——从 synchronized 到 CAS

### 3.1 三种手段的谱系

```java
// ① synchronized：JVM 内置互斥锁（自动加锁释放，简单粗暴）
synchronized (cache) {          // AIHub ChatClientFactory 的双重检查锁就是它
    if (cache.get(key) == null) { cache.put(key, build()); }
}

// ② CAS：比较并交换（CPU 原子指令，无锁）
AtomicLong usedTokens;          // AgentBudget：期望旧值==实际值才写入新值
// 失败就自旋重试；无阻塞但高竞争下自旋浪费 CPU

// ③ 显式锁 ReentrantLock：可中断/可超时/公平锁（synchronized 做不到的才用它）
ReentrantLock lock = new ReentrantLock();
if (lock.tryLock(3, TimeUnit.SECONDS)) { try { ... } finally { lock.unlock(); } }
```

### 3.2 双重检查锁（DCL）——ChatClientFactory 在用的模式

```java
if (entry == null) {                       // ① 第一次检查（无锁，快路径）
    synchronized (lock) {                  // ② 加锁
        if (entry == null) {               // ③ 第二次检查（防止重复创建）
            entry = build();               // ④ 只有一个线程真正装配
        }
    }
}
// 为什么两次检查？①拦截绝大多数请求（已装配就直接返回）
// ③防止"两个线程同时通过①、排队先后进来各建一次"
```

**instance 一定加 volatile**：`new` 对象是"分配内存→初始化→赋引用"三步，重排可能让别的线程拿到"已赋引用但未初始化"的半成品——JMM（Java 内存模型）的经典考点。

---

## 4. JMM 与 volatile

```java
// 没有 volatile：while(!flag) 可能永远循环——另一个线程改的 flag "看不见"
static boolean flag = true;                  // ✗
static volatile boolean vflag = true;        // ✓ 可见性 + 禁止重排

// volatile 两个保证：写立即刷主存、读绕过缓存；一个不保证：原子性
// volatile++ 依然不安全！ 原子性用 Atomic 系或锁
```

记忆口诀：**volatile 管可见性和有序性，不管原子性；synchronized 三样全管但重**。

happens-before 规则（面试）：程序顺序、锁的解锁先于加锁、volatile 写先于读、线程 start/join——满足任一，前面的写对后面的读可见。

---

## 5. JVM——内存区域与 GC

### 5.1 运行时内存区域

```
线程私有：虚拟机栈（局部变量/栈帧，StackOverflowError 在这）｜程序计数器｜本地方法栈
线程共享：堆（对象，GC 主战场，OutOfMemoryError 在这）｜方法区/元空间（类信息，JDK8 起用元空间替代永久代）
```

**两个最常见的 OOM**：
- `StackOverflowError`：递归没出口——排查栈帧就是"调用链"；
- `Java heap space`：对象堆积——流式场景常见于"把整个回答攒 StringBuilder 不释放"或缓存没上限。

### 5.2 GC 一页通

```
分代假设：大部分对象朝生夕死 → 按年龄分代回收
新生代：Eden + 两个 Survivor —— Minor GC 频繁但快（复制算法）
老年代：大对象/长寿对象 —— Major/Full GC 慢（标记整理）
```

**判断对象能回收**：可达性分析（从 GC Roots 出发，够不着的就回收）——注意"被 static/线程局部引用着的"都是 Roots，所以 ThreadLocal 不清理 = 内存泄漏。

### 5.3 实用三板斧（不用背参数，会用工具）

```bash
jps                     # 找进程号
jstack <pid> > dump.txt # 线程转储：卡住/死锁时看线程停在哪一行（AIHub 排障真实用过）
jmap -histo <pid>       # 对象统计：谁占内存
# 堆转储 + MAT 分析、GC 日志（-Xlog:gc）——深入时再学
```

AIHub 实战回顾：RAG 检索卡死时，就是 jstack 看到线程停在 `RedisKnowledgeRetriever.retrieve` 才锁定的——**工具比背书有用**。

---

## 6. 面试视角速记

| 高频问题 | 一句话回答 |
|---|---|
| 线程池参数和执行顺序？ | 七参数；核心→队列→最大→拒绝（先排队后扩容） |
| synchronized 和 ReentrantLock 区别？ | 前者 JVM 自动管；后者可中断/超时/公平/多条件变量 |
| volatile 能否替代锁？ | 只保证可见性/有序性，不保证原子性 |
| JVM 内存分区？ | 线程私有（栈/PC）+ 共享（堆/元空间） |
| 对象什么时候被回收？ | 可达性分析：GC Roots 不可达；引用计数法有循环引用缺陷，JVM 不用 |
| AtomicLong 原理？ | CAS 自旋；高并发写换 LongAdder（分段降低竞争） |

---

## 7. 自测与进阶

1. 跑 RaceDemo 并尝试三种修复，观察输出差异。
2. ChatClientFactory 的 DCL 为什么要 volatile？去掉会出什么问题？
3. 用 jstack 分析一次"流式对话卡住"（构造一个 Thread.sleep(60000) 的接口试试）。
4. AgentBudget 为什么选 AtomicLong 而不是 synchronized？什么场景该换 LongAdder？
5. 进阶：AQS 源码（ReentrantLock 的骨架）、ThreadLocal 内存泄漏图解、虚拟线程（Java 21，IO 密集场景革命）。
