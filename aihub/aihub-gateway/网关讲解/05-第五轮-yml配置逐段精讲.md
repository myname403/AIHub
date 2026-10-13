# 第五轮：application.yml 逐段精讲（从"会看"到"会配"）

> 目标：把网关的 [application.yml](../src/main/resources/application.yml) 113 行配置**每一段**讲透：
> 这个键是什么 → 谁在读它 → 改成别的值会怎样 → 我要加东西该怎么写。
> 学完你应该能独立干这三件事：**加一条路由、收紧 CORS、把配置挪到 Nacos 热更新**。

---

## 5.0 前置补课：yml 文件到底是怎么"生效"的？

很多初学者对 yml 的最大困惑：**这堆缩进文本凭什么能控制程序？** 三步说清：

### ① yml 语法本身（30 秒）

```yaml
spring:            # 缩进 2 格 = 父子关系
  cloud:
    gateway:
      routes:      # 下面带 - 的 = 数组（列表）
        - id: a    # 数组第 1 个元素
          uri: x
        - id: b    # 数组第 2 个元素
```

三条铁律：**缩进只能用空格不能用 Tab**；`key: value` 冒号后必须有空格；`-` 表示数组元素。

### ② 文本 → Java 对象：`@ConfigurationProperties`

Spring Boot 启动时把 yml 读进"环境"（Environment，本质是一个巨大的 key→value 表），然后：

- **框架配置**：`spring.cloud.gateway.*` 会被框架里的 `GatewayProperties` 类批量绑定（这个类就写着 `@ConfigurationProperties("spring.cloud.gateway")`，里面有 `List<RouteDefinition> routes` 等字段）——**所以你改 routes，框架就"变成"了对应的路由对象**；
- **本项目自定义配置**：`aihub.*` 没人自动绑，靠代码里逐个 `@Value` 读——回忆第三轮：`AuthGlobalFilter` 构造器里的 `@Value("${aihub.security.jwt-secret}")`、`ApiKeyVerifier` 里的 `@Value("${aihub.apikey.verify-timeout-ms:2000}")`。**yml 的键和 Java 的 @Value 占位符是一一对应的，看着配置能找到用它的人，反之亦然**。

### ③ `${ENV:默认值}` 占位符语法（全文出现 20+ 次）

```yaml
server-addr: ${NACOS_ADDR:127.0.0.1:8848}
```

读法：**优先取环境变量/启动参数 `NACOS_ADDR`，没有就用冒号后面的默认值 `127.0.0.1:8848`**。
这套写法让同一个 jar 包不改一行代码就能在 开发→测试→生产 之间漂移：本地吃默认值，生产用环境变量或 Nacos 覆盖。

### ④ 谁说了算？配置优先级（从低到高）

```
application.yml（打包在 jar 里）
  ↑ 被覆盖
application-{profile}.yml（如 standalone，激活 profile 时）
  ↑ 被覆盖
Nacos 配置中心（spring.config.import 拉下来的 aihub-common.yaml / aihub-gateway.yaml）
  ↑ 被覆盖
操作系统环境变量（AIHUB_JWT_SECRET=...）
  ↑ 被覆盖
命令行参数（--server.port=8080）
```

**排障金句**："我 yml 里明明改了，怎么不生效？"——九成是被上面某一层覆盖了。用 `/actuator/env`、`/actuator/configprops` 端点可以看每个键的最终值和来源。

---

## 5.1 逐段精讲开始

下面按文件顺序，把 113 行切成 9 段。

---

### 段 1｜server.port —— 大门牌号

```yaml
server:
  port: 8080
```

最直白的一段：Netty 监听哪个端口。前端所有请求、Nacos 上注册的实例地址都指向它。
**改它的后果**：前端代理目标（admin-ui 的 vite 代理指向 8080）要同步改，否则登录直接"网络错误"。

---

### 段 2｜spring.application.name —— 我的"身份证号"

```yaml
spring:
  application:
    name: aihub-gateway
```

