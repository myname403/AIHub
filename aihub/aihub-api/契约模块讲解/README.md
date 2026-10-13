# aihub-api 契约模块讲解（单文件版）

> 配套源码：`aihub/aihub-api/src/main/`（仅 3 个类，但地位特殊）
> 定位：**服务之间的"合同"**——AI 服务要远程调用平台服务（扣配额、查用量），
> 调什么接口、参数长什么样、头上带什么，全部钉死在这个模块里，两边都依赖它。

---

## 1. 为什么需要"契约模块"？

没有它的世界：AI 服务里手写 `restTemplate.postForObject("http://aihub-platform-service/internal/quota/check", 手拼Map...)`。
平台服务哪天改了路径、改了字段名——AI 服务**运行时才炸**，编译器帮不了你。

有了它：平台暴露什么，先写成接口放进 aihub-api；AI 服务调接口，**编译期就锁死了路径/参数/返回类型**。
这就是"面向契约编程"：两个服务之间不共享实现、不共享数据库，**只共享一份薄薄的接口定义**。

```
        aihub-api（契约：3 个类，无任何业务实现）
        ▲                    ▲
   ai-service             platform-service
   （Feign 调用的甲方）      （提供 /internal 接口的乙方）
```

---

## 2. 三个类逐个讲

### ① PlatformClient.java —— 声明式 HTTP 客户端（Feign）

**干什么**：一个 interface + `@FeignClient` 注解，声明"平台服务的这几个内部接口我要调"。

```java
@FeignClient(name = "aihub-platform-service")   // 按服务名找（Nacos 解析+负载均衡）
public interface PlatformClient {
    @PostMapping("/internal/quota/check-and-consume")
    R<QuotaResult> checkAndConsume(@RequestBody QuotaConsumeRequest request);
    // ...
}
```

**关键认知——Feign 是什么**：你只写接口，运行时框架**自动生成实现类**，把"方法调用"翻译成
"服务发现 → 负载均衡 → 拼 HTTP → 发请求 → 解析 JSON → 返回对象"。调远程像调本地方法。

**为什么不用 WebClient/RestTemplate 手写**：那些是"命令式"，每个调用点都要处理 URL、序列化、异常；
Feign 把这些收敛到一处声明。**但注意网关为什么反而用 WebClient**——网关是 WebFlux 响应式世界，
Feign 是阻塞模型，两者不兼容；AI/平台都是 MVC，Feign 舒服。**工具选择跟着线程模型走，不是谁更时髦**。

### ② QuotaDimensions.java —— 跨服务口径常量

**干什么**：配额维度的字符串常量（`request` / `token` / `document`...），AI 上报和平台扣减**引用同一份定义**。

**为什么单独存在**：一边写 `"tokens"` 一边写 `"token"`，编译器不报错、账目静默对不上——
这种"字符串漂移"是分布式系统经典暗雷。收进常量类放公共契约模块，**从源头杜绝**。

**类设计细节**：`final class` + 私有构造器（不让 new 不让继承）。
"用接口定义常量"是过时写法（接口会被 implements 污染子类命名空间）。

### ③ FeignTenantConfig.java —— 自动塞头的拦截器

**干什么**：Feign 的 `RequestInterceptor`，**每次** Feign 发请求前，自动把
`X-Tenant-Id` / `X-User-Id` / `X-Trace-Id` 从当前 ThreadLocal 抄进请求头。

**解决什么**：平台服务的 TenantResolveInterceptor 规定"没有 X-Tenant-Id 头就拒绝"。
如果每个调用点手动塞头，**迟早有地方忘**。放拦截器 = 一处配置、所有 Feign 调用自动携带。

**这是租户隔离第五环**：网关 → 服务内 ThreadLocal → **跨服务 Feign 透传** → 下游服务再入 ThreadLocal。
（回忆：ThreadLocal 不会自动跨进程/跨线程，全靠这种显式接力。）

**一个易踩的坑（注释特意强调）**：Feign 配置类**不要标 `@Configuration`**——
标了会被组件扫描全局加载，变成"所有 Feign 客户端共用"，想给某个客户端单独配置时互相打架。

---

## 3. 记忆锚点

| 类 | 一句话 | 类比 |
|---|---|---|
| PlatformClient | 远程接口的"电话簿" | 合同里的服务条款 |
| QuotaDimensions | 跨服务字符串口径 | 合同里的计量单位（都写"市斤"就不会扯皮） |
| FeignTenantConfig | 出站请求自动盖章 | 寄快递自动贴面单 |

口诀：**"一个电话簿、一本单位表、一个贴单员"**。

---

## 4. 动手实验 + 自测

**实验｜看 Feign 调用的真实面孔**
1. 管理端登录 → AI 服务发一次对话（用户端 chat 页面问一句话）；
2. 在 ai-service 控制台日志搜 `checkAndConsume` 或 `/internal/quota`（Feign 默认不打，
   可临时把 `logging.level.com.aihub.api: DEBUG` 加到 ai-service 配置看请求原文）；
3. 观察：出站请求头里有没有 `X-Tenant-Id`？——有，就是 FeignTenantConfig 干的。

**自测四问**
1. 平台服务把 `/internal/quota/check-and-consume` 路径改了，AI 服务何时会炸？（改契约 PlatformClient 后编译 AI 服务时——如果两边不都改，Feign 运行期 404；这正是"契约集中"的价值：只改一处接口）
2. 为什么 ai 调 platform 不走网关 8080？（内网直连服务名即可；走网关反而绕路且网关只放行 /auth、/api、/actuator/health）
3. QuotaDimensions 为什么不放 aihub-common？（common 是"技术公共件"，api 是"业务契约"——配额维度是业务概念，归属不同）
4. 新增一个跨服务调用要做哪三件事？（契约模块加接口方法 → 平台实现 /internal 端点 → AI 注入 PlatformClient 调用）
