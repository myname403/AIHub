# 04 - 网关 aihub-gateway 讲解

> **阅读说明**：本文自成一体，不依赖其他文档即可独立阅读。所有代码片段均摘自项目真实源码并标注来源路径与行号；行号与代码同步，读者可按 `文件路径#行号` 直接跳转对照。源码根目录约定：后端在 `aihub/`，管理端在 `aihub-admin-ui/`，用户端在 `aihub-app/`（均相对仓库根目录）。

---

## 1. 网关的职责：为什么需要它

### 1.1 没有网关的世界 vs 有网关的世界

先想"为什么"，再谈"是什么"。假设这个 AI 平台没有网关，前端（管理端 8849、用户端 H5 5173）要直连每个后端服务：

```
没有网关：
  管理端 8849 ──┬──> platform-service 8081（登录、用户/角色/菜单、租户、用量）
  用户端 5173 ──┴──> ai-service        8082（知识库、文档、对话、模型）
  第三方系统 ────> ???

  带来的问题：
  ① 前端要维护每个服务的地址表，服务迁移/扩容后前端跟着改；
  ② 每个服务都要自己写一遍 JWT 校验、CORS、限流——重复且容易漏；
  ③ 没有统一的限流闸门，洪峰直接打到下游；
  ④ 第三方系统不知道该调谁，凭证体系无处安放。
```

有了网关，所有流量先进唯一入口（端口 8080），再按 URL 前缀分发：

```
有网关：
  管理端 8849 ──┐
  用户端 5173 ──┼──> aihub-gateway 8080 ──┬──> aihub-platform-service 8081
  第三方系统 ───┘        │                  │     （/auth/**、/api/platform/**）
                         │  ① 鉴权：JWT / API Key 统一校验一次
                         │  ② 限流：Sentinel 拦洪峰（429 兜底）
                         │  ③ 链路：每个请求发 TraceId
                         │  ④ 跨域：统一 CORS 应答预检
                         └──> aihub-ai-service 8082
                               （/api/ai/**）
```

这段"为什么需要网关"的设计意图，在启动类注释里就有原文记录（`aihub/aihub-gateway/src/main/java/com/aihub/gateway/GatewayApplication.java#8-18`）：

```java
 * 网关服务启动类 —— 整个系统的唯一入口（端口 8080）。
 *
 * <p><b>为什么需要网关？</b>没有网关时，前端要记住每个服务的地址（平台 8081、AI 8082...），
 * 而且每个服务都要自己做鉴权。有了网关：
 * <pre>
 *   前端 → 网关(8080) → 按 URL 前缀路由到对应服务
 *          ├── 鉴权：JWT / API Key 统一在这里校验一次
 *          ├── 限流：Sentinel 拦住洪峰
 *          └── 链路：给每个请求发 TraceId
 * </pre>
 * 下游服务只信任网关写进请求头的身份信息（X-Tenant-Id 等），自己不再重复鉴权。
```

### 1.2 五大职责逐个讲

| 职责 | 本项目落地方式 | 关键位置 |
|------|----------------|----------|
| 统一入口 | 所有前端与第三方只认 8080 | `aihub/aihub-gateway/src/main/resources/application.yml#1-2` |
| 鉴权 | 全局过滤器统一校验 JWT 与 API Key，解析出身份后透传请求头 | `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#83-103` |
| 路由 | 按路径前缀匹配，`lb://` 走注册中心负载均衡 | `application.yml#38-46` |
| 限流 | Sentinel 网关流控（gw-flow），规则持久化在 Nacos，命中返回 429 | `application.yml#47-69` |
| 跨域 | 全局 CORS 应答（含 OPTIONS 预检） | `application.yml#27-37` |

另有一项容易被忽略的职责：**可观测**。网关暴露 `health,info,gateway,prometheus` 端点供 Prometheus 抓取（`application.yml#92-103`），并给每个请求发 TraceId（见第 4.10 节）。

### 1.3 为什么鉴权放网关而不是各服务自查——两面性分析

这是面试必问的设计权衡题，必须两面都讲透。

**方案 A：鉴权放网关（本项目的选择）**

```
优点（集中式）：
① 一次校验，处处受益：JWT 验签逻辑只写在 AuthGlobalFilter 一处，
   下游服务零重复代码；新增服务自动被保护，"忘记鉴权"这类低级漏洞不复存在。
② 身份只解析一次：claims 里的 tenantId/userId 解析后写入请求头，
   下游直接读头即可（见 TenantResolveInterceptor），不用每个服务都引入 jjwt 依赖。
③ 密钥收敛：jwt-secret 只配在网关（application.yml#77-80），
   下游服务不需要知道签名密钥，泄露面最小。
④ 对第三方友好：API Key 体系只在网关存在，第三方系统不需要理解内部凭证。

风险与代价（单点性）：
① 单点故障：网关挂了全系统不可用——但这不是网关特有的问题，
   没有网关时 LB + 每服务鉴权同样存在入口依赖；缓解手段是网关多实例 + 无状态
   （本网关不存会话，JWT 无状态校验，天然可水平扩容）。
② 性能瓶颈与故障放大：所有请求都过鉴权，鉴权逻辑慢 = 全系统慢。
   所以本项目的校验是"本地验签"（不回查数据库），
   API Key 远程校验也加了 2 秒超时 + 60 秒缓存（application.yml#84-89）。
③ 内部旁路风险：如果攻击者能绕过网关直连 8081/8082，伪造 X-Tenant-Id 头即可越权。
   这是集中式鉴权最真实的软肋——防线必须配合"网络层隔离"：
   生产环境 8081/8082 只允许网关所在网段访问，不对公网暴露。
④ 密钥扩散的边界：网关和平台服务共享同一把 jwt-secret
   （平台签发、网关校验），两端都要保管好；改密钥需要两端同步。
```

**方案 B：各服务自查**——每个服务引入 jjwt 自己验签。优点是没有单点、服务可独立对外；缺点是校验逻辑重复 N 遍、密钥扩散到 N 个服务、新服务容易漏配，且"某服务忘了鉴权"只能靠代码评审发现。

