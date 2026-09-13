# 01 · 公共模块 aihub-common —— 学习文档

> 源码位置：`aihub/aihub-common`（共 11 个类，无 main 方法，是"工具箱"不是"服务"）
> 配套代码注释已逐行增强，建议对照源码阅读本文。

---

## 1. 这个模块是干什么的？

`aihub-common` 是**所有微服务共享的公共库**（jar 包，不能独立运行）。
凡是"每个服务都要用"的东西，都放在这里，避免复制粘贴：

| 分类 | 类 | 一句话说明 |
|---|---|---|
| 统一响应 | `R`、`ResultCode` | 所有接口的返回值格式 |
| 异常体系 | `BizException`、`GlobalExceptionHandler` | 业务报错的抛出与统一转换 |
| 多租户 | `TenantContext`、`TenantResolveInterceptor` | "当前请求是哪个租户"的存取 |
| 链路追踪 | `TraceContext`、`TraceIdInterceptor` | 一次请求跨服务的唯一 ID |
| 安全 | `JwtVerifier` | JWT 令牌校验（网关验过，这里兜底） |
| 安全 | `ApiKeyCodec` | 开放 API Key 的生成与哈希 |
| 加密 | `AesGcmTextCipher` | 敏感信息（模型 Key）落库加密 |

**学习顺序建议**：先看 `R` + `ResultCode`（所有代码都在用它）→ `BizException` + `GlobalExceptionHandler`（错误怎么流到前端）→ `TenantContext` 系列（本项目最重要的安全机制）→ `TraceContext` 系列 → 三个安全/加密工具类。

---

## 2. 逐类精讲

### 2.1 R —— 统一响应体（`result/R.java`）

**解决的问题**：前端调用 N 个服务，如果返回格式五花八门，前端没法统一处理。所以约定所有接口都返回：

```json
{
  "code": 0,                // 0=成功，非 0=失败
  "message": "成功",
  "data": { ... },          // 业务数据
  "traceId": "9f3c..."      // 出错时拿它去查日志
}
```

**关键设计（面试也常考）**：

1. **构造器私有 + 静态工厂方法**。`R` 的构造器是 `private` 的，外面只能 `R.ok(data)` / `R.fail(code)` 创建实例。好处：所有 `R` 都经过同一个 `build()` 方法，traceId 一定被填充，不会漏。
2. **泛型 `<T>`**。`R<User>` 的 `data` 是 User、`R<List<KbDoc>>` 的 `data` 是列表。编译期类型检查，取数据不用强转。
3. **`@JsonInclude(NON_NULL)`**：`data` 为 null 时 JSON 里直接没有这个字段，前端 `res.data` 拿到 `undefined`，比 `null` 更好判断。
4. **`@Data`（Lombok）**：编译期自动生成 getter/setter/toString。注意 Lombok 是"编译期代码生成"，源码里看不到这些方法但 `.class` 文件里有。

**怎么在 Controller 里用**：

```java
@GetMapping("/user/{id}")
public R<SysUser> getUser(@PathVariable Long id) {
    return R.ok(userService.getById(id));   // 成功
    // 失败时不写 R.fail，直接 throw new BizException(...)，交给全局异常处理器
}
```

### 2.2 ResultCode —— 响应码枚举（`result/ResultCode.java`）

分段规则（**业务码 ≠ HTTP 状态码**，HTTP 永远 200，靠 body 里的 code 区分成败）：

| 段 | 含义 | 例子 |
|---|---|---|
| 0 | 成功 | SUCCESS |
| 1xxxx | 通用错误 | 10002 未认证、10005 跨租户、10007 配额用尽 |
| 2xxxx | AI 能力错误 | 20001 模型不可用、20005 Agent 超预算 |
| 5xxxx | 系统错误 | 50000 内部异常、50001 依赖服务不可用 |

**为什么用枚举**：类型安全（写不出不存在的码）、集中管理、可读性好。枚举天然单例、不可变，是表达"固定集合"的最佳方式。

### 2.3 BizException —— 业务异常（`exception/BizException.java`）

**核心认知**：Java 异常分两类——
- 受检异常（如 `IOException`）：必须 try-catch 或 throws，强迫每一层处理；
- 非受检异常（继承 `RuntimeException`）：可以不管，一路向上抛。

业务错误（密码错、配额尽）99% 的中间层都不关心"怎么处理"，只想"往上报"，所以 `BizException` 继承 `RuntimeException`，最终由全局异常处理器统一接住。

**标准用法**：

```java
// 三种构造方式
throw new BizException(ResultCode.UNAUTHORIZED);                          // 用默认文案
throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");        // 自定义文案（最常用）
throw new BizException(ResultCode.SYSTEM_ERROR, "加密失败", e);            // 保留底层异常堆栈
```

**红线**：message 会原样返回给前端，**绝不拼接密钥、完整 Prompt、SQL、堆栈**。

### 2.4 GlobalExceptionHandler —— 全局异常处理器（`exception/GlobalExceptionHandler.java`）

**两个注解是重点**：

