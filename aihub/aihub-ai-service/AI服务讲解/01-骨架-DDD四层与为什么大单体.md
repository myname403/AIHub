# 第一轮：DDD 四层与"为什么故意做胖"

> 目标：150 个类不再可怕——因为它们分布在 4 个层，每层职责一句话，类的归属靠一道判断题决定。

---

## 1. 先回答"为什么这个服务这么大"

启动类注释写得很直白（这是**架构决策**，不是没拆好）：

> 模型网关、对话、RAG、工具、MCP、Agent、会话、产物**全部在本服务内完成，绝不拆分**——
> 避免三大分布式难题：① 流式响应跨服务传递（SSE 断了算谁的）；② Agent 长任务跨服务编排（状态放哪）；③ 分布式事务（扣配额和写对话记录怎么保证一致）。

这叫**"模块化单体思想用在微服务内部"**：对外它是一个微服务（和 platform 平级），对内用**包**（而不是进程）划分模块边界。
一句话记忆：**边界用包画，进程就一个**——拆到包级享受清晰架构，不拆到进程级躲开分布式税。

---

## 2. 四层地图（对照目录看）

```
com.aihub.ai/
├── AiServiceApplication   启动类（@EnableFeignClients 调平台、@MapperScan 连库）
├── web/         【开门】ChatController / KnowledgeController / AgentController
│                / ModelController / ArtifactController + ws/ + sink/（SSE/WS/NDJSON）
├── application/ 【指挥】ChatAppService / KnowledgeAppService / AppAdminService
│                / ModelAdminService / AppQueryService —— 编排流程，不写业务细节
├── domain/      【定规矩】
│   ├── model/   纯业务对象：App / Chunk / AgentTask / ModelRoute / StreamEvent...（record 居多）
│   ├── spi/     ★20 个"端口"接口：ModelGateway / ChatExecutor / KnowledgeRetriever
│   │            / Agent / StreamSink / *Repository —— 只声明"我要什么"，不管谁给
│   └── support/ TextChunker（纯算法，无框架）
└── infra/       【干活】所有 spi 接口的实现 + 外部世界对接
    ├── ai/      ChatClientFactory / SpringAiChatExecutor / DbModelGateway
    │            / OpenAiCompatibleChatModelResolver / Redis*Indexer/Retriever
    │            / agent/*（7个Agent）/ tools/*（3个工具）/ DbChatMemory / AiCallContext
    ├── persistence/ 16 个 DO + Mapper + Db*Repository（domain spi 的落库实现）
    ├── browser/ ChromeLauncher / CdpBrowserDriver / PageAnnotator（真开浏览器）
    ├── storage/ Local/Minio IngestFileStore（文件存哪）
    ├── outbox/  DbEventOutbox + Dispatcher（用量上报失败重投）
    └── metrics/ AiMetrics（Micrometer 埋点）
```

---

## 3. 依赖方向是灵魂：一切朝 domain 收敛

```
web → application → domain ← infra
                     ▲        │
                     └────────┘  （infra 实现 domain 的 spi 接口）
```

**铁律**：`application` 只准 import `domain` 和 `common`，**绝不准 import `infra`/`web`**——
这条不是口头约定，是 **ArchUnit 测试在 CI 里强制卡**（注释原话"由 ArchUnit 卡口强制"）。违反就构建失败。

为什么要这么拧？看一个真实例子（`AgentNames` 的注释）：
- Agent 名字常量若定义在 infra 的 `PlanningAgent` 类上，那 application 按名派发时就得 `import infra.PlanningAgent` → **依赖方向反了**（外层依赖内层，内层却反过来被外层引用）；
- 解决：把常量提到 `domain/model/AgentNames`，application 和 infra **各自 import domain 这同一份**，依赖永远朝内。

**这就是"依赖倒置"落到实处的样子**：不是喊口号，是"接口(端口)归内层定义、实现(适配器)归外层提供"。

### SPI（端口）到底解决了什么
`domain/spi/ModelGateway` 说"我要一个按场景路由模型的能力"，但**不关心**背后是 DB 配置、还是 Nacos、还是写死。
`infra/DbModelGateway` 来兑现它。于是：换模型来源 = 换 infra 实现，application/domain 一行不改；
单测时塞个假实现即可——**这就是那 20 个空接口存在的全部意义**（不是过度设计）。

---

## 4. 三个"包内设计彩蛋"（面试可讲）

1. **domain 不碰框架**：`App.java` 注释——领域层不许出现 Spring AI/MyBatis/Redis；
   但 **Lombok 可以用**（编译期生成代码、不给字节码引入运行时依赖）——这条边界很见功力；
2. **异常也在 domain 归一**：`BrowserException` 是领域异常，infra 各种 CDP/进程异常都要"升格"成它再往上抛，上层才有一致的可读提示；
3. **record 用在该用的地方**：`AgentTask/StreamEvent/ModelRoute` 这类"值对象"用 record（不可变、天生线程安全、代码极短）；
   需要 Nacos 热更新的配置类**反而不能用 record**（见第 5 点）。

---

## 5. 一个高频困惑：为什么配置类用 @ConfigurationProperties 不用 @Value？

`AgentProperties` 注释讲得极清楚，这是**热更新**的分水岭：

| | @Value | @ConfigurationProperties |
|---|---|---|
| Nacos 改配置后 | **不回填**（只在启动时绑一次） | ConfigurationPropertiesRebinder 收到变更事件**整体重绑**，下次读取即新值 |
| 前提 | — | 类必须是**可变 JavaBean**（有 setter）——写成 record 会**静默失去热更新能力** |

所以：Agent 三重预算、RAG 的 top-k/相似度阈值、ChatClient 缓存 TTL 都做成了 `@ConfigurationProperties`，
在 Nacos 上改完不重启即生效。
**但有个反例值得玩味**：`chunk-size/chunk-overlap` 仍在 application 层用 `@Value`（改后要重启）——
因为把它们挪到 infra 配置类会违反 ArchUnit（application 不准 import infra），**架构约束倒逼出了一个"热更新盲区"**。
好的设计是有代价的，这个细节就是活教材。