**本项目的实际取舍**：开发与演示阶段规模小，集中式收益远大于风险；同时代码里已经做了三层防御弥补旁路风险——第一层，网关**强制剥离并覆盖**客户端伪造的 `X-Tenant-Id`/`X-User-Id` 头（`AuthGlobalFilter.java#157-164`）；第二层，下游 `TenantResolveInterceptor` 只信任网关写入的头（`aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantResolveInterceptor.java#45-46`）；第三层，平台侧 `/internal/**` 内部接口不注册到网关路由（见第 3.6 节），外部流量根本到不了。真实生产中还应该补第四层：网络隔离（安全组/内网段）。

---

## 2. 模块结构与启动类

### 2.1 文件清单

网关是全项目最小的一个可运行模块，全部 Java 源码只有 5 个类：

| 文件 | 职责 |
|------|------|
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/GatewayApplication.java` | 启动类 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java` | 鉴权全局过滤器（★ 安全核心） |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/TraceIdGlobalFilter.java` | 链路追踪过滤器 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyVerifier.java` | API Key 远程校验客户端（WebClient） |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyCache.java` | API Key 校验结果本地缓存（Caffeine） |

关键依赖（`aihub/aihub-gateway/pom.xml#16-80`）：

| 依赖 | 用途 |
|------|------|
| `spring-cloud-starter-gateway` | 网关本体，基于 WebFlux；**禁止再引入 spring-boot-starter-web**（pom 注释原文："网关基于 WebFlux，不可引入 spring-boot-starter-web"） |
| `spring-cloud-starter-alibaba-nacos-discovery` / `-config` | 注册发现与配置中心 |
| `spring-cloud-alibaba-sentinel-gateway` + `sentinel-datasource-nacos` | 网关限流 + 规则持久化 |
| `spring-cloud-starter-loadbalancer` | 让 `lb://` 协议可用 |
| `com.github.ben-manes.caffeine:caffeine` | API Key 校验结果缓存 |
| `io.jsonwebtoken:jjwt-*` | 网关本地验签（pom 注释原文："网关本地校验令牌，避免每个请求都回查平台服务"） |
| `spring-boot-starter-actuator` + `micrometer-registry-prometheus` | 健康检查与指标导出 |

### 2.2 技术底座：为什么网关是 WebFlux 而下游是 MVC

启动类注释讲得非常直白（`GatewayApplication.java#20-23`）：

```java
 * <p><b>本服务的技术底座是 WebFlux（响应式）而非传统 MVC：</b>
 * 网关要同时维持海量并发连接，传统"一个请求占一个线程"的模式线程开销太大；
 * 响应式用少量线程 + 事件回调处理所有请求。所以本服务的代码里到处是
 * {@code Mono} / {@code Flux}（见 AuthGlobalFilter），与下游 MVC 服务写法不同。
```

配置里也显式声明了（`application.yml#7-8`）：

```yaml
spring:
  main:
    web-application-type: reactive
```

对读者的实操含义：写网关代码时**绝不能出现阻塞调用**（`Thread.sleep`、同步 JDBC、`RestTemplate`），否则会卡死 Netty 的事件循环线程，拖垮整个网关。所以网关里发 HTTP 用的是响应式 `WebClient` 而不是阻塞的 RestTemplate/Feign（`ApiKeyVerifier.java#20-22` 注释原文："这里没用 Feign，因为 Feign 基于阻塞模型，与 WebFlux 不兼容"）。

### 2.3 启动类四段式讲解（GatewayApplication）

**作用与原理**：`GatewayApplication.java#41-48` 只有两个注解 + 一个 main 方法：

```java
@EnableDiscoveryClient
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        // 固定写法：run() 内部完成"创建 Spring 容器 → 自动装配 → 启动服务器"三步
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

`@SpringBootApplication` 是三个注解合一：`@SpringBootConfiguration`（本类是配置类）+ `@EnableAutoConfiguration`（按 classpath 自动装配——发现 classpath 里有 WebFlux 就装配 Netty 服务器，有 gateway starter 就装配路由体系）+ `@ComponentScan`（扫描本包及子包）。**所以启动类必须放在包结构根 `com.aihub.gateway` 上**，否则 `filter`、`apikey` 子包里的 Bean 扫不到。`@EnableDiscoveryClient` 启用 Nacos 注册发现——Spring Cloud 2020 之后 classpath 有 Nacos 就自动生效，显式写出语义更清晰。

**项目真实用例**：网关启动后把 `aihub-gateway` 注册进 Nacos（`application.yml#5-6`），下游虽然不回调网关，但 Prometheus 按 `application` 标签区分指标来源（`application.yml#101-103`）。

**常见误用**：把启动类挪进子包导致过滤器不生效，表现为"所有请求都 404 或都不鉴权"；或者手贱在网关 pom 里加了 `spring-boot-starter-web`，启动直接报 `Spring MVC found on classpath, which is incompatible with Spring Cloud Gateway`。

**面试怎么说**：一句话——"网关是 WebFlux 技术栈，一个入口线程模型跑海量并发；启动类三合一注解决定了包扫描根，依赖决定了我必须写响应式代码。"

---

## 3. 路由配置逐行讲解

### 3.1 路由配置原文

```yaml
# 来源：aihub/aihub-gateway/src/main/resources/application.yml#23-46
    gateway:
      discovery:
        locator:
          enabled: false
      globalcors:                      # （CORS 见第 6 章，此处省略）
        ...
      routes:
        - id: aihub-platform
          uri: lb://aihub-platform-service
          predicates:
            - Path=/auth/**,/api/platform/**
        - id: aihub-ai
          uri: lb://aihub-ai-service
          predicates:
            - Path=/api/ai/**
```

### 3.2 逐行含义

- **`discovery.locator.enabled: false`**（第 24-26 行）：关闭"按服务名自动路由"。若开启，任何注册进 Nacos 的服务都可以通过 `http://网关:8080/服务名/**` 被访问——方便但危险，等于把内部服务全部对外暴露。关掉它，**只有显式声明的路由才可达**，这是白名单思维。副作用是：新增服务必须来这个文件加路由，属于刻意为之的安全取舍。
- **`id: aihub-platform`**：路由的唯一标识，任意字符串但需唯一；Sentinel 网关限流的 resource 名就是它（见第 5.3 节，规则里的 `"resource": "aihub-platform"` 与此严格对应）。
- **`uri: lb://aihub-platform-service`**：`lb` = load balance。含义是"目标不是写死的 IP，而是注册中心里的**服务名**"。网关通过 Nacos 查到 `aihub-platform-service` 名下的所有健康实例，再用 Spring Cloud LoadBalancer（pom 里的 `spring-cloud-starter-loadbalancer`）从中选一个转发。好处：服务扩容/重启换端口后网关零配置；坏处：多一跳注册中心查询（有缓存，可忽略）。与之相对的写法是 `uri: http://localhost:8081`——写死地址，只适合临时调试。
- **`predicates: Path=/auth/**,/api/platform/**`**：断言，"什么路径走这条路由"。`/auth/**` 匹配登录相关接口，`/api/platform/**` 匹配平台管理接口。一个路由可以挂多个路径前缀（逗号分隔）。
- 第二条路由同理：`/api/ai/**` 全部转给 AI 服务。

