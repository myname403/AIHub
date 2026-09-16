# 04 · Spring 与微服务

---

## 一、Spring Boot 自动装配

### 1.1 原理（必须能一口气讲完）

`@SpringBootApplication` = `@Configuration` + `@ComponentScan` + **`@EnableAutoConfiguration`**。

自动装配的流程：
1. `@EnableAutoConfiguration` 上的 `@Import(AutoConfigurationImportSelector)` 生效；
2. `AutoConfigurationImportSelector` 读取
   **`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`**
   （Boot 3.x 起；2.x 是 `spring.factories`），拿到几百个配置类全限定名；
3. 每个配置类上挂 `@ConditionalOnXxx` 条件，符合条件才装配：
   - `@ConditionalOnClass`：classpath 有这个类（**有没有引入依赖**）
   - `@ConditionalOnMissingBean`：容器里没有同类型 Bean（**允许用户自定义覆盖**）
   - `@ConditionalOnProperty`：配置开关
4. 顺序控制：`@AutoConfiguration(before/after)`、`@Order`。

### 1.2 ★ 你项目里的真实案例（讲这个比背原理有说服力）

我加 MCP 的 **streamable-http** 传输时，原本以为要加新依赖或新模块。
查了以后发现：`McpServerStreamableHttpWebMvcAutoConfiguration` 这个自动配置类
**就在已经引入的 `spring-ai-starter-mcp-server-webmvc` 里**，
只要 `spring.ai.mcp.server.protocol=streamable` 就自动生效，端点默认 `/mcp`。

> 我是怎么确认的：直接去本地 Maven 仓库里找到 jar，用 `javap` 反编译确认类存在、
> 读它的 `@ConditionalOnProperty` 前缀。
> **讲出"我怎么验证的"，比"我知道"可信十倍。**

### 1.3 追问

- *"starter 和 autoconfigure 的区别？"*
  starter 是**依赖聚合**（pom，只管把相关 jar 拉进来，通常没有代码）；
  autoconfigure 是**装配逻辑**（有代码、有 `AutoConfiguration.imports`）。
- *"想排除某个自动配置怎么做？"*
  `@SpringBootApplication(exclude = XxxAutoConfiguration.class)` 或配置
  `spring.autoconfigure.exclude`。项目里 MCP 就用过类似思路：
  `spring.ai.mcp.server.annotation-scanner.enabled: false`
  关掉用不到的注解扫描器（否则启动时会打一条 `No tool methods found` 的 WARN 误导人）。

---

## 二、Bean 生命周期与循环依赖

### 2.1 三级缓存解决循环依赖

- **一级 `singletonObjects`**：成品 Bean；
- **二级 `earlySingletonObjects`**：半成品（已实例化、未填充属性）；
- **三级 `singletonFactories`**：`ObjectFactory`，用于**提前 AOP 代理**。

流程：A 实例化 → 把自己（工厂）放三级 → 填充属性时发现要 B → B 实例化 →
B 填充属性要 A → 从三级拿到 A 的早期引用 → B 完成 → A 完成。

**关键追问**：
- *"三级缓存能解决所有循环依赖吗？"* → **不能**。
  构造器注入的循环依赖、prototype 作用域的循环依赖都解决不了
  （前者连实例化都做不到，后者根本不缓存）。
- *"为什么需要三级？二级不够吗？"* → 因为 AOP。
  如果 A 需要被代理，B 拿到的是**代理对象**还是**原始对象**？
  三级缓存的 `ObjectFactory` 让"是否生成代理"这件事**延迟到真正被引用时才决定**，
  保证 B 注入的和最终放进一级缓存的是同一个对象。

### 2.2 我项目里的循环依赖与解法

`PlanningAgent ↔ AgentRegistry` 的环，用 **`@Lazy` + `ObjectProvider`** 解决（详见 [02](02-架构设计题.md) Q4）。

**观点（可主动讲）**：三级缓存是 Spring 的"兜底"，不是设计目标。
**出现循环依赖通常意味着职责划分有问题**，`@Lazy` 只是让启动能过。

---

## 三、事务

### 3.1 `@Transactional` 失效的 6 种场景（必背）

1. **方法不是 public**（Spring AOP 基于代理，非 public 不拦截）；
2. **同类内部调用**（没走代理，`this.method()`）；
3. **异常被 catch 吞掉**（没抛出去，AOP 不知道要回滚）；
4. **抛的是受检异常且没配 `rollbackFor`**（默认只回滚 `RuntimeException`/`Error`）；
5. **数据库引擎不支持**（MyISAM）；
6. **传播行为配错**（如 `NOT_SUPPORTED`、`NEVER`）。

**追问**：*"同类内部调用怎么解决？"*
→ 注入自己（`@Lazy` 自注入）、`AopContext.currentProxy()`（需开 `exposeProxy`）、
或把方法拆到另一个 Service。**最优解是重构**。

### 3.2 ★ 你项目的配额扣减为什么没用事务？