- `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`。
  - `@ControllerAdvice`：把类注册成**所有 Controller 的全局切面**，不用在每个 Controller 里写 try-catch；
  - `@ResponseBody`：方法返回值自动转 JSON。
- `@ExceptionHandler(Xxx.class)`：声明"本方法处理 Xxx 类型异常"。多个 handler 同时能匹配时，Spring 选**类型最近**的那个（BizException → handleBiz；校验异常 → handleValidate；其他 → handleException 兜底）。

**数据流**：

```
Service 抛 BizException → Controller 没接 → 冒泡到 Spring MVC
→ GlobalExceptionHandler.handleBiz() → R.fail(code, message) → JSON 响应
```

**易错点**：
1. 本类只对"进入 Spring MVC 的请求"生效。网关是 WebFlux（响应式），过滤器里的异常**不走**这个类。
2. `handleException` 里 `log.error("未处理异常", e)` 把堆栈写日志但**不返回给前端**，防止泄露内部信息。

### 2.5 TenantContext + TenantResolveInterceptor —— 多租户（`tenant/`）

**多租户是什么**：一套系统服务多家公司（租户），所有表都带 `tenant_id` 列，任何查询必须限定租户，防止 A 公司看到 B 公司数据。

**ThreadLocal 原理（必须搞懂）**：

```
一个 HTTP 请求 = 一个工作线程处理
ThreadLocal = 每个线程的"私有储物柜"

请求进入 → 拦截器 preHandle: TenantContext.set(tenantId, userId)
   ↓
Controller → Service → Mapper 任意深度，随手 getTenantId() 就能取到（不用层层传参！）
   ↓
请求结束 → afterCompletion: TenantContext.clear()   ← 忘了这步=经典事故
```

**为什么必须 clear**：Tomcat 用线程池，线程是复用的。不清的话，下一个请求复用这条线程，会读到**上一个租户的 ID** —— 数据串台、越权，比报错严重得多。

**信任链设计（本项目最重要的安全设计）**：

```
前端带 JWT → 网关 AuthGlobalFilter 验签 → 解析出 tenantId
→ 写入请求头 X-Tenant-Id → （同时剥离客户端伪造的同名头！）
→ 下游服务 TenantResolveInterceptor 读这个头 → 写入 TenantContext
```

下游敢信任 `X-Tenant-Id` 头，是因为网关**保证**这个头只能由它写入（客户端伪造的会被剥离）。这叫"边界统一鉴权"。**任何时候都不要写接受前端传 tenantId 的代码**。

**`requireTenantId()` vs `getTenantId()`**：前者缺失直接抛异常（fail-fast），用于"没有租户就没法干活"的写操作；后者允许 null，用于可选场景。

**注册方式**（在各服务的 `config/WebMvcConfig` 里）：

```java
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new TraceIdInterceptor()).addPathPatterns("/**");
        registry.addInterceptor(new TenantResolveInterceptor()).addPathPatterns("/**");
    }
}
```

### 2.6 TraceContext + TraceIdInterceptor —— 链路追踪（`trace/`）

**解决的问题**：请求经过 网关→平台→AI 三个服务，日志分散在三台"地方"。给每个请求发一个全局唯一 traceId，所有服务的日志都带它，排查时一把梭。

**双重存储**：
- `ThreadLocal HOLDER`：给业务代码 `TraceContext.get()` 用，`R.traceId` 响应字段自动填充；
- `SLF4J MDC`：给日志框架用。MDC 是 logback/log4j 内置的线程级上下文，在日志 pattern 加 `%X{traceId}`，每行日志自动输出，业务代码零改动。

**`wrap()` —— 异步场景保命符**：ThreadLocal 不跨线程！提交线程池任务时必须包一层：

```java
executor.submit(TraceContext.wrap(() -> asyncWork()));   // ✅ 异步线程里 traceId 也在
executor.submit(() -> asyncWork());                       // ❌ 异步线程里 traceId 是 null
```

原理：提交时把当前 traceId"拍照"存进闭包，任务开始时 set 进新线程，结束时还原旧值（不污染线程池里复用的线程）。

### 2.7 JwtVerifier —— JWT 校验（`security/JwtVerifier.java`）

**JWT 三段结构**：`Header.Payload.Signature`（base64url 编码，点分隔）。
- Payload 就是 Claims：`tenantId`、`userId`、`username`、过期时间 `exp`；
- Signature 用服务端密钥（HS256）对前两段签名，改一个字符验签就失败。

**为什么网关验过了这里还验？** 纵深防御：WebSocket 握手直连 AI 服务、绕过了网关过滤器，这种入口必须自己再验。**永远不要假设"上游一定验过了"**。

**API 设计**：无效/过期不抛异常，返回**空 Map**。调用方 `isEmpty()` 判断即可，避免每个调用点写 try-catch。

### 2.8 ApiKeyCodec —— 开放 API Key（`security/ApiKeyCodec.java`）

用户在控制台生成 `ak_xxx...`，程序调用时放请求头里，服务端校验。三个关键决策：