### 3.3 路径映射全景表（与下游 Controller 对账）

关键点：**网关转发时保留原始路径前缀，没有配置 StripPrefix**。下游 Controller 的 `@RequestMapping` 必须带同样的前缀，两边对得上才通。逐一对账：

| 网关路由 | 转发目标 | 下游 Controller 实际前缀（已验证） |
|----------|----------|-----------------------------------|
| `Path=/auth/**` | `lb://aihub-platform-service` | `@RequestMapping("/auth")`（`aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/controller/AuthController.java#33`） |
| `Path=/api/platform/**` | 同上 | 如 `@RequestMapping("/api/platform/system/user")`（UserController#37）、`/api/platform/system/role`（RoleController#30）、`/api/platform/system/menu`（MenuController#27）、`/api/platform/usage`（UsageController#25）、`/api/platform/apikey`（ApiKeyController#51） |
| `Path=/api/ai/**` | `lb://aihub-ai-service` | 如 `@RequestMapping("/api/ai")`（ChatController#74）、`/api/ai/kb`（KnowledgeController#30）、`/api/ai/model`（ModelController#37）、`/api/ai/app`（AppController#29）、`/api/ai/agent`（AgentController#25） |

**为什么不配 StripPrefix**：`StripPrefix=1` 会把 `/api/platform/system/user` 剥成 `/system/user` 再转发，下游 Controller 就不用写 `/api/platform` 前缀。本项目没这么做，而是让下游保留完整业务前缀——好处是**看日志/看代码就知道这个接口是给谁用的**（`/api/platform/*` 是管理端、`/api/ai/*` 是用户端、`/auth/*` 是免认证入口），路径自解释，也让网关断言与下游映射一一对应，排障时少一层心智转换。

### 3.4 一个安全细节：`/internal/**` 在网关上不可达

平台服务还暴露了内部接口 `@RequestMapping("/internal")`（`aihub/aihub-platform-service/src/main/java/com/aihub/platform/internal/controller/InternalController.java#35`），如 `POST /internal/apikey/verify`（#60，供网关校验 API Key 用）。注意两条路由的 Path 断言**都不匹配 `/internal/**`**——外部请求打到 `http://网关:8080/internal/apikey/verify` 会 404。这是刻意的：内部契约接口只能被内网服务调用（网关→平台），不对外暴露。配合第 1.3 节说的网络隔离，构成"内部接口双保险"。

### 3.5 配置的其他部分速览

- **Nacos 客户端**（`application.yml#10-22`）：注册与配置共用 `127.0.0.1:8848`，账号密码支持环境变量覆盖（Nacos 3.x 默认开鉴权，不带凭证会 403——这是本项目真实踩过的坑，详见《12-实战调试问题集》）。
- **配置热更新**（#72-75）：`config.import: optional:nacos:aihub-common.yaml / aihub-gateway.yaml`，改 Nacos 上的配置无需重启网关。
- **健康与指标**（#92-103）：暴露 `gateway` 端点可查看全部路由与过滤器，排障利器。

**常见误用**（路由章节）：① 把 `lb://` 写成 `http://`，看似能通实则失去负载均衡与实例发现，服务多实例后流量永远打一个点；② 新增服务忘了加路由，请求 404，还以为是下游没起来——先 `curl http://localhost:8080/actuator/gateway/routes` 看路由表；③ 断言写成 `Path=/auth/*`（单星只匹配一层），`/auth/captcha/image` 这类两层路径就漏了。

**面试怎么说**："路由三要素是 id、uri、predicates；`lb://` 表示按服务名走负载均衡而非写死地址；我们关闭了 locator 自动路由、用显式白名单，且不做 StripPrefix，让路径前缀在网关和下游保持一致，换取可排障性。"

---

## 4. 全局过滤器源码逐段讲解（本章核心）

### 4.1 过滤器链全景

Spring Cloud Gateway 里有两类过滤器：GatewayFilter（绑定到某条路由）和 **GlobalFilter（对所有请求生效）**。多个 GlobalFilter 按 `getOrder()` 返回值**从小到大**依次执行，构成过滤器链。本项目的链是：

```
请求进入 8080
   │
   ▼
TraceIdGlobalFilter          getOrder = Integer.MIN_VALUE（最先执行）
   │  确定本次请求的 X-Trace-Id（沿用或新生成）
   ▼
AuthGlobalFilter             getOrder = -100
   │  白名单放行 / API Key / JWT 校验 → 注入 X-Tenant-Id、X-User-Id
   ▼
（框架内置过滤器：路由匹配、Netty 转发）
   │
   ▼
下游服务 8081 / 8082（读请求头里的租户与用户身份）
```

顺序为什么重要：TraceId 必须最先，这样**连 401 拒绝响应都带 traceId**（401 恰恰是客户端配置错误最多的地方，最需要链路 ID 排查）；鉴权必须在路由转发之前，否则身份头还没注入请求就出去了。

### 4.2 AuthGlobalFilter 类骨架与构造器

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#53-73
@Component   // 注册为 Spring Bean，Spring Cloud Gateway 自动收集所有 GlobalFilter 类型的 Bean
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** API Key 请求头 */
    private static final String HEADER_API_KEY = "X-API-Key";

    /** JWT 验签密钥（字节数组）。HS256 要求≥32字节；与平台服务 JwtTokenProvider 的签名密钥必须一致 */
    private final byte[] secretKey;

    /** API Key 校验客户端（带本地缓存），见 apikey 包 */
    private final ApiKeyCache apiKeyCache;

    public AuthGlobalFilter(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                            ApiKeyCache apiKeyCache) {
        this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
        this.apiKeyCache = apiKeyCache;
    }