**这是很好的诚实题**。

`QuotaService.consume()` 是**两阶段**：
1. 遍历所有维度策略，试算 `used + amount > limitValue` → 超限则整体拒绝；
2. 全部通过才统一执行 upsert。

它**没有加事务**，代码注释里也承认并发下有**超扣窗口**。

**为什么不加（我的解释）**：
- 配额校验在**请求热路径**上（每次对话都要走），加事务会增加锁持有时间；
- 跨库（AI 服务 → 平台服务是 Feign 调用），本来就没法用本地事务，
  要一致就得上分布式事务（TCC/Saga），成本高；
- **配额是"软约束"**：超扣一点点可接受，业务上不会造成资损事故。

**如果要求强一致怎么改**（追问必答）：
1. **Redis + Lua**：把"读-判断-累加"做成原子脚本，一次调用完成；
2. **预扣 + 确认/释放**：调用前预扣，成功后确认，失败/超时释放（类似 TCC）；
3. **数据库悲观锁**：`SELECT ... FOR UPDATE` 锁住配额行（性能差，不推荐）；
4. **分段配额**：把额度切成 N 段，每次随机抢一段，减少热点（类似 Redisson 的分段锁思路）。

---

## 四、配置热更新（Nacos）

### 4.1 机制

`spring.config.import: optional:nacos:xxx.yaml` → Nacos 配置变更 →
Spring Cloud 发 `RefreshEvent` → 被 `@RefreshScope` 标注的 Bean **销毁重建**，
被 `@ConfigurationProperties` 标注的 Bean **重新绑定属性**。

### 4.2 ★ 真实坑：record 不能热更新

我在做热更新时踩到：**`@ConfigurationProperties` 的类必须是可变 JavaBean**
（有 setter / 有默认构造器）。用 `record` 或构造器绑定（`@ConstructorBinding`）时，
**重新绑定不会发生，配置会静默失效**——没有任何报错。

另外，**使用方必须在调用时读属性值，不能在启动时拷贝到本地字段**，
否则拷贝的那份永远不会更新。

项目里 `AgentProperties`（Agent 三重预算）、`RagProperties`（topK / 相似度阈值）、
`ChatClientProperties`（缓存 TTL）都遵守了这个约定，
并且在 Nacos 配置模板里给每个键标了 🔥（热更）/ ❄️（需重启）。

### 4.3 配置优先级（从高到低）

命令行参数 > `application-{profile}.yml`（jar 外）> jar 内 > `bootstrap.yml` >
**Nacos 配置**（`spring.cloud.config` 系列）> 默认值。
（注：Boot 2.4+ 起 `spring.config.import` 改变了这套顺序，Nacos 的优先级取决于导入位置。）

---

## 五、Spring Cloud Gateway

### 5.1 你项目里的过滤器链

全局过滤器按顺序：
1. `TraceIdGlobalFilter`（order = `Integer.MIN_VALUE`）—— 最先执行，生成/透传 TraceId；
2. `AuthGlobalFilter`（order = **-100**）—— JWT 校验；
3. 路由转发。

**★ 关键安全细节**：`AuthGlobalFilter` 对 `X-Tenant-Id` / `X-User-Id` 是
**先 `remove` 再 `set`**——防止客户端伪造这两个头。
这是多租户系统的**第一道防线**，面试官听到会点头。

API Key 通道（开放平台）只写租户头、**不写用户头**（因为 API Key 不携带用户身份）。
鉴权失败手写 401 JSON `{"code":10002,...}`。

### 5.2 追问：Gateway 是 WebFlux，和 MVC 有什么区别？

- **线程模型**：MVC 是 **一请求一线程**（阻塞 IO，线程池打满就拒绝）；
  WebFlux 是 **事件循环**（少量线程处理大量连接，非阻塞）。
- 所以 Gateway 里**不能用 `ThreadLocal`**（线程不固定对应请求）——
  要用 Reactor 的 `Context`（`Mono.subscriberContext()` / `contextWrite`）。
- 也**不能在 Gateway 里写阻塞代码**（会拖垮事件循环）。

> 我项目里 Gateway 只做鉴权和路由，没有业务逻辑，就是为了避免踩这个坑。

### 5.3 网关的其他职责（可展开）

- **限流**：Sentinel `gw-flow` 规则（按路由维度），触发返回 429 `{"code":10006}`；
- **跨域**：`CorsWebFilter`；
- **重试/熔断**：装饰器模式，一般不推荐在网关做重试（会放大流量）。

---

## 六、OpenFeign

### 6.1 你项目里怎么透传上下文？

`FeignTenantConfig` 里定义 `RequestInterceptor` Bean，往请求里塞三个头：
`X-Tenant-Id`、`X-User-Id`、`X-Trace-Id`。
AI 服务通过 `WebMvcConfig` 上的 `@Import(FeignTenantConfig.class)` 让它生效。

### 6.2 常考