1. **库里只存 HMAC 哈希，不存明文**——明文只在签发时返回一次，丢了只能重置（和 GitHub Token 一个思路）。
2. **为什么 HMAC-SHA256 不用 bcrypt**：bcrypt 刻意慢（抗弱口令暴力破解），但鉴权在每个请求上都发生，慢哈希会拖垮吞吐。API Key 是 192 bit 随机数不是弱口令，用带服务端密钥的 HMAC 即可——就算库被拖走，没有 HMAC 密钥也伪造不出可用 Key。
3. **`MessageDigest.isEqual` 防时序攻击**：`String.equals()` 第一个不同字符就返回 false，攻击者测比较耗时能逐字节猜哈希；`isEqual` 恒定耗时，堵死侧信道。

### 2.9 AesGcmTextCipher —— AES-GCM 加密（`crypto/AesGcmTextCipher.java`）

用户的模型 API Key（如 DeepSeek 的 key）不能明文入库，必须加密。要点：

- **AES-GCM 是认证加密**：解密时密文被篡改过一位直接失败，自带完整性校验；
- **随机 IV**：每次加密用新的 12 字节随机 IV，同一明文每次密文都不同；密文格式 `base64(IV + 密文)`，IV 明文拼在前面，解密时切出来用；
- **密钥派生**：用户配的口令长度不定，SHA-256 哈希一次整形为固定 32 字节（AES-256 要求）；
- **密钥来源**：配置 `aihub.security.data-key`（环境变量/Nacos），不落库不打日志。**换了 key 旧密文就解不开了**（报错信息里已提示）。

---

## 3. 本模块注解词典

| 注解 | 出现位置 | 作用 | 常见坑 |
|---|---|---|---|
| `@Data` | R | Lombok 生成全部 getter/setter/toString/equals/hashCode | 加了 @Data 又手写 getter 会重复；继承场景慎用 equals |
| `@Getter` | ResultCode、BizException | 只生成 getter（字段 final 不可变，不需要 setter） | — |
| `@Slf4j` | GlobalExceptionHandler | 生成 `log` 字段 | 类上才能用 |
| `@JsonInclude(NON_NULL)` | R | Jackson 转 JSON 时跳过 null 字段 | 是类级配置，影响所有字段 |
| `@RestControllerAdvice` | GlobalExceptionHandler | 全局异常处理 + 返回值自动转 JSON | 只管 Spring MVC，网关 WebFlux 不归它管 |
| `@ExceptionHandler` | 三个 handler 方法 | 声明处理的异常类型，就近匹配 | 返回值也会被转成 JSON |
| `@Override` | 拦截器方法 | 编译期检查"确实覆盖了父类方法"，写错方法名会报错 | 不是必须但强烈建议 |
| `implements Serializable` | R | 可序列化标记（缓存/RPC 场景） | 建议显式声明 `serialVersionUID` |

---

## 4. 编码规范（从本模块学到的）

1. **工具类三件套**：`final class` + 私有构造器 + 全静态方法（`AesGcmTextCipher`、`ApiKeyCodec`、`JwtVerifier`、`TenantContext`、`TraceContext` 全是这个模式）。
2. **构造器私有 + 静态工厂**：需要控制"怎么创建对象"时用（`R` 的 ok/fail）。
3. **fail-fast**：租户头缺失立即拒绝，不带病运行。
4. **枚举代替魔法数字**：状态码、错误码全部收进枚举。
5. **finally/afterCompletion 里清理 ThreadLocal**：用了必须还。
6. **安全红线**：租户 ID 不接受前端传参；异常信息不含敏感内容；密钥不落库不打日志。

---

## 5. 常见坑速查

| 坑 | 后果 | 正确做法 |
|---|---|---|
| ThreadLocal 用完不 clear | 线程复用导致租户/traceId 串台 | 拦截器 afterCompletion 必清 |
| 异步任务直接 submit lambda | 新线程里上下文全丢 | 用 `TraceContext.wrap()` 包一层 |
| Controller 里 try-catch BizException 再吞掉 | 全局处理器收不到，前端拿到 500 | 让它抛，交给全局处理器 |
| 业务码当 HTTP 状态码判断 | 前端永远走不进成功分支 | HTTP 看 200，业务看 body.code |
| 在代码里 new R<>(...) | 编译不过（私有构造器） | 用 R.ok / R.fail |
| 换了 aihub.security.data-key | 旧密文解不开 | 换 key 前先评估存量密文 |

---

## 6. 学习自测

1. 为什么 `R` 的构造器是 private 的？直接 public 会怎样？
2. `BizException` 为什么继承 `RuntimeException` 而不是 `Exception`？
3. 一个请求从网关进来，`X-Tenant-Id` 头经历了什么？为什么下游服务敢信任它？
4. `TenantContext` 忘了 `clear()` 会发生什么？在哪个时机清理？
5. 为什么异步任务要用 `TraceContext.wrap()` 包装？不加会怎样？
6. API Key 为什么用 HMAC-SHA256 存哈希，而用户登录密码却用 bcrypt？（提示：思考"谁生成的、能不能暴力猜"）
7. AES-GCM 比 AES-CBC 多了什么能力？（提示：篡改密文试试）

能全部答上来，就可以进入下一个模块 `aihub-api` 了。
