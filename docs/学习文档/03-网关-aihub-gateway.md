# 03 · 网关 aihub-gateway —— 学习文档

> 源码位置：`aihub/aihub-gateway`（端口 8080，5 个类 + application.yml）
> 配套代码注释已逐行增强，建议对照源码阅读本文。

---

## 1. 网关是什么？为什么必须有它？

**没有网关的世界**：前端要记住平台服务 8081、AI 服务 8082……每个服务各做一套鉴权、限流、日志。

**有网关的世界**：

```
                       ┌────────────── 网关 aihub-gateway (8080) ──────────────┐
 前端/第三方 ──HTTP──→ │ 1. TraceIdGlobalFilter：发 TraceId                     │
                       │ 2. AuthGlobalFilter：验 JWT / API Key → 写租户头        │
                       │ 3. Sentinel：限流（gw-flow 规则，存 Nacos）              │
                       │ 4. 按 URL 路由：转发到对应微服务                         │
                       └───────┬──────────────────────────┬────────────────────┘
                        /auth/**,/api/platform/**         /api/ai/**
                               ↓                          ↓
                    aihub-platform-service(8081)   aihub-ai-service(8082)
```

网关是系统的"大门保安 + 前台分诊"：**鉴权只做一次**（下游信任网关写的头）、**限流收口**、**路由解耦**（前端只认 8080）。

---

## 2. 前置知识：WebFlux 响应式编程（网关的底座，必须先懂）

网关用的是 **Spring WebFlux**（不是 Spring MVC），因为它要同时扛海量并发连接：

| | Spring MVC（下游服务用） | Spring WebFlux（网关用） |
|---|---|---|
| 模型 | 一个请求占一个线程（阻塞） | 少量线程 + 事件回调（非阻塞） |
| 万级并发 | 线程暴涨、内存吃紧 | 线程数不变 |
| 返回类型 | 直接返回对象 | `Mono<T>`（0/1个结果）、`Flux<T>`（0-N个结果） |
| 代码风格 | 顺序执行 | 链式调用 |

**Mono 怎么读**：把它想成"一张将来才兑现的提货单"。
- `Mono.just(x)` —— 提货单上已经有 x；
- `webClient.post()...bodyToMono(X.class)` —— 单先开着，货（响应）到了再兑现；
- `.map(v -> ...)` —— 货到了之后加工；
- `.flatMap(v -> anotherMono)` —— 货到了之后再异步办下一件事；
- `Mono.empty()` —— 空提货单（"没有结果"）；
- `.switchIfEmpty(mono)` —— 空单时改用另一张单。

**最大坑**：**绝不能在 WebFlux 代码里写阻塞调用**（`Thread.sleep`、同步 JDBC、普通 Feign）——会卡死整个网关共享的事件循环线程，所有请求一起卡。

---

## 3. 逐类精讲

### 3.1 GatewayApplication（`GatewayApplication.java`）

标准启动类。两个注解：
- `@SpringBootApplication`：三合一（配置类声明 + 自动装配 + 组件扫描）。**启动类必须放在包结构根部**（`com.aihub.gateway`），否则子包组件扫不到——初学者最经典的启动失败原因；
- `@EnableDiscoveryClient`：注册到 Nacos。Spring Cloud 2020+ 可以省略，显式写更清晰。

### 3.2 过滤器链与执行顺序（核心中的核心）

两个过滤器都实现了 `GlobalFilter`（全局过滤器：所有路由的请求都执行）和 `Ordered`（排序）。**getOrder 越小越先执行**：

```
请求进入
  ↓ ① TraceIdGlobalFilter（HIGHEST_PRECEDENCE = Integer.MIN_VALUE，最早）
  │    确定 traceId → 写入下游请求头 + 响应头
  ↓ ② AuthGlobalFilter（order = -100）
  │    公开路径放行 / API Key 通道 / JWT 通道 → 写 X-Tenant-Id、X-User-Id
  ↓ ③ Sentinel 网关流控（框架内置）
  ↓ ④ 路由转发（RouteToRequestUrlFilter 等框架内置）
下游服务收到：干净的、带可信身份头的请求
```

**chain.filter(exchange) 的含义**：把请求交给下一个过滤器；不调用而直接 `writeWith(...)` 就是"拦截，自己响应"。

### 3.3 AuthGlobalFilter —— 鉴权（★ 全系统安全核心）

**主流程**：