```

三个设计点：
1. `implements GlobalFilter, Ordered`——一个类同时给出"做什么"（filter）和"排第几"（getOrder）。
2. **构造器注入**而非字段注入：依赖声明为 `final` 不可变、便于单测直接 new、Spring 官方推荐（#66-67 注释原文）。
3. 密钥从配置 `${aihub.security.jwt-secret}` 读取（`application.yml#77-80`，默认值仅开发用，注释原文："生产环境请放入 Nacos 并开启鉴权，不要使用默认值"）。**这把密钥必须与平台服务签发 JWT 用的密钥完全一致**——平台签、网关验，HS256 对称签名，不一致时全部令牌验签失败。

类注释里还有一句安全总纲（#40-47）：**租户 ID 只从凭证解析，绝不接受前端传参**。JWT 有签名伪造不了，API Key 由平台反查——两条路都绕不开服务端验证。

### 4.3 filter() 主流程：三分支分派

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#82-103
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 公开路径（登录、健康检查）直接放行，无需凭证
        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        // 鉴权分派：优先识别 API Key（第三方对接的固定头），其次走 JWT
        String apiKey = request.getHeaders().getFirst(HEADER_API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            return authenticateByApiKey(exchange, chain, apiKey);
        }

        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(exchange, "缺少认证令牌");
        }
        return authenticateByJwt(exchange, chain, token);
    }
```

主流程就是三个分支，读一遍就懂：

```
① isPublicPath(path) 为真 ──────> chain.filter(exchange) 放行（不校验任何凭证）
② 带 X-API-Key 头 ─────────────> authenticateByApiKey()：远程反查租户
③ 其余：取 Authorization 头 ───> 有 Bearer 令牌走 authenticateByJwt()
                                 没有 → unauthorized() 返回 401
```

返回值 `Mono<Void>` 是响应式编程的"完成信号"：返回 `chain.filter(...)` 表示"我处理完了，传给下一个过滤器"；返回 `writeWith(...)`（见 unauthorized）表示"到此为止，响应直接回给客户端"。`exchange` 是一次 HTTP 交互的上下文（请求 + 响应 + 属性袋），`mutate()` 可以基于它造出"改了请求头的副本"——因为请求对象是不可变的。

### 4.4 第一步：白名单放行（isPublicPath）

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#169-172
    /** 公开路径白名单：登录接口（发令牌前当然没令牌）和健康检查（探活不能要凭证） */
    private boolean isPublicPath(String path) {
        return path.startsWith("/auth/") || path.startsWith("/actuator/health");
    }
```

为什么必须有白名单：登录接口是"用来**发**令牌的"，请求方此刻手里还没有令牌，如果要求凭证，鸡生蛋死循环；健康检查是给探活脚本用的，也不能要凭证。

两个值得注意的细节：
- 前缀带**尾斜杠** `"/auth/"`——如果写成 `startsWith("/auth")`，那么 `/authorized-xxx`、`/authority` 这类不相干路径也会被放行，属于经典的白名单绕过笔误。
- 白名单只放行"发令牌前"的接口，范围最小化。登录**之后**的一切业务接口（`/api/platform/**`、`/api/ai/**`）都必须过校验。

**常见误用**：把白名单当"调试后门"临时加 `/api/**`，调试完忘了删——等于全网关裸奔。白名单的每次变更都应该走 code review。

### 4.5 第二步：取头（resolveToken）

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#174-181
    /** 从 Authorization 头解析 Bearer 令牌（OAuth2 标准格式：Bearer <token>） */
    private String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);   // 去掉 "Bearer " 前缀（7 个字符）
        }
        return null;
    }
```

遵循 OAuth2 标准格式 `Authorization: Bearer <jwt>`。`substring(7)` 正好跳过 `"Bearer "` 的 7 个字符（B-e-a-r-e-r-空格）。返回 null 表示请求根本没带令牌，主流程直接 401（"缺少认证令牌"）。

**常见误用**：前端把 token 放进自定义头（如 `token: xxx`）而不按标准头来，网关取不到就 401；或者 "Bearer" 拼错大小写/少了空格。规范的好处是任何一个标准客户端库都能无缝对接。

### 4.6 第三步：解析 claims（authenticateByJwt）——逐行精读

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#131-167
    /** JWT 通道：解析令牌取租户与用户 */
    private Mono<Void> authenticateByJwt(ServerWebExchange exchange,
                                         GatewayFilterChain chain, String token) {
        final String tenantId;
        final String userId;
        try {
            // 验签 + 解析 payload（与 common 的 JwtVerifier 同一套 jjwt API，网关自带一份）
            Claims claims = Jwts.parser()
                    .verifyWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(secretKey))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            // 令牌内的 tenantId 是租户上下文的唯一可信来源（签名保证不可伪造）
            tenantId = String.valueOf(claims.get("tenantId", Object.class));
            userId = String.valueOf(claims.get("userId", Object.class));
        } catch (Exception e) {
            // 签名不对 / 过期 / 格式错误，统一按 401 拒绝
            return unauthorized(exchange, "令牌无效或已过期");
        }

        // "null" 字符串防御：String.valueOf(null) 会产生 "null" 而不是 null
        if (tenantId == null || "null".equals(tenantId)) {
            return unauthorized(exchange, "令牌缺少租户信息");
        }

        // 重建请求头：先剥离客户端带来的同名头（防伪造），再写入从令牌解析出的可信值
        ServerHttpRequest mutated = exchange.getRequest().mutate()
                .headers(headers -> {
                    headers.remove(TenantContext.HEADER_TENANT);
                    headers.remove(TenantContext.HEADER_USER);
                    headers.set(TenantContext.HEADER_TENANT, tenantId);
                    headers.set(TenantContext.HEADER_USER, userId);
                })
                .build();

        return chain.filter(exchange.mutate().request(mutated).build());
    }
```

逐段拆解：

**① 验签与解析（#138-142）**。`Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secretKey)).build().parseSignedClaims(token)` 这条链做三件事：按 HS256 用密钥**验签**（签名对不上直接抛异常）、检查 **exp 过期时间**（过期抛 `ExpiredJwtException`）、解析 **payload（claims）**。注意这里**只验签不查库**——无状态校验，一次解析搞定，这正是网关能扛高并发的关键之一（对照 `aihub/aihub-gateway/pom.xml#65` 注释："网关本地校验令牌，避免每个请求都回查平台服务"）。

