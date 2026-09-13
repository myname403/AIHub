# 02 · 契约模块 aihub-api —— 学习文档

> 源码位置：`aihub/aihub-api`（共 3 个文件，不可运行，是"服务间调用的合同书"）
> 配套代码注释已逐行增强，建议对照源码阅读本文。

---

## 1. 这个模块是干什么的？

微服务之间要互相调用（AI 服务要找平台服务"扣配额"）。调用双方需要一份**一模一样的约定**：
调哪个服务、什么路径、传什么参数、返回什么结构。这份约定就是 `aihub-api`。

```
aihub-ai-service ──依赖──> aihub-api <──依赖── aihub-platform-service
     (调用方)                  (共享契约)           (提供方)
```

**为什么不直接在 AI 服务里写 URL 调用？** 三个理由：
1. **类型安全**：接口改了参数，两边编译立刻报错；手写 URL 只有运行时才发现错；
2. **一处定义两端共享**：DTO（如 `QuotaResult`）不会两边各写一份导致字段漂移；
3. **集中管控**：哪些服务间调用是合法的，看这个模块就知道（架构约束：只允许租户校验/配额扣减/审计上报三类）。

---

## 2. 前置知识：Feign 是什么？

**Feign（OpenFeign）是声明式 HTTP 客户端**——把"发 HTTP 请求"变成"调接口方法"：

```java
// ① 定义接口（本模块 PlatformClient 就是这样）
@FeignClient(name = "aihub-platform-service", path = "/internal")
public interface PlatformClient {
    @PostMapping("/quota/consume")
    R<QuotaResult> consume(@RequestBody QuotaConsumeRequest request);
}

// ② 使用方直接注入调用，感觉像调本地方法
@Autowired PlatformClient platformClient;
R<QuotaResult> r = platformClient.consume(req);   // 实际发了一次 HTTP！
```

背后 Feign 帮你做了 5 件事：

| 步骤 | Feign 做的事 | 依赖的组件 |
|---|---|---|
| 1 | 服务名 `aihub-platform-service` → 从注册中心找到实例 IP:端口 | Nacos 服务发现 |
| 2 | 多个实例时选一个 | 客户端负载均衡 |
| 3 | 执行 `RequestInterceptor`（塞租户头、traceId 头） | 本模块 FeignTenantConfig |
| 4 | 按 Spring MVC 注解拼 URL、序列化参数、发请求 | HTTP 客户端 |
| 5 | 响应 JSON → 反序列化为 `R<QuotaResult>` | Jackson |

**注解解释**：
- `@FeignClient(name="服务名", path="公共前缀")`：声明这是 Feign 客户端，服务名对应对方 `spring.application.name`（在 Nacos 注册的名字）；
- `@GetMapping / @PostMapping`：和写 Controller 一模一样，只是这里是"发起"请求而不是"接收"；
- `@RequestParam("x")`：拼到 URL 问号后面；
- `@RequestBody`：对象转 JSON 放进请求体（POST）。

---

## 3. 逐类精讲

### 3.1 PlatformClient —— 远程契约（`client/PlatformClient.java`）

5 个方法，对应平台服务 `InternalController` 暴露的 5 个内部接口：

| 方法 | 路径 | 谁调用 | 干什么 |
|---|---|---|---|
| `checkTenant` | GET /internal/tenants/check | AI 服务 | 校验租户是否有效 |
| `checkAndConsume` | POST /internal/quota/check | AI 服务 | 校验并扣配额（单维度） |
| `consume` | POST /internal/quota/consume | AI 服务 | 多维批量扣配额 |
| `reportUsage` | POST /internal/usage/report | AI 服务 | 上报 token 用量/审计 |
| `verifyApiKey` | POST /internal/apikey/verify | 网关 | 校验开放 API Key |

**两个重要设计点**：

1. **幂等（requestId）**：`checkAndConsume` / `consume` 带 `requestId`。网络抖动时 Feign 可能重试，同一个 requestId 到达两次，平台侧只扣一次 —— 否则用户一次对话扣两次配额。
2. **`ApiKeyVerifyResult` 只有租户和范围，没有密钥**：租户由 Key 反查得到，调用方**无法**通过传参指定"我是租户 1002"——从接口设计上杜绝越权。

**record 语法（Java 17）**：DTO 用 `record` 定义——

```java
record QuotaResult(boolean allowed, long remain, String reason) {}
```

一行顶过去 30 行：自动生成 final 字段、构造器、getter（`allowed()` 不是 `getAllowed()`）、equals/hashCode/toString。**不可变**，天然线程安全，是 DTO 的最佳选择。注意 record 反序列化靠 Jackson 的构造器绑定，字段名必须和 JSON 一致。