```
filter(exchange, chain)
├── 公开路径（/auth/**、/actuator/health）→ 直接放行
├── 有 X-API-Key 头 → API Key 通道
│     apiKeyCache.verify(key)          ← 本地缓存，未命中远程调平台
│     ├── 通过 → 剥离伪造头 → 只写 X-Tenant-Id（API Key 无用户身份）→ 放行
│     └── 失败 → 401 {"code":10002,...}
└── 无 API Key → JWT 通道
      ├── Authorization: Bearer xxx？→ 验签解析 claims
      │     ├── 失败/过期 → 401
      │     ├── 缺 tenantId → 401
      │     └── 成功 → 剥离伪造头 → 写 X-Tenant-Id + X-User-Id → 放行
      └── 没有令牌 → 401 "缺少认证令牌"
```

**三个必须理解的设计**：

1. **为什么先 remove 再 set 租户头**（"强制剥离伪造头"）：下游 `TenantResolveInterceptor` 信任 `X-Tenant-Id`。如果不剥离，攻击者自己带上 `X-Tenant-Id: 1002` 就冒充别家租户。这是本项目**最高危的安全点**，改动这段必须做安全评审。
2. **两种凭证的分工**：JWT 带用户身份（tenantId+userId），API Key 只代表"某个租户的系统调用"（只有 tenantId）。所以 API Key 通道显式清空用户头。
3. **401 是手写的**：网关是 WebFlux，common 的 `GlobalExceptionHandler`（Servlet 体系）管不到它，所以自己拼 JSON 响应体（格式与 R 对齐，还带 traceId）。

**响应式 API 对照**（本类里出现的）：
- `apiKeyCache.verify(...)` 返回 `Mono<VerifiedKey>`；
- `.flatMap(principal -> ...)`：校验通过后继续异步处理；
- `.switchIfEmpty(Mono.defer(...))`：empty（校验失败）时切换到"拒绝响应"；
- `exchange.getRequest().mutate()...build()`：请求对象不可变，mutate 造修改后的副本。

### 3.4 ApiKeyVerifier —— 远程校验 Key（`apikey/ApiKeyVerifier.java`）

用 **WebClient**（非阻塞 HTTP 客户端）调平台服务 `POST /internal/apikey/verify`。
**为什么不用 Feign**：Feign 是阻塞模型，与 WebFlux 事件循环不兼容。

关键点：
- **传明文不传哈希**：算哈希需要 HMAC 密钥，把密钥铺到网关=多一个泄露面。明文只在内网传输，平台侧负责比对；
- **超时 2 秒**：鉴权在主链路，不能让平台故障拖死网关；
- **fail-closed 原则**：`onErrorResume` 里平台不可用 → 返回 empty（= 拒绝）。鉴权组件"出错时宁可拒绝，绝不放行"。

### 3.5 ApiKeyCache —— 校验结果本地缓存（`apikey/ApiKeyCache.java`）

**Caffeine**（Spring 官方推荐的本地缓存库）的 `AsyncCache`：
- TTL 60 秒：延迟与"吊销及时性"的平衡（Key 禁用后最多再放行 60 秒）；
- 上限 1 万条：防内存膨胀，超出按 LRU 淘汰；
- **异步加载防击穿**：并发未命中时只有一个请求真正回源，其余等同一个 Future；
- **失败结果的处理取舍**：校验"未通过"（Optional.empty）会进缓存 60 秒；好处是省调用，风险是平台故障期可能放大误拒，应急用 `invalidateAll()`；
- 缓存键不用 Key 明文（堆 dump 会泄露），用 hashCode+长度派生（非密码学用途，仅 Map 寻址）。

---

## 4. application.yml 逐段讲解（网关行为都在这）

```yaml
server.port: 8080                          # 对外端口，前端所有请求打这里
spring.application.name: aihub-gateway     # 注册到 Nacos 的服务名（Feign/负载均衡靠它）
spring.main.web-application-type: reactive # 显式声明响应式应用（用 WebFlux 而不是 MVC）

spring.cloud.nacos.discovery.*             # 服务注册发现：把网关自己注册进 Nacos
spring.cloud.nacos.config.*                # 配置中心：从 Nacos 拉配置

spring.cloud.gateway.routes                # ★ 路由表
  - id: aihub-platform                     #   路由 ID（唯一标识）
    uri: lb://aihub-platform-service       #   目标：lb:// = 从 Nacos 按服务名负载均衡
    predicates: Path=/auth/**,/api/platform/**   #   匹配规则：这些前缀走平台服务
  - id: aihub-ai
    uri: lb://aihub-ai-service
    predicates: Path=/api/ai/**

spring.cloud.sentinel.*                    # ★ Sentinel 限流
  transport.dashboard                      #   控制台地址（可视化看限流情况）
  datasource.gw-flow.nacos                 #   限流规则持久化到 Nacos（改规则不重启）
  scg.fallback                             #   被限流的兜底响应：HTTP 429 + code 10006

spring.config.import: optional:nacos:...   # 从 Nacos 拉两个配置文件（optional=拉不到也能启动）

aihub.security.jwt-secret                  # JWT 验签密钥：生产放环境变量/Nacos，默认值仅限开发
aihub.apikey.*                             # API Key 校验的地址/超时/缓存参数

management.*                               # Actuator：/actuator/prometheus 供 Prometheus 抓指标
logging.pattern.console                    # ★ %X{traceId:--}：每行日志自动带 traceId（MDC）
```