**② 取业务字段（#144-145）**。claims 里只取两个值：`tenantId`（租户）与 `userId`（用户）——这正是登录时平台服务签发进令牌的两个身份字段。注释强调"令牌内的 tenantId 是租户上下文的唯一可信来源（签名保证不可伪造）"：租户隔离的地基从这里打起，前端说什么都不算，**令牌里写的是什么才是什么**。

**③ 异常路径（#146-149）**。验签失败、令牌过期、格式损坏，全部 catch 成一个 401（"令牌无效或已过期"）。不对异常做细分是有意的：对客户端而言"你的凭证不可用"，具体原因（改过密钥？令牌真过期了？被人篡改？）不该透露给请求方，避免给攻击者提供探测信息。

**④ "null" 字符串防御（#151-154）**。这是 Java 老手才懂的坑：`String.valueOf(null)` 的结果是字符串 `"null"` 而不是 `null` 引用！如果令牌里恰好没写 tenantId，`claims.get("tenantId")` 返回 null，经 `String.valueOf` 一转就变成四个字符的 `"null"`，若只判断 `== null` 就漏过去了，下游会把字符串 `"null"` 当租户 ID 用。所以判断条件是 `tenantId == null || "null".equals(tenantId)` 双保险。

**⑤ 透传租户头（#156-166）**。这是整个过滤器最关键的安全动作：

```java
headers.remove(TenantContext.HEADER_TENANT);   // X-Tenant-Id —— 先删掉客户端可能伪造的
headers.remove(TenantContext.HEADER_USER);     // X-User-Id   —— 同上
headers.set(TenantContext.HEADER_TENANT, tenantId);  // 再写入令牌里解析出的可信值
headers.set(TenantContext.HEADER_USER, userId);
```

请求对象不可变，所以用 `mutate()` 造副本：**先 remove 再 set**。为什么要先 remove？因为下游的 `TenantResolveInterceptor` 就是信任 `X-Tenant-Id`/`X-User-Id` 这两个头的（常量定义在 `aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantContext.java#26-29`：`HEADER_TENANT = "X-Tenant-Id"`、`HEADER_USER = "X-User-Id"`）。如果攻击者手动带上 `X-Tenant-Id: 1002` 而网关不剥离，这个伪造头会原样穿透到下游——直接冒充别的租户看数据。剥离后，下游读到的头**一定**来自令牌验签，伪造链路被物理切断。

**⑥ 继续链路（#166）**：`chain.filter(exchange.mutate().request(mutated).build())`——注意必须把**改造后的 exchange** 传下去，传原 exchange 等于白改。

**常见误用**：① 网关与平台的 `jwt-secret` 不一致，所有请求 401，日志却是笼统的"令牌无效"——先对两端密钥配置；② 忘记 remove 直接 set，伪造头在某些 set 失败路径下漏网；③ 在过滤器里写阻塞代码（查库、同步 HTTP），卡死事件循环；④ 自己生成新的租户头名字而不复用 common 常量，网关写 `X-TenantID`、下游读 `X-Tenant-Id`，静默拿到 null，数据"看起来正常"实则隔离失效。

### 4.7 异常路径：unauthorized() 手写 401 响应

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#183-200
    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);   // HTTP 401
        exchange.getResponse().getHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json;charset=UTF-8");
        // 带上链路 ID：401 多发生在客户端配置错误时，没有 traceId 很难排查
        String traceId = exchange.getResponse().getHeaders().getFirst(TraceContext.HEADER);
        String tracePart = traceId == null ? "" : ",\"traceId\":\"" + traceId + "\"";
        // 手工拼 JSON（不用 Jackson 是因为这里格式固定且要避免额外依赖开销）
        String body = String.format("{\"code\":10002,\"message\":\"%s\"%s}", message, tracePart);
        // writeWith：把数据写入响应并结束请求 —— Mono.just 包装字节数组作为响应体
        return exchange.getResponse()
                .writeWith(Mono.just(exchange.getResponse()
                        .bufferFactory()
                        .wrap(body.getBytes(StandardCharsets.UTF_8))));
    }
```

为什么"手写"？下游 MVC 服务有 common 模块的 `@RestControllerAdvice` 全局异常处理器兜底，但网关是 WebFlux、且拒绝发生在业务代码之前，异常处理器管不到——只能自己写响应。三个细节：

1. **响应体与 common 的 `R` 结构对齐**：`{"code":10002,"message":"...","traceId":"..."}`（#184 注释原文："响应体格式与 R 保持一致"）。前端拦截器只认 `code` 字段，401 的 `code=10002` 与其他业务错误码共用一套约定，前端无需为网关单独写解析逻辑。
2. **traceId 从响应头拿**：因为链路最早的 TraceIdGlobalFilter 已经把 `X-Trace-Id` 回写进响应头（`TraceContext.HEADER = "X-Trace-Id"`，定义于 `aihub/aihub-common/src/main/java/com/aihub/common/trace/TraceContext.java#30`），这里取出来塞进响应体，让用户反馈"报错截图"时能直接给出链路号。
3. **手工拼 JSON 而非 Jackson**：格式固定、字段都是白名单内容（无用户输入，不存在注入风险），省掉序列化开销。注意 message 都是代码内常量字符串，不是用户输入——如果哪天把用户输入拼进来，这个 `String.format` 就是 JSON 注入点。

HTTP 状态码用 401（未认证）而非 403（已认证但无权限）：语义上"你没带有效凭证"。前端收到 401 的标准动作是跳登录页。

### 4.8 另一条凭证通道：API Key（authenticateByApiKey + apikey 包）