这一个值同时干四件事，非常重要：
1. **注册到 Nacos 的服务名**（别人列表里看到的 `aihub-gateway`）；
2. 下游配 `lb://aihub-gateway` 时**靠它寻址**；
3. 拼 Nacos 配置文件的 data-id 前缀（见段 8）；
4. 指标/日志里的应用标签（`management.metrics.tags.application: ${spring.application.name}`）。

**记住：服务名就是微服务世界里的域名，改名字 = 换域名，所有引用你的地方都得跟着改。**

---

### 段 3｜web-application-type —— 声明"我是响应式"

```yaml
  main:
    web-application-type: reactive
```

强制 Spring Boot 按 **WebFlux（响应式）** 而不是 Servlet（MVC）启动。
网关 classpath 里同时有 `spring-cloud-starter-gateway`（依赖 webflux）和历史遗留可能混进来的 web 依赖时，这个声明防止容器类型二义性。
**改错实验**：把它改成 `servlet` 再启动，网关直接起不来或行为怪异——SCG 的过滤器模型（`ServerWebExchange`）只活在 reactive 世界。

---

### 段 4｜nacos —— 注册与配置中心的账号密码

```yaml
    nacos:
      username: ${NACOS_USERNAME:nacos}
      password: ${NACOS_PASSWORD:Nacos@aihub123}
      discovery:
        server-addr: ${NACOS_ADDR:127.0.0.1:8848}
        namespace: ${NACOS_NAMESPACE:public}
        group: ${NACOS_GROUP:DEFAULT_GROUP}
      config:
        server-addr: ...（同上）
        file-extension: yaml
```

三个概念一次讲清（这是 Nacos 新手最容易懵的）：

| 概念 | 比喻 | 本项目 |
|---|---|---|
| **namespace（命名空间）** | 大楼楼层：dev / test / prod 物理隔离 | 都在 `public`（单层小楼） |
| **group（分组）** | 楼层里的部门 | `DEFAULT_GROUP` |
| **data-id（配置名）** | 部门里的文件 | `aihub-gateway.yaml` 等（见段 8） |

- `discovery` 子树管**服务注册**（我在哪让别人找到）；`config` 子树管**配置拉取**（我去哪领配置）。生产上两者可以指向不同 Nacos；
- `username/password`：因为我们的 Nacos 3.x **开了鉴权**（docker-compose 里 `NACOS_AUTH_ENABLE=true`），不配就 403 拉不到配置——这正是之前记忆里"开启鉴权后必须先初始化管理员用户，否则客户端 403"的配套项；
- `file-extension: yaml`：拉配置时尝试的文件后缀。拼 data-id 的规则是 `${spring.application.name}.${file-extension}`，即 `aihub-gateway.yaml`。

**为什么密码敢写在 yml 里？** 默认值只是本地开发的钥匙；生产用 `NACOS_PASSWORD` 环境变量覆盖（`${}` 语法存在的意义），仓库里永远不出现真密码。

---

### 段 5｜gateway.discovery.locator —— 一个"故意关掉"的开关

```yaml
    gateway:
      discovery:
        locator:
          enabled: false
```

打开它（true），网关会**自动**给 Nacos 上每个注册服务生成一条路由：`/服务名/** → 该服务`。
听起来方便，为什么关？——**自动路由 = 把内部服务全部暴露到公网大门**。`/aihub-platform-service/actuator/env` 这种地址一旦可达，等于自己把后厨门对着大街敞开。
本项目原则：**暴露哪些路径是"审批制"（手写 routes 白名单），不是"自动制"**。这一行本身就是一条安全设计，面试讲到这里都是加分项。

---

### 段 6｜globalcors —— 跨域放行（前端"网络错误"的救星）

```yaml
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowed-methods: "*"
            allowed-headers: "*"
            exposed-headers: "X-Trace-Id"
            maxAge: 3600
```

