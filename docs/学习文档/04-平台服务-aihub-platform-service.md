# 04 · 平台服务 aihub-platform-service —— 学习文档

> 源码位置：`aihub/aihub-platform-service`（端口 8081，27 个类）
> 配套代码注释已逐行增强，建议对照源码阅读本文。

---

## 1. 这个服务是干什么的？

平台服务是整个系统的"**账号与账单中枢**"，管理端最离不开的服务：

```
业务包划分（每包内 controller → service → mapper → entity，按业务纵向切分）：
├── auth/      登录认证：验密码、发 JWT
├── tenant/    租户：多租户体系的"户口本"
├── user/      用户：属于某个租户的登录账号
├── apikey/    开放 API Key：签发/校验/吊销
├── quota/     配额与用量：扣减（写）+ 看板（读）
├── internal/  内部接口：AI 服务/网关经 Feign 调用
├── security/  JWT 签发
└── config/    MVC/OpenAPI 配置
```

**与网关的分工**：网关负责"验令牌"（验签、写租户头），平台负责"发令牌"（登录）和"管账"（配额/用量）。

---

## 2. 核心业务精讲

### 2.1 登录认证全流程（auth 包）

**请求路径**：前端 → 网关（/auth/** 免鉴权）→ AuthController → AuthService。

```
① resolveTenantId：租户码 → 租户 ID（M0 是内置映射，TODO 改查 sys_tenant 表）
② selectByTenantAndUsername：按 租户+用户名 查用户（SQL 带 tenant_id，防跨租户撞名）
③ passwordEncoder.matches(明文, 库里BCrypt哈希)：验密码
④ 状态校验：status != 1 → "账号已停用"
⑤ jwtTokenProvider.generate(...)：签发 JWT（tenantId/userId 写进令牌）
⑥ 返回 {token, tenantId, userId, username}
```

**三个安全设计（面试高频）**：
1. **"用户名或密码错误"统一文案**——分开提示会让攻击者批量探测有效用户名（用户名枚举）；
2. **BCrypt 而非 MD5/SHA256**：BCrypt 自带随机盐（同密码每次哈希不同，防彩虹表）+ 刻意慢（几十毫秒，拖垮暴力破解）。MD5 早就不安全；
3. **登录接口必须 POST**：密码不能出现在 URL（URL 会进各层访问日志）。

**JWT 生命周期**：
```
登录 → JwtTokenProvider.generate（HS256 签名，2小时过期）
    → 前端存 localStorage
    → 每次请求带 Authorization: Bearer xxx
    → 网关验签（AuthGlobalFilter）→ 解析 tenantId/userId → 写请求头 → 下游服务
```
无状态（服务端不存会话）→ 微服务横向扩容不用共享 session；代价是签发后无法主动作废。

### 2.2 API Key 管理（apikey 包）—— 最标准的分层示范

| 类 | 职责 |
|---|---|
| `ApiKeyController` | REST 三件套：POST 签发 / GET 列表 / DELETE 吊销 |
| `ApiKeyService` | 核心逻辑：签发（生成+哈希+入库）、verify（哈希比对+状态/过期校验）、touch（用量统计） |
| `SysApiKeyDO` | 表映射（@TableName/@TableId/@TableLogic） |
| `SysApiKeyMapper` | 继承 BaseMapper，白嫖 MyBatis-Plus 单表 CRUD |

**签发流程**：查当前 Key 数（上限 20）→ `ApiKeyCodec.generate()` 生成明文 → 算 HMAC 哈希存库 → **明文只在这次响应返回**（之后无法找回）。

**verify 流程**（鉴权热路径，被网关调用）：明文算哈希 → 按 keyHash 精确查（唯一索引，一次查询）→ 校验 status=1 → 校验未过期 → 返回租户归属。注意**不区分"不存在"和"已停用"**——统一返回失败，避免给攻击者探测信息。

**revoke 必须带 tenantId 校验**：`entity.getTenantId()` 与上下文租户比对，否则拿着别人的 Key ID 就能越权吊销。**所有"按 ID 操作"的接口都要做这层归属校验（IDOR 漏洞防御）**。

### 2.3 配额系统（quota 包）—— 两阶段扣减 + 原子计数

**三张表各司其职**：

| 表 | 角色 | 一行代表 |
|---|---|---|
| ai_quota_policy | 规则 | 某租户某维度某周期最多用多少 |
| ai_quota_usage | 计数器 | 某租户某维度某周期已用多少（唯一键） |
| ai_usage_record | 流水 | 一次调用的明细（看板从这算，扣减不看它） |

**扣减流程（QuotaService.consume）**：

```
第一阶段·试算：逐维度取策略（day 优先于 month）
    → currentUsed 查已用量 → used + amount > limitValue？超限则整体拒绝
第二阶段·累加：全部通过才统一 upsert（usageMapper.upsertConsume）
```

**为什么两阶段**：一次对话同时扣"次数+token"。先扣次数再扣 token 时若超限，会留下"次数被扣了但请求没执行"的脏数据。先全试算再统一累加 = "要么全扣、要么全不扣"。

**原子累加 SQL（AiQuotaUsageMapper.upsertConsume）**：
```sql
insert into ai_quota_usage (...) values (...)
on duplicate key update used_value = used_value + #{amount}
```
`ON DUPLICATE KEY UPDATE` 是 MySQL 的 upsert（存在即更新）——并发下不会"先查再插撞唯一键"，天然线程安全。

**两个精妙的工程细节（源码里有详细注释）**：
- 策略排序必须用 `Comparator` 且先拷贝列表（手写三元比较在 3 条以上策略时违反传递性会抛异常；Mapper 返回的列表可能不可变）；
- 周期键用字符串（"2026-09-11"/"2026-09"）——新周期自然落新行，**不需要定时任务清零**。

### 2.4 用量看板（UsageController + UsageQueryService）

四个接口：`/overview`（指标卡）、`/trend`（折线）、`/by-model`（模型分布）、`/quota`（配额余量）。

**读路径与写路径分离**：QuotaService（扣减，在鉴权热链路上）和 UsageQueryService（看板聚合，低频）分开，让热路径保持最小依赖。

**两个必须懂的细节**：
1. **趋势补零**：SQL 的 group by 只返回有数据的日期，服务层把窗口内空白日期补 0 —— 否则折线图把"两天没调用"画成一条跨越直线，误导运营；
2. **查询口径 = 扣减口径**：都取 `app_id IS NULL` 的租户级记录，否则看板显示一个从未被扣过的数字。

### 2.5 内部接口（InternalController）

对应 aihub-api 的 PlatformClient 契约五个方法。**与 /api/** 的区别**：网关不暴露 /internal 前缀（公网不可达），租户信息从请求体取（AI 侧 Feign 拦截器写入），所以 WebMvcConfig 把它排除在租户拦截器外。

---

## 3. 配置类精讲

### WebMvcConfig —— 拦截器注册
```java
order(0) TraceIdInterceptor      // 先有 traceId（报错日志才能查）
order(1) TenantResolveInterceptor // 再定租户
排除名单：/auth/**（登录）、/internal/**（内部契约）、/error、swagger、actuator
```
`WebMvcConfigurer` 是 Spring 的 MVC 定制回调接口，实现 addInterceptors 即可挂拦截器。

### OpenApiConfig —— Swagger 文档
springdoc-openapi 自动扫描 Controller 生成文档（http://localhost:8081/swagger-ui.html）。
`OperationCustomizer` 给每个接口文档补 X-Tenant-Id/X-User-Id 头参数说明（直连调试需要）。
生产环境 `springdoc.api-docs.enabled=false` 关闭。

---

## 4. 本模块注解词典

| 注解 | 出现位置 | 作用 | 常见坑 |
|---|---|---|---|
| `@RestController` | 所有 Controller | = @Controller + @ResponseBody，返回值自动转 JSON | 类上缺了会 404 或返回视图名 |
| `@RequestMapping` | 类（前缀）/方法 | URL 映射 | 类+方法路径拼接 |
| `@GetMapping/@PostMapping/@DeleteMapping` | 方法 | HTTP 方法绑定 | 登录等敏感操作必须 POST |
| `@RequestBody` | 方法参数 | JSON 请求体 → 对象 | 一个方法只能有一个 |
| `@RequestParam(defaultValue)` | 方法参数 | URL 查询参数 | 缺 defaultValue 时参数必传 |
| `@PathVariable` | 方法参数 | URL 路径变量 /{id} | 变量名要一致 |
| `@Valid` + `@NotBlank` | 参数/字段 | 触发 JSR-380 校验 | 校验失败由全局处理器转 PARAM_ERROR |
| `@RequiredArgsConstructor` | Service/Controller | final 字段构造器注入 | 只管 final 字段 |
| `@Service` | 业务类 | 声明业务层 Bean（单例） | 单例下不要有可变成员状态 |
| `@Component` | JwtTokenProvider 等 | 通用 Bean | — |
| `@Configuration` + `@Bean` | 配置类 | 声明配置与方法级 Bean | — |
| `@Value("${key:default}")` | 字段/构造器参数 | 注入配置 | 冒号后是默认值 |
| `@TableName` | DO 类 | 类↔表映射 | 驼峰↔下划线自动 |
| `@TableId(ASSIGN_ID)` | DO 主键 | 雪花 ID 自动生成 | 不用数据库自增 |
| `@TableLogic` | DO deleted 字段 | 逻辑删除（软删） | 原生 @Select SQL 不自动加 deleted=0 |
| `@Data` | DO/DTO | Lombok 全套 | — |
| `@Select/@Insert` | Mapper 方法 | 注解 SQL | #{xxx} 预编译，${} 是拼接（禁用于用户输入） |
| `@Slf4j` | 需要日志的类 | 生成 log 字段 | — |

---

## 5. 编码规范（本模块示范）

1. **Controller 三行式**：取上下文 → 调 Service → 包 R 返回。业务逻辑一律下沉 Service；
2. **租户一律取自 TenantContext.requireTenantId()**，绝不接受前端传参；
3. **按业务纵向分包**（auth/user/apikey/quota），每包内 controller-service-mapper-entity，而不是按层横向分包——改一个功能只动一个包；
4. **单表 CRUD 用 MP BaseMapper + lambdaQuery**，复杂聚合才写 @Select SQL；
5. **record 做返回视图**（ApiKeyView/QuotaSnapshot），不可变且省代码；
6. **写路径/读路径分离**（QuotaService vs UsageQueryService）。

---

## 6. 常见坑速查

| 坑 | 现象 | 解法 |
|---|---|---|
| 原生 @Select 忘写 deleted=0 | 查出已逻辑删除的行 | 手动带条件（或改用 MP 条件构造器） |
| 按 ID 操作不校验归属 | IDOR 越权（改/删别人的数据） | 先查归属再操作（见 revoke） |
| double 存金额 | 精度丢失 | BigDecimal |
| Mapper 返回列表直接 sort | UnsupportedOperationException | 先 new ArrayList<>(list) |
| 看板数字对不上扣减 | 口径不一致 | 统一 app_id IS NULL 租户级口径 |
| swagger 生产没关 | API 结构泄露 | springdoc.api-docs.enabled=false |

---

## 7. 学习自测

1. 画出登录的完整流程（Controller → Service → DB → JWT），说明每步失败时的响应。
2. 为什么"用户不存在"和"密码错误"要返回同一个提示？
3. BCrypt 和 MD5+盐 的本质区别是什么？（提示：慢哈希的设计目的）
4. API Key 签发后为什么找不回明文？库里存的什么？
5. 两阶段扣减解决了什么问题？极端并发下还有漏洞吗？
6. `ON DUPLICATE KEY UPDATE` 为什么比"先查再插或更新"安全？
7. 趋势图为什么要在服务层补零？
8. revoke 为什么要先比对 entity.getTenantId()？不比会怎样？

答完进入最大的模块 `aihub-ai-service`。