面向第三方系统对接的凭证不是 JWT，而是固定头 `X-API-Key: ak_xxx`。主流程在 `AuthGlobalFilter.java#106-129`：

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#106-129（节选）
        return apiKeyCache.verify(apiKey)
                .flatMap(principal -> {
                    // 重建请求：mutate() 是"基于原请求造一个修改过的副本"（请求对象不可变）
                    ServerHttpRequest mutated = exchange.getRequest().mutate()
                            .headers(headers -> {
                                // 强制覆盖，防止伪造租户头：先 remove 再 set，客户端带的假头在这里被清掉
                                headers.remove(TenantContext.HEADER_TENANT);
                                headers.remove(TenantContext.HEADER_USER);
                                headers.set(TenantContext.HEADER_TENANT,
                                        String.valueOf(principal.tenantId()));
                                // API Key 代表系统调用而非具体用户，显式清空用户头（不 set）
                            })
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .switchIfEmpty(Mono.defer(() -> unauthorized(exchange, "API Key 无效、已停用或已过期")));
```

要点：**API Key 只注入租户头、不注入用户头**——Key 代表"某个应用/系统"而不是具体的人（#46 注释原文）。校验失败或平台不可用时 `Mono` 为空，`switchIfEmpty + Mono.defer` 走拒绝路径（`Mono.defer` 延迟构造，避免 unauthorized 在不需要时提前执行）。

**ApiKeyVerifier**（`aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyVerifier.java#56-79`）负责真实的远程校验：

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyVerifier.java#61-78（节选）
        return webClient.post()                        // ① 发 POST
                .uri("/internal/apikey/verify")        // ② 完整地址 = baseUrl + uri
                .bodyValue(new VerifyRequest(plainKey)) // ③ 请求体：record 自动转 JSON
                .retrieve()                            // ④ 准备接收响应
                .bodyToMono(VerifyResponse.class)      // ⑤ 响应 JSON → VerifyResponse 对象
                .timeout(timeout)                      // ⑥ 2 秒没回来就抛 TimeoutException
                .flatMap(resp -> { ... })              // ⑦ code!=0 → empty；成功 → 传租户信息
                .onErrorResume(e -> {
                    // 平台服务不可用时拒绝而非放行：鉴权失败必须 fail-closed
                    log.warn("API Key 校验失败（拒绝请求）err={}", e.getMessage());
                    return Mono.empty();
                });
```

两个架构要点：① 网关不直连平台库（"禁止跨库访问"的服务边界约束），而是回调平台内部接口 `POST /internal/apikey/verify`（下游实现在 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/internal/controller/InternalController.java#60`）；② **fail-closed 原则**——平台服务挂了，校验结果"未知"，选择拒绝而非放行（#73-77 注释原文："不确定时宁可拒绝，绝不能'出错就放行'造成鉴权裸奔"）。超时 2 秒兜底，不让平台拖死网关（`application.yml#84-85`）。

**ApiKeyCache**（`aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyCache.java#67-87`）用 Caffeine 异步缓存校验结果，TTL 60 秒、上限 1 万条（`application.yml#88-90`）。设计权衡都在注释里：缓存命中省掉内网 HTTP 往返；60 秒 TTL 是"延迟"与"吊销及时性"的平衡——Key 被禁用后最多再放行 60 秒；缓存键用 `hashCode + 长度` 派生而非 Key 明文（#106-108），避免明文长期驻留堆内存被堆 dump 泄露。

### 4.9 TraceIdGlobalFilter：链路起点

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/TraceIdGlobalFilter.java#34-52
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        // 上游（前端/外部系统）带了 X-Trace-Id 就沿用，否则生成新的 —— 保证全链路同一 ID
        String incoming = request.getHeaders().getFirst(TraceContext.HEADER);
        String traceId = incoming == null || incoming.isBlank()
                ? TraceContext.newTraceId() : incoming;

        // 写入转发给下游的请求头：platform/ai 服务的 TraceIdInterceptor 会从这读
        ServerHttpRequest mutated = request.mutate()
                .header(TraceContext.HEADER, traceId)
                .build();
        // 同时回写响应头：前端控制台 Network 面板里就能看到本次请求的 traceId
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set(TraceContext.HEADER, traceId);

        // 继续过滤器链，传给下一个过滤器（鉴权）的是改造后的 exchange
        return chain.filter(exchange.mutate().request(mutated).build());
    }

    /** 最高优先级：Integer.MIN_VALUE，保证所有过滤器里最先执行 */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;   // TraceIdGlobalFilter.java#55-58
    }
```

沿用或新生成 `X-Trace-Id`，同时写进**转发头**（下游服务读）和**响应头**（前端 Network 面板可见）。日志格式里 `%X{traceId:--}`（`application.yml#108-110`）会自动打印它——全链路日志用同一个 ID 串起来，跨服务排障时 grep 一个 ID 就能还原整条请求轨迹。安全上，traceId 无鉴权含义，沿用客户端传值不构成越权（伪造它只影响"你自己的日志能不能被找到"）。

**常见误用**（过滤器章节总述）：① 把鉴权过滤器的 order 调到 TraceId 之后又小于内置路由过滤器，顺序错乱导致下游拿到的是没注入身份的头——记住口诀"追踪最先、鉴权居中、路由最后"；② 在 GlobalFilter 里调用阻塞 API；③ 修改请求头后传了原 exchange，改动静默丢失。

---

## 5. Sentinel 网关限流

### 5.1 规则类型与在哪儿配置

网关限流用的是 Sentinel 的**网关流控规则（gw-flow）**：按"路由维度"限流，resource 名就是第 3.2 节讲的路由 `id`。规则**不写在代码里**，而是持久化在 Nacos（`aihub/aihub-gateway/src/main/resources/application.yml#47-63`）：

```yaml
# 来源：aihub/aihub-gateway/src/main/resources/application.yml#47-63（节选）
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:127.0.0.1:8858}
      # 规则持久化：网关流控规则（gw-flow，按路由维度限流）从 Nacos 拉取，
      # dashboard 或 Nacos 控制台改规则不再"重启即丢"。规则样例见 docs/nacos/。
      # 单机模式未启动 Nacos 时，规则拉取失败仅打 WARN 日志，不阻断启动。
      datasource:
        gw-flow:
          nacos:
            server-addr: ${NACOS_ADDR:127.0.0.1:8848}
            ...
            data-id: aihub-gateway-flow-rules
            rule-type: gw-flow
            data-type: json
```

要点：① `data-id: aihub-gateway-flow-rules` 指向 Nacos 里的一个 JSON 配置文件，规则改动**即时生效**（Nacos 推送 → Sentinel 数据源监听刷新）；② `rule-type: gw-flow` 告诉 Sentinel 这批 JSON 按网关流控规则解析；③ 单机开发没起 Nacos 也不影响启动（只打 WARN）——配置注释原文交代得很清楚；④ 依赖是 `sentinel-datasource-nacos`（`aihub/aihub-gateway/pom.xml#42-46`），注释原文："限流规则存 Nacos，dashboard/控制台改规则不再'重启即丢'"。

### 5.2 真实规则样例