先补背景：**跨域是浏览器行为**，不是服务器拒绝你——前端在 `localhost:8849`，网关在 `localhost:8080`，端口不同即不同源。浏览器会：
1. 对非简单请求**先发 `OPTIONS` 预检**："我等下要用 PUT + Authorization 头打你，你同意吗？"
2. 网关答复不同意（或压根没处理 OPTIONS）→ 浏览器**根本不发正式请求**，前端表现为"网络错误/请求失败"，但网关日志里干干净净——这就是"报错毫无指向性"的经典现场。

逐键讲：

| 键 | 含义 | 注意 |
|---|---|---|
| `'[/**]'` | 对哪些路径生效（这里全放行）。yml 里以 `/` 或含特殊字符的 key 要加引号 | 单引号不是装饰，去掉可能解析失败 |
| `allowedOriginPatterns: "*"` | 允许哪些来源页面。**带 credentials 的跨域不能用 `allowedOrigins:"*"`（会被 Spring 拒绝），必须用 Patterns 这个"可星号的变体"** | 生产收紧成 `https://admin.yourcorp.com` |
| `allowed-methods/headers: "*"` | 允许任意动词/任意请求头 | 生产列白名单 |
| `exposed-headers: "X-Trace-Id"` | **响应头默认浏览器 JS 只允许读 6 个标准头**；要让前端 F12/代码能读到 traceId，必须在这里"曝光"它 | 这行和 TraceIdGlobalFilter 是一对搭档 |
| `maxAge: 3600` | 预检结果浏览器缓存 1 小时，1 小时内同 URL 不再发 OPTIONS | 减少一半跨域开销 |

**动手验证**：04 文档实验 3 用 curl 直接 401（curl 不受跨域约束）；而浏览器里如果 CORS 配错，你连 401 都看不到，只看到"网络错误"——对比一次就永远记住了。

---

### 段 7｜routes —— 路由表（本文件最该学会"配"的一段）