### 3.2 QuotaDimensions —— 配额维度常量（`client/QuotaDimensions.java`）

四个维度：`request`（次数）、`token`（token 数）、`doc`（文档入库数）、`task`（Agent 任务数）。

**核心思想**：跨服务共享的"字面量"必须只有一个定义处。AI 侧上报和平台侧扣减如果各写各的字符串，写错一个字母就静默失配（不报错但账不对）。放进契约模块，两端编译期就保证一致。

### 3.3 FeignTenantConfig —— 上下文透传（`config/FeignTenantConfig.java`）

**问题**：AI 服务收到了带租户的请求，它再调平台服务时，租户信息怎么"跟过去"？

**方案**：`RequestInterceptor`（Feign 扩展点，每次发请求前回调）从 ThreadLocal 读出 `TenantContext` / `TraceContext` 的值，塞进请求头：

```
AI 服务线程: TenantContext 里有 tenantId=1001
     ↓ Feign 发请求前，拦截器把 tenantId 写入 X-Tenant-Id 头
平台服务收到 → TenantResolveInterceptor 读头 → 写入自己的 TenantContext
```

traceId 用 `TraceContext.ensure()`（没有就现场生成），保证 AI→平台 的调用在日志里是同一条链路。

**注意事项**：
- Feign 配置类**不要标 `@Configuration`**（会被组件扫描进全局，影响所有 Feign 客户端的隔离性）；
- 如果 ThreadLocal 里没有租户（如定时任务），头就不会带，平台侧会直接拒绝（fail-fast）—— 这是**故意的**，逼你显式处理"无租户调用"。

---

## 4. 本模块注解词典

| 注解 | 作用 | 常见坑 |
|---|---|---|
| `@FeignClient(name, path)` | 声明 Feign 客户端；name 必须与 Nacos 中服务名一致 | name 写错 → 运行时才报"找不到服务"；path 是所有方法的 URL 前缀 |
| `@GetMapping/@PostMapping` | 声明远程接口的 HTTP 方法和子路径 | 与 Controller 的同名注解同源，参数规则一致 |
| `@RequestParam("x")` | 参数拼进 URL 查询串 | Feign 下 name 属性建议显式写（编译器不保留参数名时能救命） |
| `@RequestBody` | 参数对象整体转 JSON 放请求体 | 只能有一个；GET 请求不要用 |
| `@Bean` | 方法返回值注册为 Spring Bean | 本类特意不加 @Configuration，靠 Feign 显式引用生效 |

---

## 5. 完整链路演示：一次"扣配额"

```
1. 用户在 AI 服务发起对话
2. ChatAppService.checkQuota() 构造 QuotaConsumeRequest{requestId=uuid, items=[request:1, token:1500]}
3. 调 platformClient.consume(request)
4. FeignTenantConfig 拦截器：塞 X-Tenant-Id / X-User-Id / X-Trace-Id 头
5. Feign 从 Nacos 找到 aihub-platform-service 实例 → POST http://ip:8081/internal/quota/consume
6. 平台侧 TenantResolveInterceptor 验头 → InternalController → QuotaService 扣减 → R.ok(result)
7. AI 服务拿到 R<QuotaResult>，allowed=false 时抛 BizException(QUOTA_EXCEEDED)
```

---

## 6. 编码规范与注意事项

1. **契约优先**：新增服务间调用，先在 `aihub-api` 定义接口和 DTO，两端再实现/调用。
2. **DTO 用 record**：不可变、简洁；字段即 JSON 字段。
3. **共享常量进契约模块**：两端共用的字符串/枚举值只有一处定义。
4. **跨服务调用必须无事务**：不指望"跨服务的分布式事务"，用幂等 + 补偿（outbox）兜底。
5. **契约变更要两端同步升级**：改了字段名/类型，先改契约模块，两端一起编译验证。

---

## 7. 学习自测

1. Feign 调用 `platformClient.consume(req)` 时，HTTP 请求是怎么产生的？服务名 `aihub-platform-service` 怎么变成真实 IP？
2. 为什么 `checkAndConsume` 需要 `requestId`？没有它会出什么问题？
3. `FeignTenantConfig` 没标 `@Configuration`，它是怎么生效的？标了会怎样？
4. 为什么 DTO 用 `record` 而不是普通 class + Lombok？
5. `ApiKeyVerifyResult` 为什么不返回 Key 的 HMAC 哈希或任何密钥材料？

答完进入 `aihub-gateway`（网关）。