```json
// 来源：aihub/docs/nacos/aihub-gateway-flow-rules.json
[
  {
    "resource": "aihub-ai",
    "grade": 1,
    "count": 100,
    "intervalSec": 1,
    "burst": 20,
    "controlBehavior": 0,
    "maxQueueingTimeMs": 0
  },
  {
    "resource": "aihub-platform",
    "grade": 1,
    "count": 50,
    "intervalSec": 1,
    "burst": 10,
    "controlBehavior": 0,
    "maxQueueingTimeMs": 0
  }
]
```

逐字段解读：`resource` 对应路由 id（`aihub-ai`、`aihub-platform`——注意是路由 id 不是服务名，虽然本项目两者恰好同名）；`grade: 1` 表示按 QPS 限流（0 是按并发线程数）；`count` 是阈值——AI 服务每秒最多 100 个请求、平台服务每秒 50 个（AI 服务承载流式对话，流量特征更"重"却给了更高 QPS，因为它单请求耗时长、QPS 高峰值来自知识库批量操作，平台服务则是短平快的管理请求）；`intervalSec: 1` 统计窗口 1 秒；`burst: 20` 允许 1 秒内的突发余量；`controlBehavior: 0` 表示超阈值直接拒绝（另两种是匀速排队 1、冷启动 2）。

### 5.3 命中后返回什么

被限流的请求不会抛异常给用户，而是走配置好的兜底响应（`aihub/aihub-gateway/src/main/resources/application.yml#65-69`）：

```yaml
# 来源：aihub/aihub-gateway/src/main/resources/application.yml#65-69
      # 网关限流后的兜底响应
      scg:
        fallback:
          mode: response
          response-status: 429
          response-body: '{"code":10006,"message":"请求过于频繁，请稍后再试"}'
```

即 HTTP **429 Too Many Requests** + 统一响应结构 `code=10006`。与第 4.7 节 401 的 `code=10002` 一样遵守 common 的响应体约定，前端可以统一按 code 弹提示。`mode: response` 表示自定义响应体（另一种 mode 是 redirect）。

**常见误用**：① 在 Sentinel dashboard 上改了规则，以为持久化了——dashboard 默认内存态，重启即丢；本项目规则真身必须改 **Nacos 的 `aihub-gateway-flow-rules`**；② resource 名写错（写成服务名而路由 id 不同），规则静默不生效；③ 阈值拍脑袋定太低，正常用户被 429——先压测看基线。

**面试怎么说**："网关限流我们用 Sentinel 的 gw-flow 规则、按路由维度配 QPS 阈值；规则持久化在 Nacos 实现热更新；命中返回 429 加统一 code=10006，前端同一套错误码处理。"

---

## 6. 跨域处理

### 6.1 先讲原理：浏览器为什么发 OPTIONS

跨域是**浏览器的同源策略**：页面协议/域名/端口任一不同即"不同源"，页面里的 JS 发起的请求会被浏览器拦截。管理端跑在 8849、用户端 H5 跑在 5173、网关在 8080——只要前端直连网关，就是跨域请求。浏览器对"非简单请求"（比如带 `Authorization` 头、`Content-Type: application/json` 的 POST）会先发一个 **OPTIONS 预检请求**问服务器"允不允许"，服务器正确应答 CORS 头后，真正的请求才会发出。网关的配置注释原文正说了这件事（`application.yml#27-29`）："前端(5173)与网关(8080)不同源，浏览器会先发 OPTIONS 预检请求，不放行预检则所有跨域请求都会被浏览器拦下（登录页表现为'网络错误'）"。

### 6.2 网关统一 CORS 配置逐行

```yaml
# 来源：aihub/aihub-gateway/src/main/resources/application.yml#30-37
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowed-methods: "*"
            allowed-headers: "*"
            exposed-headers: "X-Trace-Id"
            maxAge: 3600
```

- `'[/**]'`：对所有路径生效的全局 CORS（不用每个路由单独配）。
- `allowedOriginPatterns: "*"`：允许任意来源。**为什么用 `allowedOriginPatterns` 而不是 `allowedOrigins: "*"`？**因为如果将来需要开启 `allowCredentials`（携带 Cookie/凭证），CORS 规范禁止"通配来源 + 凭证"组合，`allowedOriginPatterns` 会把请求的实际来源回显到 `Access-Control-Allow-Origin`，两者才能兼容。配置注释原文（#29）："allowedOriginPatterns 配合 allowedCredentials 使用；生产环境应收紧为具体域名。"
- `allowed-methods` / `allowed-headers: "*"`：方法与请求头全放行（含 OPTIONS 预检、含 `Authorization`）。
- `exposed-headers: "X-Trace-Id"`：浏览器默认只让 JS 读少数"安全"响应头，把 traceId 显式暴露出来，前端才能读到并展示给用户（配合第 4.9 节）。
- `maxAge: 3600`：预检结果缓存 1 小时，1 小时内同一请求不再重复发 OPTIONS。

### 6.3 两类前端的两种方案：代理 vs 直连 + CORS

本项目两类前端采用了**不同的跨域解法**，这个对比很有讲解价值：

| | 管理端 aihub-admin-ui（8849） | 用户端 aihub-app（H5 5173） |
|---|---|---|
| 方案 | **开发期 Vite 代理**：请求发给自己的 dev server，由服务器转发给网关 | **直连网关 + 服务端 CORS** |
| 配置位置 | `aihub-admin-ui/vite.config.ts#29-38`：`proxy: { "/auth": { target: "http://localhost:8080", changeOrigin: true }, "/api": {...} }` | `aihub-app/common/config.js#7`：`baseUrl: 'http://localhost:8080'` |
| 是否跨域 | 浏览器视角全程同源（都发往 8849），**根本不产生跨域** | 跨域发生，靠网关 globalcors 应答预检 |
| 为什么 | 开发期"免去 CORS 配置依赖"（vite.config.ts 注释原文），生产换 nginx 做同样转发 | H5 部署后没有 dev server，必须直连；小程序端不存在浏览器同源策略，但真机调试需要改局域网 IP（config.js#10） |

**为什么开发期偏爱代理**：① 前端代码里写相对路径 `/auth/login`，不用关心后端地址，联调/切换环境零改动；② 跨域问题在 dev server 层被"物理消除"，不依赖网关 CORS 配置正确与否，少一个故障变量；③ 生产环境用 nginx 反向代理即可平滑衔接同样的相对路径。而网关的 globalcors 是给"无法代理的直连方"（H5 生产部署、第三方系统浏览器场景）兜底的——两条路都通，才不会互相绑架。