- **Feign 的底层**：动态代理 + `RestTemplate`/`Client` + 负载均衡（Spring Cloud LoadBalancer）。
- **超时**：`connectTimeout` + `readTimeout`，**默认很短**（Connect 10s / Read 60s，不同版本不同），
  生产必须显式配置。
- **fallback**：需要引入 `spring-cloud-starter-circuitbreaker-resilience4j` 或 Sentinel。
  我项目里 AI 侧的配额调用**不用 fallback，而是 catch 异常放行**（降级策略：宁可不扣配额也不能拒绝服务）。
  但网关侧的 API Key 校验是 **fail-closed**（平台服务不可用就拒绝）——
  **鉴权必须 fail-closed，配额可以 fail-open**，这个对比讲出来很加分。

---

## 七、Sentinel

### 7.1 限流算法（必考）

| 算法 | 原理 | 特点 |
| --- | --- | --- |
| **计数器（固定窗口）** | 每窗口计数 | 实现简单，但**临界突刺**（窗口交界处双倍流量） |
| **滑动窗口** | 把窗口切小格，按时间滑动 | Sentinel 默认用的就是这个思路（`LeapArray`） |
| **漏桶** | 恒定速率出水 | 平滑，但**无法应对突发** |
| **令牌桶** | 恒定速率生成令牌，允许突发 | Guava `RateLimiter` 用这个，适合有突发流量的场景 |

**Sentinel 的核心**：滑动窗口统计 + 令牌桶/漏桶的流控效果（快速失败 / Warm Up / 排队等待）。

### 7.2 你项目里的规则持久化

三个服务都接了 `sentinel-datasource-nacos`：

| 服务 | Data ID | rule-type |
| --- | --- | --- |
| gateway | `aihub-gateway-flow-rules` | `gw-flow`（按路由限流） |
| ai-service | `aihub-ai-service-flow-rules` / `-degrade-rules` | flow / degrade |
| platform | `aihub-platform-service-flow-rules` | flow |

**★ 必须讲清的单向性**（很多人搞错）：
> 规则流向是 **Nacos → Sentinel 内存**（启动和变更时拉取）。
> 在 Sentinel Dashboard 上手工改的规则**只在本进程内存，重启即失**。
> 要持久化，最终落点必须是 Nacos 的 data-id。

单机模式（没起 Nacos）时拉取失败只打 WARN，**不阻断启动**。

### 7.3 熔断（degrade）

三种策略：慢调用比例、异常比例、异常数。
状态机：**Closed → Open（熔断，直接拒绝）→ Half-Open（放一个探测请求）→ Closed/Open**。

**追问**：*"熔断和降级的区别？"*
熔断是**被动保护**（下游挂了自动切断）；降级是**主动取舍**（压力大时关掉非核心功能，给一个兜底结果）。

---

## 八、Spring AI 与 Spring 的结合点（给 Agent 岗）

| Spring 概念 | Spring AI 对应 | 说明 |
| --- | --- | --- |
| `RestTemplate` / `WebClient` | `ChatModel` | 模型调用被抽象成接口 |
| Filter 链 | **Advisor 链** | `MessageChatMemoryAdvisor`、`AuditAdvisor` 都是 Advisor，和 Servlet Filter 同构 |
| `@Bean` 装配 | `ChatClient.Builder` | 由 starter 自动配置，注入即用 |
| 方法级注解 | **`@Tool`** | 类似 `@Scheduled`，方法即工具，由 `MethodToolCallbackProvider` 扫描注册 |
| `ApplicationRunner` | MCP 客户端连接 | 配置即连 |

**可主动讲的观点**：
> Spring AI 最有价值的设计是**把 LLM 调用纳入了 Spring 的 IOC 和 AOP 体系**，
> 所以我能直接用 Advisor 做审计——`AuditAdvisor` 包住 call 和 stream 两条路径，
> 把每次调用的 token、耗时、租户写进审计表。这在 LangChain 体系里要手写不少胶水代码。

---

## 九、高频短问

| 问题 | 答法要点 |
| --- | --- |
| Bean 的作用域？ | singleton（默认，**非线程安全，不要在单例里放可变状态**）、prototype、request、session、application |
| `@Autowired` 和 `@Resource`？ | 前者按**类型**（Spring 提供），后者按**名称**（JSR-250）。类型冲突时用 `@Qualifier` 或 `@Primary` |
| `@Configuration` 的 `proxyBeanMethods`？ | 默认 `true`（CGLIB 代理，保证 `@Bean` 方法间调用返回同一实例）；`false`（lite 模式，启动更快） |
| AOP 失效场景？ | 同类内部调用、final 类/方法（CGLIB 无法代理）、非 Spring 管理的对象 |
| 过滤器 vs 拦截器 vs AOP？ | 过滤器（Servlet 容器级，最外层）→ 拦截器（Spring MVC，能拿到 HandlerMethod）→ AOP（方法级，能拿到参数返回值） |