**`${VAR:default}` 语法**：环境变量优先，没有就用冒号后的默认值 —— 12-factor 应用的标准做法。

---

## 5. 完整链路演示：一个对话请求

```
1. 前端 POST http://localhost:8080/api/ai/chat
   Headers: Authorization: Bearer eyJhbGci...
2. 网关 TraceIdGlobalFilter：生成 traceId=9f3c...，写请求头+响应头
3. 网关 AuthGlobalFilter：
   - /api/ai/** 非公开路径 → JWT 通道
   - 验签通过 → claims 里 tenantId=1001, userId=7
   - 剥离伪造头 → 写 X-Tenant-Id: 1001, X-User-Id: 7
4. Sentinel 检查 /api/ai 路由的流控规则（超了返回 429）
5. 路由匹配 /api/ai/** → lb://aihub-ai-service → Nacos 查实例 → 转发 8082
6. AI 服务 TraceIdInterceptor（沿用 9f3c...）→ TenantResolveInterceptor（读头写上下文）
7. 业务执行 → 响应沿原路返回，前端在响应头 X-Trace-Id 里能看到链路 ID
```

---

## 6. 本模块注解词典

| 注解/接口 | 作用 | 常见坑 |
|---|---|---|
| `@SpringBootApplication` | 三合一启动注解 | 启动类必须在包结构根 |
| `@EnableDiscoveryClient` | 启用 Nacos 注册发现 | 需要 nacos 依赖 + server-addr 配置 |
| `@Component` | 注册为 Spring Bean | GlobalFilter 类型 Bean 会被网关自动收集 |
| `implements GlobalFilter` | 全局过滤器：所有路由都执行 | 只有实现了 Ordered 才能控制顺序 |
| `implements Ordered` | getOrder() 决定过滤器顺序 | 数字越小越先；框架内置过滤器也占号 |
| `@Value("${key:default}")` | 从配置注入值 | key 拼错启动不报错（注入 null 或默认值），注意检查 |
| `@Slf4j` | 生成 log 字段 | — |

---

## 7. 编码规范与注意事项

1. **网关零业务逻辑**：只做鉴权/限流/路由/追踪，出现业务代码就是设计腐化；
2. **鉴权 fail-closed**：不确定时拒绝，绝不放行；
3. **WebFlux 禁阻塞**：网关代码里禁止同步 IO；用 WebClient 不用 RestTemplate/Feign；
4. **公开路径白名单最小化**：`/auth/**` 和健康检查之外，默认全部要求鉴权；
5. **配置三环境**：密钥等敏感配置用 `${ENV:default}`，生产从 Nacos/环境变量注入；
6. **新增路由步骤**：application.yml 加 route → 下游服务确认注册了 TraceId/Tenant 拦截器 → 联调。

---

## 8. 常见坑速查

| 坑 | 现象 | 原因/解法 |
|---|---|---|
| 启动类位置不对 | 组件扫不到，Filter 不生效 | 启动类放包结构根 |
| 网关里写阻塞调用 | 偶发全体请求卡死 | 换 WebClient/Mono 链 |
| 401 但不知道哪错了 | 前端只有 code 10002 | 看响应体 traceId → 网关日志 |
| 限流规则改了没生效 | 还是旧阈值 | 规则在 Nacos（aihub-gateway-flow-rules），不是 dashboard 单改 |
| 忘了 lb:// 前缀 | uri 当成静态地址直连 | lb:// = 从 Nacos 负载均衡 |

---

## 9. 学习自测

1. 为什么网关用 WebFlux，而下游服务用 Spring MVC？
2. 画出请求经过的过滤器顺序，并说明为什么 TraceId 过滤器必须最先。
3. 如果删掉"先 remove 再 set 租户头"的代码，攻击者能做什么？（提示：伪造 X-Tenant-Id）
4. API Key 通道为什么不写 X-User-Id？
5. `lb://aihub-ai-service` 里的 lb 是什么意思？服务名从哪来？
6. 为什么 ApiKeyCache 不缓存"校验失败"之外的异常结果？吊销一个 Key 后最长多久生效？
7. Sentinel 限流规则存在哪？为什么不用 dashboard 直接改？

答完进入最大的模块 `aihub-platform-service`。