**常见误用**：① `allowedOrigins: "*"` 与 `allowCredentials: true` 同配，浏览器直接报错"The CORS protocol does not allow..."——用 `allowedOriginPatterns` 解决；② 只配了 GET/POST 忘了 OPTIONS，预检永远失败，前端表现为"网络错误"但后端日志毫无动静（预检请求根本没到业务层）；③ 网关和下游服务**同时**配 CORS，响应头重复（`Access-Control-Allow-Origin` 出现两次），浏览器报错——CORS 应答者应该只有一跳，本项目只在网关配；④ 生产环境忘把 `allowedOriginPatterns: "*"` 收紧成具体域名，任意站点都能带着用户凭证调接口（CSRF 风险面）。

---

## 7. 一次请求的完整旅程（把本章串起来）

以"用户端 H5 查知识库列表"为例，走一遍网关全部机制：

```
① GET http://localhost:8080/api/ai/kb/list，Authorization: Bearer eyJhbGci...
② TraceIdGlobalFilter（order=MIN）：无 X-Trace-Id → 生成 t-8f3a...，
   写入转发头 + 响应头                                   TraceIdGlobalFilter#filter:35
③ AuthGlobalFilter（order=-100）：
   a. isPublicPath("/api/ai/kb/list")？否                AuthGlobalFilter#isPublicPath:170
   b. 有 X-API-Key 头吗？没有                            AuthGlobalFilter#filter:93-96
   c. resolveToken 取出 Bearer 令牌                      AuthGlobalFilter#resolveToken:175
   d. 验签解析 claims：tenantId=1, userId=2              AuthGlobalFilter#authenticateByJwt:138-145
   e. 剥离并覆盖 X-Tenant-Id / X-User-Id                 AuthGlobalFilter#authenticateByJwt:157-164
④ Sentinel gw-flow 检查：resource=aihub-ai，QPS≤100？通过
⑤ 路由匹配 Path=/api/ai/** → lb://aihub-ai-service → Nacos 查实例 → 转发 8082
⑥ 下游 TenantResolveInterceptor 读到 X-Tenant-Id=1，租户隔离查询生效
⑦ 响应回传：浏览器从响应头读到 X-Trace-Id（CORS exposed-headers 已放行）
```

失败路径对照：没带令牌 → 401 `{"code":10002,"message":"缺少认证令牌"}`；令牌被改 → 401 "令牌无效或已过期"；手动伪造 `X-Tenant-Id: 1002` → 头被剥离，无效；1 秒内第 101 个请求 → 429 `code=10006`。

---

## 8. 本章面试三问与参考回答

**Q1：你们把鉴权放在网关，网关挂了怎么办？被绕过了怎么办？（两面性考察）**

> 参考回答："集中式鉴权的收益是一次校验处处受益——下游零重复代码、密钥只配在网关、新服务自动被保护。代价是入口依赖和旁路风险。单点问题我们靠'网关无状态 + 多实例水平扩容'解决，JWT 本地验签不依赖任何外部存储，扩容零成本。旁路问题我们做了三层防御：网关强制剥离客户端伪造的 X-Tenant-Id 头再写入令牌解析值；下游拦截器只信任网关写入的头；内部接口 /internal/** 不注册进网关路由。生产上还应该加网络层隔离，让 8081/8082 只接受来自网关网段的流量。"

**Q2：路由配置里的 lb:// 是什么意思？路由是怎么匹配的？**

> 参考回答："lb:// 后面跟的是注册中心里的服务名，网关通过 Nacos 拉取该服务名下的健康实例列表，由 Spring Cloud LoadBalancer 选一个转发，所以服务扩容换端口网关零配置。路由匹配靠 predicates，我们用 Path 断言：/auth/** 和 /api/platform/** 走平台服务，/api/ai/** 走 AI 服务。我们刻意关闭了 discovery locator 自动路由（避免内部服务被按服务名猜出来访问），也不做 StripPrefix，让路径前缀在网关断言与下游 @RequestMapping 之间保持一致，排障时少一层心智转换。"

**Q3：JWT 在网关验过了，下游服务还信请求头里的租户 ID，怎么防止伪造头？String.valueOf 有什么坑？**

> 参考回答："三道防线：第一，网关在写入身份头之前先 remove 客户端携带的同名头——下游拿到的 X-Tenant-Id 一定来自验签后的令牌 claims，而不是请求方；第二，租户 ID 唯一来源是令牌，前端传参一律不认；第三，内部接口不对外暴露。至于坑：claims.get 返回 null 时 String.valueOf 会把它变成字符串 'null' 而不是 null 引用，如果只判 == null 就漏网了，下游会把 'null' 当租户 ID。我们的判断是 tenantId == null || \"null\".equals(tenantId) 双保险——这类字符串化的坑在动态语言里更隐蔽，Java 里也要防。"

---

## 附：本文引用源码路径清单

| 路径 | 说明 |
|------|------|
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/GatewayApplication.java` | 启动类 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java` | 鉴权全局过滤器 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/TraceIdGlobalFilter.java` | 链路追踪过滤器 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyVerifier.java` | API Key 远程校验 |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/apikey/ApiKeyCache.java` | API Key 缓存 |
| `aihub/aihub-gateway/src/main/resources/application.yml` | 网关全部配置 |
| `aihub/aihub-gateway/pom.xml` | 网关依赖 |
| `aihub/docs/nacos/aihub-gateway-flow-rules.json` | Sentinel 网关流控规则样例 |
| `aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantContext.java` | 租户请求头常量 |
| `aihub/aihub-common/src/main/java/com/aihub/common/trace/TraceContext.java` | 链路头常量 |
| `aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantResolveInterceptor.java` | 下游租户解析拦截器 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/controller/AuthController.java` | 登录接口（白名单路径的下游） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/internal/controller/InternalController.java` | 内部契约接口（不经网关暴露） |
| `aihub/aihub-ai-service/src/main/java/com/aihub/ai/web/KnowledgeController.java` | AI 服务知识库接口（路由对账用例） |
| `aihub-admin-ui/vite.config.ts` | 管理端开发期代理配置 |
| `aihub-app/common/config.js` | 用户端 baseUrl 直连配置 |