```yaml
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

一条 route = **id（名字）+ uri（发到哪）+ predicates（什么样的请求算"我的"）+ filters（转之前/之后加工什么，本项目没写）**。

#### predicates（断言：匹配条件）
- `Path=/auth/**,/api/platform/**`：Ant 风格通配——`**` 匹配多层路径，`*` 匹配一层；逗号 = 或。
- 一个请求自上而下匹配所有 route，**命中即转发**；一条都不命中 → 404。
- 常用兄弟断言（本项目没用，但你要会写）：

```yaml
predicates:
  - Method=GET,POST          # 只收这俩动词
  - Header=X-Gray, v2        # 带这个头才算命中（灰度发布）
  - Query=version, 2\.0      # URL 参数匹配
  - After=2026-10-01T00:00:00+08:00[Asia/Shanghai]   # 定时上线
  - Weight=group1, 8         # 同 uri 两条 route 按权重分流（8:2 = 金丝雀发布）
```

#### uri 的 `lb://` 前缀
`lb` = load-balanced：不写死地址，运行时问 Nacos 要 `aihub-platform-service` 的**全部健康实例**，轮询挑一个。平台服务起两个实例（8081/8083），流量自动对半分——**这就是段 2 服务名的闭环**。
去掉 lb 也能直连：`uri: http://127.0.0.1:8081`（standalone 配置就这么干的，对照 [application-standalone.yml](../src/main/resources/application-standalone.yml)：关掉 nacos、uri 换死地址，其余一模一样——**它其实是路由表最好的"控制变量实验"教材**）。

#### filters（路由级加工器，本项目留空，教你加）
写在 route 下面，只对这条路由生效：

```yaml
- id: aihub-platform
  uri: lb://aihub-platform-service
  predicates:
    - Path=/api/platform/**
  filters:
    - AddRequestHeader=X-From-Gateway, true   # 转发前给请求加个头（下游可感知"来自网关"）
    - StripPrefix=1                            # 去掉 1 层前缀再转发：/api/platform/x → /platform/x
    - AddResponseHeader=X-Route, platform      # 响应加头
```

⚠️ 注意区分：这里是**路由级 GatewayFilter**（配在 yml，只影响本 route）；第二轮讲的 **GlobalFilter 是 Java 类**（影响所有 route）。

#### 完整练习：给 MCP SSE 服务加一条路由（真需求）

`aihub-mcp-sse` 跑在 8090，想让它也从网关走：

```yaml
        - id: aihub-mcp-sse
          uri: lb://aihub-mcp-sse          # 它注册在 Nacos 上的服务名
          predicates:
            - Path=/mcp/**
```

然后记得去 `AuthGlobalFilter.isPublicPath()` 决定这条路径要不要鉴权——**配置和代码是联动的，改 yml 前先想"谁会读它"**。

#### 验证路由是否生效（学完必做的实验）

段 9 会讲到，提前剧透：`GET /actuator/gateway/routes` 能把**当前生效的路由表**吐成 JSON——改完 yml 重启，先看这里，别看前端瞎猜。

---

### 段 8｜sentinel + config.import —— 限流与热更新

```yaml
    sentinel:
      transport:
        dashboard: ${SENTINEL_DASHBOARD:127.0.0.1:8858}
      datasource:
        gw-flow:
          nacos:
            server-addr: ${NACOS_ADDR:127.0.0.1:8848}
            data-id: aihub-gateway-flow-rules
            rule-type: gw-flow
            data-type: json
      scg:
        fallback:
          mode: response
          response-status: 429
          response-body: '{"code":10006,"message":"请求过于频繁，请稍后再试"}'
```

- `transport.dashboard`：启动后向 Sentinel 控制台**上报心跳**，你在 8858 页面能看到这个应用（注意：这是"控制台看得到"，规则却**不从控制台来**——见下一条）；
- `datasource.gw-flow`：真正的限流规则来源——**从 Nacos 拉** `aihub-gateway-flow-rules` 这份 JSON（样例在 `aihub/docs/nacos/aihub-gateway-flow-rules.json`）。为什么绕一圈？控制台改的规则默认存内存，**重启即丢**；以 Nacos 为"规则的持久化存储"，改完秒级推送生效、重启不丢。`rule-type: gw-flow` = 网关路由维度限流（对"哪条 route"限 QPS）；
- `scg.fallback`：被限流后返回什么。`429` + 统一 JSON——和网关手写的 401 一样，**格式对齐 R**，前端一套拦截器全接住。

```yaml
  config:
    import:
      - optional:nacos:aihub-common.yaml
      - optional:nacos:aihub-gateway.yaml
```

- 这就是段 4 说的"去 Nacos 领配置"的**领清单**：先领公共的（数据库、JWT 密钥等所有服务共享的），再领网关专属的（**同名键后者覆盖前者**）；
- `optional:` 前缀：拉不到也不报错、继续用本地默认值启动——**单机没起 Nacos 也能跑**的功臣。去掉 optional，Nacos 一挂网关直接起不来（生产上反而建议去掉，配置中心不可用就该报警而不是带病运行——又一个可以讲权衡的点）；
- **热更新**：Nacos 上改这两个文件 → 发布 → 网关里标了 `@RefreshScope` 的 Bean 会被重建、`@Value` 注入刷新。改个限流阈值、换个密钥，不用重启。

---

### 段 9｜aihub.* + management + logging —— 自定义键与观察窗

```yaml
aihub:
  security:
    jwt-secret: ${AIHUB_JWT_SECRET:aihub-default-dev-secret-please-change-me-32bytes}
  apikey:
    verify-url: ${AIHUB_PLATFORM_URL:http://aihub-platform-service}
    verify-timeout-ms: 2000
    cache-enabled: ${AIHUB_APIKEY_CACHE:true}
    cache-ttl-seconds: 60
    cache-max-size: 10000
```

`aihub.*` 不是任何框架的键，**纯自定义**，消费方全在第三轮讲过的代码里：

| 键 | 谁在读（@Value） | 配错的典型症状 |
|---|---|---|
| `jwt-secret` | AuthGlobalFilter | 和平台侧不一致 → **登录成功但一切接口 401**（签发和验签两把钥匙） |
| `verify-url` | ApiKeyVerifier | standalone 模式下还是服务名 → 解析失败 → 所有 Key 401 |
| `verify-timeout-ms` | ApiKeyVerifier | 配太大 → 平台慢时请求堆积；太小 → 误杀 |
| `cache-*` 三件 | ApiKeyCache | ttl 太大 → 吊销不生效 |

HS256 密钥有个硬规矩：**≥32 字节**，所以默认值特意凑了个长串——`Keys.hmacShaKeyFor()` 不够长直接抛异常（fail-fast，防弱密钥）。

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,gateway,prometheus
```

Actuator 观察窗，`include` 是白名单——**默认只开 health，其他全关**（安全）。这里额外开了三个：
- **`gateway`**：⭐ 本段最值钱——`GET /actuator/gateway/routes` 返回**当前生效的全部路由**（id、uri、断言、filters），改完 yml 第一时间来这核对；`GET /actuator/gateway/globalfilters` 能列出**全局过滤器和它们的 order 值**——第二轮讲的"链"在这里看得见摸得着！
- `prometheus`：给监控系统抓指标（docker-compose 里的 Prometheus 每 15 秒来拉一次）；
- 生产上这些端点要么不开、要么必须内网隔离——`/actuator/env` 会吐你所有配置。

```yaml
logging:
  pattern:
    console: "%d{...} %-5level [%X{traceId:--}] [%thread] %logger{36} - %msg%n"
```

`%X{traceId:--}`：从 **MDC**（ThreadLocal 的日志版）取 traceId 打出来，没有就显示 `--`。
这是 traceId 旅程的最后一站：网关过滤器写头 → 下游拦截器读头塞 MDC → 日志 pattern 从 MDC 取值——**三段接力，一个 ID 贯穿三个服务的日志**。

---

## 5.2 三个动手练习（做完才算这轮毕业）

**练习 1｜看见"链"本身**
```powershell
curl.exe -s http://127.0.0.1:8080/actuator/gateway/routes
curl.exe -s http://127.0.0.1:8080/actuator/gateway/globalfilters
```
在输出里找到：两条 route 的 predicate 原文；两个 GlobalFilter 及其 order 值（-2147483648 和 -100）。第二轮的"三约定"此刻变成了肉眼可见的 JSON。

**练习 2｜加一条路由并验证**
按 5.1 段 7 的示例给 `aihub-mcp-sse` 加路由 → 重启网关 → 再跑练习 1 第一条，确认新 route 出现在列表里 → 用 `curl.exe -i http://127.0.0.1:8080/mcp/sse` 观察行为（401？转发成功？——取决于你鉴权白名单怎么决定，这正是"配置与代码联动"的体感）。

**练习 3｜感受优先级**
不改任何文件，用环境变量启动网关：
```powershell
$env:SERVER_PORT='8099'; java -jar target\aihub-gateway-1.0.0-SNAPSHOT.jar
```
`server.port` 被环境变量覆盖，网关起在 8099——`${}` 与优先级链条，一次实验全验证。（完事记得 `$env:SERVER_PORT=''`。）

---

## 5.3 本段配置 ↔ 前四轮知识挂钩总表

| yml 段 | 挂到哪个知识点 |
|---|---|
| routes + lb:// | 第二轮 ⑤⑥ 站（路由匹配、NettyRoutingFilter） |
| globalcors | 第二轮 ② 站（CORS 预检在业务链之前） |
| jwt-secret | 第三轮 3.3.5（两服务共享一把钥匙） |
| apikey.* | 第三轮 3.4/3.5（Verifier 电话线 + Cache 小本本） |
| sentinel 规则从 Nacos 拉 | 与 routes 的 id 联动（gw-flow 按路由维度限流） |
| logging traceId | 第三轮 3.2（盖邮戳 → MDC 接力） |
| management.gateway 端点 | 第二轮整条链的"显影液" |
