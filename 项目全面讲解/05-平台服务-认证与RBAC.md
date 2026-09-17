# 05 - 平台服务认证与 RBAC（aihub-platform-service）

> **本文档自成一体**：不需要先读其他文档即可完整理解 aihub-platform-service 的认证与权限体系。所有代码片段均来自真实源码，标注了 `类名#方法名` 与行号。文中相对路径均相对于项目根目录 `javaai/`。

---

## 1. 服务职责与端口

### 1.1 服务定位

aihub-platform-service 是整个 AIHub 平台的"底座服务"，监听 **8081** 端口，对外提供四类能力：

| 能力 | 代表接口 | 所在包 |
|---|---|---|
| 认证（登录/注册/验证码） | `POST /auth/login`、`POST /auth/register`、`GET /auth/captcha` | `auth` |
| 用户/角色/菜单管理（RBAC） | `/api/platform/system/user/**`、`/role/**`、`/menu/**` | `system` |
| 租户数据 | `sys_tenant` 表读写（登录时解析租户码） | `user`/`tenant` |
| 配额与用量 | `/api/platform/usage/**`、`/internal/**`（AI 服务回传用量） | `quota`、`internal` |

它还是**全系统唯一"发令牌"的地方**——JWT 只由本服务的 `JwtTokenProvider` 签发，网关只负责"验票不发票"。

```yaml
# 来源：aihub/aihub-platform-service/src/main/resources/application.yml（第 1-2 行）
server:
  port: 8081
```

### 1.2 包结构地图

```
com.aihub.platform
├── auth/        认证入口：AuthController + AuthService + CaptchaService + vo
├── security/    JwtTokenProvider（签发/解析 JWT）
├── system/      RBAC：user/role/menu 三组 Controller + Service
│   ├── annotation/RequirePermission   权限注解
│   ├── interceptor/PermissionInterceptor  权限执法者
│   └── service/PermissionService      权限"查询大脑"（缓存）
├── user/        SysUserDO/SysUserMapper、SysTenantDO/SysTenantMapper
├── tenant/      SysTenantDO
├── quota/       配额策略/用量记录（本文不展开，见 08 篇）
├── apikey/      开放 API 密钥签发
├── internal/    Feign 内部契约接口（AI 服务调用）
└── config/      WebMvcConfig（拦截器注册）、CryptoConfig（BCrypt）、MybatisPlusConfig
```

### 1.3 与网关的分工：边界统一鉴权

一个高频架构问题：**鉴权到底放网关还是放各服务？** 本项目两面都做了，但分工明确：

- **网关（认证 Authentication）**：你是谁？——解析 JWT 验签，把 `tenantId/userId` 写进请求头转发给下游。登录接口 `/auth/**` 在网关白名单里（发令牌前当然没有令牌）。
- **平台服务（鉴权 Authorization）**：你能干什么？——`TenantResolveInterceptor` 重建租户上下文，`PermissionInterceptor` 按 RBAC 校验功能权限。

鉴权只做一次（网关），内网服务信任内网流量；服务级的 RBAC 是"第二道闸"，防御直连 8081 的内部误用。

---

## 2. 登录认证全流程源码讲解

这是本文档的核心章节。按 `AuthService#login` 的 ⓪~⑥ 步逐行走，每步贴真实代码。

### 2.0 全流程总览

```
前端(8849)                 网关(8080)                platform-service(8081)          MySQL/本地缓存
   │  GET /auth/captcha ──────►│                            │                            │
   │◄── {id, imageBase64} ─────┼──────────────────────────►│ CaptchaService#generate    │──► Caffeine 存答案
   │                           │                            │                            │
   │  POST /auth/login ───────►│ (白名单放行) ─────────────►│ AuthService#login          │
   │  {tenantCode,username,    │                            │ ⓪ captchaService.verify ───┼──► Caffeine 一次性取出比对
   │   password,captchaId,     │                            │ ① resolveTenantId ─────────┼──► sys_tenant 查租户码
   │   captchaCode}            │                            │ ② selectByTenantAndUsername┼──► sys_user（带 tenant_id）
   │                           │                            │ ③ BCrypt.matches           │
   │                           │                            │ ④ status==1 校验           │
   │                           │                            │ ⑤ jwtTokenProvider.generate│
   │◄── {token, tenantId, ─────┼───────────────────────────│ ⑥ 组装 AuthLoginRespVO     │
   │     userId, username}     │                            │                            │
   │                           │                            │                            │
   │  后续请求带 Authorization ─►│ AuthGlobalFilter#authenticateByJwt（验签→剥伪造头→写 X-Tenant-Id）──► 下游服务
```

### 2.1 入口：AuthController 只做三件事

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/controller/AuthController.java
// AuthController#login（第 63-66 行）
/** 登录：校验验证码 + 租户码 + 用户名 + 密码，签发 JWT。 */
@PostMapping("/login")
public R<AuthLoginRespVO> login(@Valid @RequestBody AuthLoginReqVO reqVO) {
    return R.ok(authService.login(reqVO));
}
```

Controller 只做"接 VO、调服务、包响应"三件事，业务全部下沉 Service。`@Valid` 会触发 `AuthLoginReqVO` 字段上的 `@NotBlank` 校验（`tenantCode/username/password` 三个字段必填，见 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/vo/AuthLoginReqVO.java` 第 23-30 行）。

**四段式小结**
- 作用与原理：`@RestController + @RequestMapping("/auth")` 暴露 REST 入口；VO 分层保证 DO（含 `passwordHash`）永远不出 Service。
- 项目真实用例：`AuthController#captcha/#register/#login`（第 47-66 行）。
- 常见误用：在 Controller 里直接收发 DO——一旦返回 `SysUserDO`，密码哈希就泄出去了。
- 面试怎么说："我们严格做了 web 层 VO 与持久层 DO 的分层，敏感字段靠结构而不是靠人肉置空来保证不出门。"

### 2.2 第⓪步：验证码生成与校验（CaptchaService）

**生成**（`CaptchaService#generate`，第 49-55 行）：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/CaptchaService.java
/** 生成一张 4 位字符的线段干扰验证码 */
public CaptchaImage generate() {
    LineCaptcha captcha = CaptchaUtil.createLineCaptcha(120, 40, 4, 10);
    String id = UUID.randomUUID().toString().replace("-", "");
    // getCode() 是答案；只存答案不存图片，图片直接给前端
    captchaCache.put(id, captcha.getCode());
    return new CaptchaImage(id, captcha.getImageBase64Data());
}
```

**校验**（`CaptchaService#verify`，第 61-70 行）：

```java
public boolean verify(String captchaId, String code) {
    if (!captchaEnabled) {
        return true;                       // aihub.auth.captcha-enabled=false 时放行（测试开关）
    }
    if (captchaId == null || code == null || code.isBlank()) {
        return false;
    }
    String expected = captchaCache.asMap().remove(captchaId);   // 一次性消费：取出即删除
    return expected != null && expected.equalsIgnoreCase(code.trim());
}
```

存储用的是 **Caffeine 本地缓存**（不是 Redis）：

```java
// 来源：CaptchaService（第 39-42 行）
/** 验证码缓存：key=验证码 ID，value=答案。2 分钟未使用自动过期 */
private final Cache<String, String> captchaCache = Caffeine.newBuilder()
        .expireAfterWrite(Duration.ofMinutes(2))
        .maximumSize(10_000)
        .build();
```

> **★ 如实说明（重要取舍点）：** 本项目验证码答案存的是 **Caffeine 本地缓存**，而不是 Redis。源码注释（CaptchaService 第 24-25 行）给出了理由：验证码是短命数据（2 分钟），且当前单机部署够用，不引入跨服务依赖；**多实例部署时必须换 Redis**（否则验证码生成在实例 A、校验落在实例 B，必然失败）。若演进为 Redis 方案：`captchaCache.put(id, code)` 换成 `redisTemplate.opsForValue().set("captcha:"+id, code, 2, MINUTES)`，`remove` 换成 `getAndDelete`，语义完全等价。这是典型的"按当前规模选最简方案，并预留清晰演进路径"的工程判断，面试可主动讲。

三个安全细节：
1. **只存答案不存图片**——图片 base64 直接给前端，服务端不需要它。
2. **一次性消费**——`asMap().remove(captchaId)` 原子地"取出并删除"，防重放：同一个验证码第二次提交必然失败。
3. **验证码校验放在登录流程最前面**——验证码不对就没必要查库（省资源，也不泄露"账号是否存在"的信息）。

### 2.3 第①步：租户码换租户 ID（resolveTenantId）

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/AuthService.java
// AuthService#resolveTenantId（第 225-240 行）
private Long resolveTenantId(String tenantCode) {
    if (tenantCode == null || tenantCode.isBlank()) {
        throw new BizException(ResultCode.PARAM_ERROR, "缺少租户标识");
    }
    SysTenantDO tenant = tenantMapper.selectOne(
            new LambdaQueryWrapper<SysTenantDO>()
                    .eq(SysTenantDO::getCode, tenantCode)
                    .last("limit 1"));
    if (tenant == null) {
        throw new BizException(ResultCode.PARAM_ERROR, "租户不存在，请检查租户码");
    }
    if (tenant.getStatus() == null || tenant.getStatus() != 1) {
        throw new BizException(ResultCode.FORBIDDEN, "租户已停用");
    }
    return tenant.getId();
}
```

登录必须显式带租户码（如 `demo`），因为用户名只在**租户内唯一**（数据库唯一键是 `uk_tenant_username(tenant_id, username)`，见 V1 迁移第 29 行）。租户码是全局唯一的（`uk_code`），所以"租户码 + 用户名 + 密码"三元组才能唯一定位一个账号。

### 2.4 第②步：查用户（SQL 天然带租户条件）

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/user/mapper/SysUserMapper.java
// SysUserMapper#selectByTenantAndUsername（第 24-25 行）
@Select("select * from sys_user where tenant_id = #{tenantId} and username = #{username} and deleted = 0 limit 1")
SysUserDO selectByTenantAndUsername(Long tenantId, String username);
```

注意三个点：
- `#{param}` 是**预编译占位符**，参数安全注入，天然防 SQL 注入；`${}` 是字符串拼接，永不用于拼用户输入。
- `deleted = 0` 要**手动带**：`@Select` 原生 SQL 不会自动应用 MyBatis-Plus 的 `@TableLogic` 逻辑删除过滤。
- `limit 1` 兜底唯一键之外的意外数据。

调用处（`AuthService#login` 第 96-101 行）：

```java
// 来源：AuthService#login（第 96-105 行）
// ② 查用户：SQL 带 tenant_id 条件 —— 同名用户在不同租户互不干扰
SysUserDO user = userMapper.selectByTenantAndUsername(tenantId, reqVO.getUsername());
if (user == null) {
    // 用户不存在与密码错误用同一文案（防用户名枚举，见类注释）
    throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
}
// ③ 验密码：matches(明文, 库里哈希)。哈希算法自带随机盐，直接比即可
if (!passwordEncoder.matches(reqVO.getPassword(), user.getPasswordHash())) {
    throw new BizException(ResultCode.UNAUTHORIZED, "用户名或密码错误");
}
```

### 2.5 第③④步：BCrypt 密码比对 + 状态校验

第③步 `passwordEncoder.matches(明文, 库里哈希)` 的原理在第 4 章展开。第④步：

```java
// 来源：AuthService#login（第 106-109 行）
// ④ 验状态：1=启用。status 为 null 视为停用（fail-safe：不确定时按最严处理）
if (user.getStatus() == null || user.getStatus() != 1) {
    throw new BizException(ResultCode.FORBIDDEN, "账号已停用");
}
```

`status == null` 按"停用"处理是 **fail-safe（默认拒绝）** 设计：不确定时按最严执行，宁可误拒不可误放。

### 2.6 第⑤步：签发 JWT

```java
// 来源：AuthService#login（第 111-112 行）
// ⑤ 签发 JWT：tenantId/userId/username 写进令牌 payload，有效期由配置决定
String token = jwtTokenProvider.generate(tenantId, user.getId(), user.getUsername());
```

### 2.7 第⑥步：组装响应

```java
// 来源：AuthService#login（第 114-120 行）
AuthLoginRespVO respVO = new AuthLoginRespVO();
respVO.setToken(token);
respVO.setTenantId(tenantId);
respVO.setUserId(user.getId());
respVO.setUsername(user.getUsername());
return respVO;
```

前端拿到 `token` 后存起来，之后每次请求带 `Authorization: Bearer <token>`（`AuthLoginRespVO` 第 14 行注释明确约定）。

### 2.8 防用户名枚举：同一个错误文案

`AuthService` 类注释（第 43-45 行）专门强调了这一点：**"用户不存在"和"密码错误"返回同一个提示"用户名或密码错误"**。如果分别提示，攻击者可以批量试探哪些用户名存在（用户名枚举攻击），再对这些有效用户名做定向暴力破解。注意与 `resolveTenantId` 的对比：租户不存在却**明确报错**——因为注册流程要求用户自查租户码，这里模糊提示反而伤害正常用户体验。两个接口两种策略，背后是"该接口暴露给谁、枚举收益多大"的威胁建模，不是一刀切。

### 2.9 附加：注册流程 = 自助开租户

`POST /auth/register` 一个请求创建"新租户 + 管理员账号 + ROLE_ADMIN 角色 + 全量菜单授权 + 用户角色绑定"，共写 5 张表：

```java
// 来源：AuthService#register（第 132 行注解 与 第 168-185 行节选）
@Transactional(rollbackFor = Exception.class)
public AuthRegisterRespVO register(AuthRegisterReqVO reqVO) {
    // …… 验证码校验 → 租户码/用户名/密码格式校验 → "demo"保留码拦截 → 租户码唯一性检查 ……
    // ③ 建租户：status=1 直接启用
    Long tenantId = IdWorker.getId();
    SysTenantDO tenant = new SysTenantDO();
    tenant.setId(tenantId);
    tenant.setName(companyName);
    tenant.setCode(tenantCode);
    tenant.setStatus(1);
    tenantMapper.insert(tenant);
    // ④ 建管理员用户（BCrypt 加密后落库，永不存明文）
    Long userId = IdWorker.getId();
    SysUserDO admin = new SysUserDO();
    admin.setId(userId);
    admin.setTenantId(tenantId);
    admin.setUsername(username);
    admin.setPasswordHash(passwordEncoder.encode(password));   // ← 编码处
    admin.setNickname("管理员");
    admin.setStatus(1);
    userMapper.insert(admin);
    // …… ⑤ 建 ROLE_ADMIN ⑥ 授权全部菜单 ⑦ 用户绑定角色 ……
}
```

要点：
- `@Transactional(rollbackFor = Exception.class)` 保证 5 张表要么全成功要么全回滚——比如授权菜单失败时，不能留下"有租户有管理员但没有角色"的脏数据。`rollbackFor = Exception.class` 是因为默认只对 `RuntimeException` 回滚，受检异常不回滚，这里显式放宽。
- 主键用 `IdWorker.getId()`（MyBatis-Plus 雪花 ID）：趋势递增、分布式唯一、不暴露业务量。
- `"demo"` 是保留码（第 156-158 行）：防止演示租户码被抢注导致教学环境混乱。

---

## 3. JWT 设计

### 3.1 无状态 vs Session：为什么选 JWT

| 维度 | Session（有状态） | JWT（无状态，本项目） |
|---|---|---|
| 会话数据存哪 | 服务端（内存/Redis） | 客户端令牌本身 |
| 横向扩容 | 需要 Session 共享（粘性路由或集中存储） | 任何实例验签即可，天然水平扩展 |
| 微服务间传递 | 需要网关反复查 Session 存储 | 网关解一次令牌，把身份写进请求头即可 |
| 主动注销 | 删服务端 Session 即可 | **做不到**（签发即有效至过期），只能等过期或换密钥 |
| 典型场景 | 传统单体、强会话业务 | 开放 API、微服务、多端（H5/小程序） |

本项目两个前端（管理端 Vue3 + 用户端 uni-app）+ 一个网关 + 两个后端服务，多端多服务架构下 JWT 是合理选择。`JwtTokenProvider` 类注释（第 29-31 行）点破了代价："令牌无状态（服务端不存会话），天然适合微服务横向扩容；代价是'签发后无法主动作废'——所以有敏感操作要重新认证，密钥泄露只能换密钥。"

### 3.2 令牌解剖：三段结构与 claims

一个 JWT 形如 `xxxxx.yyyyy.zzzzz`：头部（算法）.载荷（claims）.签名（HMAC）。

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/security/JwtTokenProvider.java
// JwtTokenProvider#generate（第 66-75 行）
public String generate(Long tenantId, Long userId, String username) {
    Date now = new Date();
    return Jwts.builder()
            .subject(username)                                              // sub = 用户名
            .claims(Map.of(CLAIM_TENANT, tenantId, CLAIM_USER, userId))     // 自定义 claims
            .issuedAt(now)                                                  // iat = 签发时间
            .expiration(new Date(now.getTime() + expireMillis))             // exp = 过期时间
            .signWith(Keys.hmacShaKeyFor(secretKey))                        // 用密钥签名（HS256）
            .compact();                                                     // 生成最终字符串
}
```

**令牌里放了什么：**

| claim | 值 | 用途 |
|---|---|---|
| `sub` | 用户名 | 标准声明，便于日志排查 |
| `tenantId` | 租户 ID（自定义） | **租户上下文的唯一可信来源**，网关据此写 `X-Tenant-Id` |
| `userId` | 用户 ID（自定义） | 审计"是谁在操作"，RBAC 校验的输入 |
| `iat` / `exp` | 签发/过期时间 | 过期判断，`exp` 默认 7200 秒 = 2 小时 |

**解析**（`JwtTokenProvider#parse`，第 84-90 行）：`parseSignedClaims(token)` 一次性完成格式检查、验签、过期检查，任何一步失败抛异常。取值方法（第 93-102 行）有一个防御细节：

```java
// 来源：JwtTokenProvider#parseTenantId（第 93-96 行）
public Long parseTenantId(String token) {
    Object v = parse(token).get(CLAIM_TENANT);
    return v == null ? null : Long.valueOf(String.valueOf(v));   // 统一经 String 转换防类型歧义
}
```

claim 是数字时，JSON 往返后可能是 `Integer` 也可能是 `String`，先 `String.valueOf` 再 `Long.valueOf` 消除类型歧义。

### 3.3 过期与续签

- **过期**：`exp` 由配置 `aihub.security.jwt-expire-seconds:7200`（application.yml 第 65 行）决定，网关和平台服务解析时都会检查——过期令牌直接 401。
- **续签：本项目没有做自动续签**。令牌过期后前端重新走登录流程拿新令牌。这是有意的简化：2 小时有效期对管理端足够；一旦引入 refresh token 双令牌机制，就要处理"refresh token 存哪、泄露怎么撤、并发登录怎么管"一整套复杂度。
- **为什么没有"退出登录就失效"**：无状态令牌签出后服务端不认识它。若要主动作废，常见方案是维护 Redis 黑名单（logout 时把令牌 jti 写入，网关每次验签多查一次）——这会重新引入共享存储，等于放弃部分无状态收益。本项目权衡后选择"短有效期 + 不过度注销"，教学与中小规模场景够用。

面试可以这样说："JWT 的无状态是拿'不可注销'换来的，我们的应对是 2 小时短有效期 + 网关统一验签 + 敏感操作（如改密）后要求重新认证。如果业务强制要求即时注销，我会加 Redis 黑名单，接受每次多一跳查询的代价。"

### 3.4 为什么把 tenantId 放 claims（而不是每次查库）

这是多租户架构的关键决策，三个理由：

1. **签名保证不可伪造**。JWT 的签名由服务端密钥生成，客户端改 payload 中任何字节都会验签失败。`AuthGlobalFilter` 类注释（第 41-43 行）原文："租户 ID 只从凭证解析，绝不接受前端传参。JWT 有签名验不出来不了假"——如果 tenantId 靠前端每次传请求头，任何人填个别的租户号就能越权。
2. **网关验签后即可获得租户身份**，不需要每个下游服务都去查一次 `sys_user` 表。网关 `AuthGlobalFilter#authenticateByJwt`（第 143-145 行）直接 `claims.get("tenantId")`、`claims.get("userId")`。
3. **全链路单一事实来源**。tenantId 在 token 里 → 网关写入 `X-Tenant-Id` → 拦截器写入 `TenantContext` → 业务查询带 `tenant_id` 条件。一条信任链，源头是签名保护的 claims。

### 3.5 密钥管理与对称签名的约束

- HS256 是**对称签名**：签发（platform-service）与验签（gateway）用同一把密钥 `aihub.security.jwt-secret`。`JwtTokenProvider` 类注释强调"**密钥一致是整个 JWT 体系成立的前提**"。
- 密钥从配置注入并支持环境变量覆盖：`jwt-secret: ${AIHUB_JWT_SECRET:aihub-default-dev-secret-please-change-me-32bytes}`（application.yml 第 64 行）——HS256 要求密钥 ≥ 32 字节，默认值本身也满足长度（生产必须换掉）。
- 与 API Key 的 HMAC 密钥（`api-key-secret`）**故意分离**（第 66-67 行注释）："避免一处泄露牵连另一处"——最小影响面原则。

---

## 4. 密码安全

### 4.1 BCrypt 原理：为什么不用 MD5/SHA-256

BCrypt 基于 Blowfish 加密算法改造，专为密码存储设计，核心三特性：

1. **自动加盐**：每次 `encode()` 生成随机盐，同一个密码每次编码结果都不同——彩虹表（预计算哈希表）彻底失效。盐直接嵌在哈希字符串里，无需单独存盐列。
2. **慢哈希**：内置成本因子（cost factor，默认 10），执行 `2^10 = 1024` 轮密钥扩展，单次计算约几十毫秒。攻击者离线爆破时每次猜测都要付出同样的时间成本，而正常用户登录一次多等几十毫秒无感。
3. **自适应成本**：硬件升级后调大成本因子即可维持安全裕度，哈希格式向前兼容。

**哈希字符串解剖**（以 V1 种子数据里的哈希为例）：

```
$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi
 │   │  └────────── 22位随机盐 ──────────┘└────── 31位哈希值 ──────┘
 │   └─ 成本因子 10（2^10 轮）
 └─ 算法版本 2a
```

`matches(明文, 哈希)` 的过程：从哈希串里**解析出盐和成本因子** → 用同样的盐和轮数对输入明文重算 → 常量时间比对结果。所以校验不需要存明文，也不需要单独的盐字段。

**为什么不用 MD5/SHA-256**：它们太快了（GPU 每秒可算数十亿次），加静态盐也只能防彩虹表，防不了高速暴力破解；且无自适应能力。密码存储必须用慢哈希（BCrypt/scrypt/argon2 家族）。

### 4.2 项目中的实现

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/CryptoConfig.java
// CryptoConfig#passwordEncoder（第 19-22 行）
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

做成全局单例 Bean 而不是各 Service 自己 `new`（类注释第 12-14 行给出三条理由）：① 单例复用；② 全局只有一种加密实现，将来升级 argon2 只改这一处；③ 测试时可注入 Mock。这是"配置归配置、业务归业务"的工程约定。

使用点有三处（注入类型全部是接口 `PasswordEncoder`）：
- 注册建管理员：`AuthService#register` 第 182 行 `passwordEncoder.encode(password)`；
- 管理员建子用户/重置密码：`SysUserService#create` 第 104 行、`SysUserService#resetPassword` 第 153 行；
- 登录与改密校验：`AuthService#login` 第 103 行 `matches(...)`；`SysUserService#changePassword` 第 208 行（先验旧密码再设新密码）。

**encode 与 matches 的经典误会**："我注册时 encode 的值和数据库里的哈希对不上？"——正常现象，盐是随机的，每次 encode 结果都不同；判断密码正确性只能用 `matches`，绝不能用"再 encode 一次比字符串"。

### 4.3 种子哈希事故复盘（真实踩坑 → 面试金句）

**现象**：按文档用 `demo / admin / admin123` 登录管理端，必然报"用户名或密码错误"。

**根因**：V1 迁移脚本第 118 行注释写着"密码：admin123"，但第 122-124 行硬编码的 BCrypt 哈希实际对应的是 **123456**（生成脚本时贴错了哈希串）：

```sql
-- 来源：aihub/aihub-platform-service/src/main/resources/db/migration/V1__init_platform.sql（第 118-124 行）
-- 初始化演示租户与用户（密码： admin123）
INSERT INTO sys_tenant (id, name, code, status) VALUES (1001, '演示租户', 'demo', 1)
    ON DUPLICATE KEY UPDATE name = VALUES(name);

INSERT INTO sys_user (id, tenant_id, username, password_hash, nickname, status)
VALUES (1, 1001, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', '管理员', 1)
    ON DUPLICATE KEY UPDATE nickname = VALUES(nickname);
```

**定位过程的关键障碍——BCrypt 无法反解**：数据库里只有哈希，你没法"解密"它看原文是什么。正确姿势是**对拍验证**：写一段小代码，把候选明文（admin123、123456……）逐个 `matches(候选, 库里哈希)`，谁返回 true 谁就是真实密码。一拍即中：123456。

**修复**：不能改 V1！Flyway 会校验已应用脚本的 checksum，改动历史脚本直接导致启动失败。正确做法是**新增增量迁移**：

```sql
-- 来源：aihub/aihub-platform-service/src/main/resources/db/migration/V10__fix_admin_seed_password.sql（第 8-10 行）
UPDATE sys_user
SET password_hash = '$2a$10$4a6Yl4qwbtuOs8vD5j0yZ.lgnSSeh1jKyROA116dG366EUc7Bf5gG'
WHERE id = 1 AND username = 'admin';
```

**教训与预防**：
1. 种子数据里的哈希必须**从真实明文现场生成**，不能从别处复制粘贴（贴错哈希=注释说谎）。
2. 已经进入版本库的 Flyway 脚本是**不可变历史**，任何修正都走增量迁移。
3. 凡是"哈希 + 注释声明明文"的组合，都应该有对拍验证的习惯——哈希世界没有"看起来对"。

### 4.4 常见误用清单

| 误用 | 后果 | 正确做法 |
|---|---|---|
| MD5/SHA-256 存密码 | GPU 秒破 | BCrypt 及其家族 |
| `encode` 两次结果比对 | 永不相等（盐随机） | `matches(明文, 哈希)` |
| 单独建 `salt` 列 | 多余 | 盐已在哈希串内 |
| 日志打印 `passwordHash` | 哈希外泄（可离线爆破） | DO 不出 Service；日志无哈希 |
| 前端传"已 MD5 的密码"再入库 | 无意义且锁死迁移 | 传输层 HTTPS，服务端统一 BCrypt |

---

## 5. RBAC 三表模型与菜单下发

### 5.1 模型总览：5 张表的关系

RBAC（Role-Based Access Control，基于角色的访问控制）的核心思想：**不直接给用户授权限，而是"用户 ↔ 角色 ↔ 权限"**，权限变更只动角色，成百上千用户无需逐一调整。

```
sys_user ──< sys_user_role >── sys_role ──< sys_role_menu >── sys_menu
 (用户)        (用户角色)        (角色)         (角色菜单)        (菜单/权限)
                带 tenant_id      带 tenant_id                    平台全局，无 tenant_id
```

建表与设计要点全部在 V7 迁移：

```sql
-- 来源：aihub/aihub-platform-service/src/main/resources/db/migration/V7__register_and_rbac.sql（第 16-33 行节选）
CREATE TABLE IF NOT EXISTS sys_menu (
    id          BIGINT       NOT NULL COMMENT '菜单ID（用固定段位便于种子数据引用）',
    name        VARCHAR(64)  NOT NULL COMMENT '菜单名称',
    permission  VARCHAR(128) NULL COMMENT '权限标识（如 system:user:create），按钮必填',
    menu_type   CHAR(1)      NOT NULL COMMENT 'M目录 C菜单 F按钮',
    parent_id   BIGINT       NOT NULL DEFAULT 0 COMMENT '父菜单ID，0=根',
    path        VARCHAR(128) NULL COMMENT '前端路由路径',
    component   VARCHAR(128) NULL COMMENT '前端组件路径',
    -- ……
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单（平台全局）';
```

V7 注释（第 1-6 行）写明了三条设计要点，其中两条直接关系到租户隔离：
1. **`sys_role` 带 tenant_id**——角色是"租户内"的概念，每个租户注册后自动获得自己的 ROLE_ADMIN；
2. **`sys_menu` 不带 tenant_id**——菜单/权限标识是平台全局统一定义的，租户只做"授权"不做"定义"（这也是 `MybatisPlusConfig#GLOBAL_TABLES` 把 `sys_menu`、`sys_role_menu` 列入租户白名单的原因，见 6.5 节）；
3. **按钮级权限**用 `menu_type=F` 的记录 + `permission` 标识（如 `system:user:create`）表达，前端按集合控制按钮显隐，后端按集合校验接口。

权限标识命名规范：**`模块:资源:动作`**（如 `system:user:create`、`system:role:assign-menu`、`ai:kb:query`），见 `RequirePermission` 注释第 22-24 行。

内置菜单树（V7 第 47-73 行）用**固定 ID 段**（1、10、11、20、211……）播种，注释说明原因："固定 ID 便于 ROLE_ADMIN 全量授权"——V9 补种脚本直接 `SELECT ... FROM sys_menu` 生成授权关系，ID 偏移段 9 亿避免与雪花 ID 冲突。

### 5.2 权限解析链路：PermissionService

"某用户能干什么"的计算过程（`PermissionService` 类注释第 29-33 行）：

```
用户 → sys_user_role → 角色列表 → sys_role_menu → 菜单列表 → 菜单上的 permission 字段集合
```

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/PermissionService.java
// PermissionService#loadPermissions（第 110-127 行）
private Set<String> loadPermissions(Long tenantId, Long userId) {
    List<Long> roleIds = activeRoleIds(tenantId, userId);
    if (roleIds.isEmpty()) {
        return Set.of();
    }
    // 角色 → 菜单关联
    List<Long> menuIds = roleMenuMapper.selectList(Wrappers.<SysRoleMenuDO>lambdaQuery()
                    .in(SysRoleMenuDO::getRoleId, roleIds))
            .stream().map(SysRoleMenuDO::getMenuId).distinct().toList();
    if (menuIds.isEmpty()) {
        return Set.of();
    }
    // 菜单 → 非空 permission 集合
    return menuMapper.selectBatchIds(menuIds).stream()
            .map(SysMenuDO::getPermission)
            .filter(p -> p != null && !p.isBlank())
            .collect(Collectors.toSet());
}
```

三处租户防御藏在 `activeRoleIds`（第 149-163 行）：查用户角色时 `eq(SysUserRoleDO::getTenantId, tenantId)`，过滤角色时 `eq(SysRoleDO::getTenantId, tenantId)`——**用户无法借到别的租户的角色**。一个用户可绑多角色，权限取**并集**。

### 5.3 @RequirePermission + PermissionInterceptor：注解式鉴权

自定义注解（`system/annotation/RequirePermission.java` 第 26-32 行）：`@Target(METHOD)` 只能标方法，`@Retention(RUNTIME)` 保证运行期可反射读取。

执法者（`system/interceptor/PermissionInterceptor.java#preHandle`，第 38-60 行）：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/interceptor/PermissionInterceptor.java
// PermissionInterceptor#preHandle（第 38-60 行节选）
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    // 静态资源等非 Controller 请求直接放行
    if (!(handler instanceof HandlerMethod handlerMethod)) {
        return true;
    }
    // 读取方法上的注解（方法级优先，可再扩展类级兜底）
    RequirePermission annotation = handlerMethod.getMethodAnnotation(RequirePermission.class);
    if (annotation == null) {
        return true;   // 没标注解 = 只要求登录态（租户拦截器已保证）
    }
    Long tenantId = TenantContext.requireTenantId();
    Long userId = TenantContext.getUserId();
    if (userId == null) {
        throw new BizException(ResultCode.UNAUTHORIZED, "缺少用户身份");
    }
    if (!permissionService.hasPermission(tenantId, userId, annotation.value())) {
        // 提示带上权限标识，方便排查"为什么这个账号点不了这个按钮"
        throw new BizException(ResultCode.FORBIDDEN, "无操作权限（" + annotation.value() + "）");
    }
    return true;
}
```

使用方式（以角色授权接口为例，`RoleController#assignMenus` 第 78-83 行）：`@RequirePermission("system:role:assign-menu")`。默认策略是**宽松默认**：没标注解的接口只要求"登录"，敏感接口才逐个标权限——对齐芋道框架的思路。

### 5.4 菜单下发接口：登录后的第一个调用

管理端登录成功后要"按角色渲染菜单"，靠这三个接口（`system/controller/PermissionController.java`）：

| 接口 | 方法（行号） | 返回 |
|---|---|---|
| `GET /api/platform/system/permission/routes` | `PermissionController#routes`（42-49） | 当前用户可见菜单**树**（仅 M/C 类型） |
| `GET /api/platform/system/permission/codes` | `PermissionController#codes`（55-60） | 按钮**权限标识集合**（如 `["system:user:create"]`） |
| `GET /api/platform/system/permission/info` | `PermissionController#info`（63-72） | routes + permissions 一次拉齐（省两趟） |

注意这两个接口**故意不标 @RequirePermission**——每个登录用户都要查自己的权限，只要求登录态（类注释第 26-28 行）。

树怎么组装？`SysMenuService#buildUserMenuTree`（第 47-49 行）接收已按角色过滤的平铺列表，交给通用组装逻辑：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysMenuService.java
// SysMenuService#buildChildren（第 113-123 行）
private List<MenuVO> buildChildren(Map<Long, List<SysMenuDO>> byParent, Long parentId) {
    List<SysMenuDO> children = byParent.getOrDefault(parentId, new ArrayList<>());
    children.sort(Comparator.comparing(m -> m.getSort() == null ? 0 : m.getSort()));
    List<MenuVO> nodes = new ArrayList<>(children.size());
    for (SysMenuDO child : children) {
        MenuVO vo = MenuVO.from(child);
        vo.setChildren(buildChildren(byParent, child.getId()));
        nodes.add(vo);
    }
    return nodes;
}
```

数据库里菜单靠 `parent_id` **平铺存储**，查出来后按 `parentId` 分组，从根（parentId=0）开始递归挂 `children`——先按 sort 排序保证顺序稳定。

前端拿到树后：`component` 字符串映射到本地 `views` 目录的组件，动态 `addRoute` 注册进 vue-router——这就是"不同角色登录看到不同菜单"的实现机制（`PermissionController` 类注释第 22-25 行）。

### 5.5 平铺结构的由来：一次前后端契约对齐的实战

**MenuVO 的注释记录了这次变更**：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/MenuVO.java（第 10-16 行）
/**
 * 菜单 VO（含 children 树结构）—— 管理端菜单树/授权树/动态路由共用。
 *
 * <p>替代原先的 MenuNode record 包装（{menu:{...}, children}）——
 * 直接把字段提升到 VO 本层，前端取值少一层嵌套。
 * <b>注意：</b>改了本 VO 的字段结构必须同步调整管理端
 * system/role、system/menu 两个页面的树数据处理。
 */
```

事情的来龙去脉：

1. **旧结构**：后端返回嵌套包装 `{menu: {id, name, ...}, children: [...]}`——多包了一层 `menu`。
2. **新结构**：改为**平铺**——字段直接提升到 VO 本层：`{id, name, path, component, ..., children: [...]}`。
3. **事故**：前端多处代码仍按旧的 `{menu:{...}}` 包裹解析，读 `node.menu.name` 读到 `undefined`，引发 **5 处前端连锁故障**：动态菜单解析（routes.ts）、刷新后菜单丢失（token 恢复时序）、顶级叶子路由崩溃（router/index.ts）、菜单管理页（menu/index.vue）、角色授权树（role/index.vue）。
4. **修复**：前端 5 处全部改为平铺取值对齐新契约。

**为什么后端选平铺**（面试重点）：
- 前端取值少一层嵌套，`node.name` 优于 `node.menu.name`；
- 树的"层级语义"已由 `children` 数组表达，再套一层 `menu` 包装没有信息增量，纯属冗余；
- 平铺结构与数据库 `sys_menu` 表的平铺存储、`MenuVO.from(DO)` 的逐字段映射天然对齐，转换代码最短。

**教训**：一个接口的响应结构变更会沿着"动态路由 → 权限指令 → 管理页"的依赖链多点爆开。跨端契约（字段名、嵌套层级）应该当成**显式契约**管理——变更要么保持向后兼容，要么同步盘点所有消费方。

### 5.6 权限缓存与失效

权限解析要逐级查 4 张表，每个请求都查太浪费，`PermissionService` 用 Caffeine 缓存（第 50-59 行）：权限集合 `perm:{tenantId}:{userId}`、菜单列表 `menu:{tenantId}:{userId}`，10 分钟过期 + 上限 1 万条。

失效策略与变更点一一对应（第 85-105 行）：

| 变更 | 失效方法 | 粒度 |
|---|---|---|
| 用户分配角色/改状态/删除 | `invalidateUser(tenantId, userId)` | 精确到人 |
| 角色授权菜单/改角色/删角色 | `invalidateRole(roleId)` → 实为 `invalidateAll()` | 全量（本地缓存无法反查"哪些用户绑了此角色"，教学项目接受粗粒度；生产可用 Redis 按角色建反向索引） |
| 菜单增删改 | `invalidateAllMenus()` | 全量 |

缓存 key 带上 `tenantId` 天然实现租户间缓存隔离——两个租户的同名用户（userId 相同概率极低，但 key 设计上仍以租户为先）互不串缓存。

---

## 6. 多租户隔离实现

### 6.1 三种隔离方案对比：我们选了什么、为什么

| 方案 | 做法 | 隔离强度 | 成本 | 适用 |
|---|---|---|---|---|
| 独立数据库 | 每租户一个 DB 实例 | ★★★★★ 物理隔离 | 高（连接数、备份、迁移 ×N） | 金融/强合规大客户 |
| 独立 Schema | 同库不同 schema | ★★★★ 逻辑隔离 | 中（跨 schema 查询、DDL 管理） | 租户数中等、中等合规要求 |
| **共享表 + 行级隔离** | 所有表带 `tenant_id` 列 | ★★★ 依赖代码纪律 | 低（一套表一套迁移） | 租户多、单体量小、SaaS 起步 |

本项目选择**共享行级隔离**：`V1__init_platform.sql` 开头注释即宣言——"所有业务表均以 tenant_id 作为隔离维度，且为复合索引首位"。理由：AIHub 目标是大量中小租户，独立库/Schema 的运维成本随租户数线性爆炸；行级隔离只要"查询永远带 tenant_id"这一条纪律执行到位，安全性足够，且未来可平滑迁移到独立 Schema。

代价也明说了：隔离强度从"物理"降级为"代码纪律"，所以必须用**多重防线**把纪律机械化（见 6.3）。

### 6.2 tenantId 信任链：token → 请求头 → ThreadLocal → 查询条件

这是本项目最高安全优先级的数据流，四跳环环相扣：

```
┌─ 第1跳 ────────────────┐ ┌─ 第2跳 ────────────────┐ ┌─ 第3跳 ─────────────────┐ ┌─ 第4跳 ──────────────┐
│ JWT claims             │→│ 网关写请求头            │→| TenantContext(ThreadLocal)│→│ SQL 查询条件          │
│ JwtTokenProvider       │ │ AuthGlobalFilter        │ │ TenantResolveInterceptor │ │ Service 显式 eq 条件  │
│ #generate (L66)        │ │ #authenticateByJwt      │ │ #preHandle (L42)         │ │ + MP 租户插件兜底     │
│ tenantId 已签名防伪造   │ │ 剥伪造头→写可信头 (L157) │ │ 头缺失即拒 (L49)         │ │ (默认关闭，见6.5)     │
└────────────────────────┘ └────────────────────────┘ └──────────────────────────┘ └──────────────────────┘
```

**第 2 跳（网关）**：`AuthGlobalFilter#authenticateByJwt`（gateway 第 157-164 行）解析令牌后**先剥离客户端伪造的同名头，再写入从令牌解析出的可信值**：

```java
// 来源：aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java
// AuthGlobalFilter#authenticateByJwt（第 156-164 行）
// 重建请求头：先剥离客户端带来的同名头（防伪造），再写入从令牌解析出的可信值
ServerHttpRequest mutated = exchange.getRequest().mutate()
        .headers(headers -> {
            headers.remove(TenantContext.HEADER_TENANT);
            headers.remove(TenantContext.HEADER_USER);
            headers.set(TenantContext.HEADER_TENANT, tenantId);
            headers.set(TenantContext.HEADER_USER, userId);
        })
        .build();
```

**第 3 跳（下游服务）**：`TenantResolveInterceptor#preHandle`（common 第 42-56 行）读取请求头写入 ThreadLocal：

```java
// 来源：aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantResolveInterceptor.java
// TenantResolveInterceptor#preHandle（第 45-55 行节选）
String tenant = request.getHeader(TenantContext.HEADER_TENANT);
String user = request.getHeader(TenantContext.HEADER_USER);
// fail-fast：租户头缺失直接拒绝，绝不让"无主请求"进入业务层
if (tenant == null || tenant.isBlank()) {
    throw new BizException(ResultCode.TENANT_MISMATCH, "请求缺少租户上下文");
}
TenantContext.set(Long.valueOf(tenant), user == null || user.isBlank() ? null : Long.valueOf(user));
```

为什么下游敢信这个请求头？因为**网关已强制剥离客户端伪造的同名头**（TenantResolveInterceptor 注释第 20-24 行明确记录了这条信任链），内网服务信任内网流量——这就是"边界统一鉴权"模式。

**第 4 跳（业务查询）**：Service 层随手取上下文。以用户分页为例：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java
// SysUserService#page（第 66-74 行）
public Page<SysUserRespVO> page(long pageNo, long pageSize, String keyword) {
    Long tenantId = TenantContext.requireTenantId();
    Page<SysUserDO> doPage = userMapper.selectPage(new Page<>(pageNo, pageSize),
            Wrappers.<SysUserDO>lambdaQuery()
                    .eq(SysUserDO::getTenantId, tenantId)     // ← 隔离条件
                    .and(keyword != null && !keyword.isBlank(),
                            w -> w.like(SysUserDO::getUsername, keyword)
                                    .or().like(SysUserDO::getNickname, keyword))
                    .orderByDesc(SysUserDO::getCreateTime));
    // ……
}
```

**为什么用 ThreadLocal？**（TenantContext 注释第 12-15 行）Spring MVC 下"一个请求 = 一个工作线程"，ThreadLocal 是每线程的私有储物柜：入口写入后，Controller → Service → Mapper 无论调用多深都能 `TenantContext.getTenantId()` 直取，不用层层传参。**代价**是请求结束必须 `clear()`（`afterCompletion` 第 62-66 行），否则线程池复用线程时会把上一个请求的租户带给下一个请求——经典的串台事故。登录/注册等公开路径不注册租户拦截器（`WebMvcConfig#addInterceptors` 第 52-53 行排除 `/auth/**`），因为登录时还不知道租户 ID，靠租户码换。

### 6.3 五道防线总览

| # | 防线 | 位置 | 挡什么 |
|---|---|---|---|
| 1 | tenantId 进 JWT claims（签名防伪造） | `JwtTokenProvider#generate` | 前端伪造租户身份 |
| 2 | 网关剥离并覆盖 `X-Tenant-Id` 头 | `AuthGlobalFilter#authenticateByJwt` | 客户端带假头冒充他租户 |
| 3 | 拦截器头缺失即拒（fail-fast） | `TenantResolveInterceptor#preHandle` | 漏传租户的"无主请求" |
| 4 | Service 显式 `eq(tenant_id)` + `mustGetOwn` 归属校验 | 各 Service | 按 ID 越权（IDOR） |
| 5 | MP 租户插件自动追加 WHERE（可选兜底） | `MybatisPlusConfig` | 开发者漏写条件的最后兜底 |

### 6.4 IDOR 防御：mustGetOwn 模式

IDOR（Insecure Direct Object Reference，不安全直接对象引用）：攻击者拿着自己租户的合法令牌，遍历/猜测**别的租户的资源 ID** 直接访问 `/user/{id}`。防法是"取到资源后先验归属"：

```java
// 来源：SysUserService#mustGetOwn（第 221-228 行）
/** 取本租户的用户，不存在/不属于本租户抛异常（IDOR 防御核心） */
private SysUserDO mustGetOwn(Long userId) {
    Long tenantId = TenantContext.requireTenantId();
    SysUserDO user = userMapper.selectById(userId);
    if (user == null || !tenantId.equals(user.getTenantId())) {
        throw new BizException(ResultCode.NOT_FOUND, "用户不存在");
    }
    return user;
}
```

同样的模式在 `SysRoleService#mustGetOwn`（第 144-151 行）出现。更细的例子是给用户分配角色时**校验角色归属本租户**（`SysUserService#assignRoles` 第 166-169 行）："校验角色归属本租户（防拿别的租户的角色 ID 来绑）"。另外还有防自锁设计：不能停用/删除自己的账号（第 121-123、136-138 行）——最后一名管理员把自己删了，整个租户就锁死了。

### 6.5 第五道防线：SQL 兜底插件（默认关闭的取舍）

人工写 `eq(tenant_id)` 总有漏的时候，MyBatis-Plus 提供了 `TenantLineInnerInterceptor`——改写每条 SQL 自动追加 `tenant_id = ?`。本项目**已落地但默认关闭**：

```java
// 来源：aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/MybatisPlusConfig.java
// MybatisPlusConfig#mybatisPlusInterceptor（第 47-62 行节选）
@Bean
public MybatisPlusInterceptor mybatisPlusInterceptor(
        @Value("${aihub.tenant.sql-guard.enabled:false}") boolean tenantGuardEnabled, ...) {
    MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
    if (tenantGuardEnabled) {
        // 顺序遵循 MP 官方建议：多租户 → 分页（先改 WHERE，再套 LIMIT/COUNT）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new PlatformTenantLineHandler(splitToSet(extraIgnoreTables))));
        log.warn("★ 租户 SQL 兜底已开启：……请确认已在真实库上回归过数据访问链路。");
    }
    interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return interceptor;
}
```

**为什么默认关闭**（类注释第 79-85 行，一段非常坦诚的工程判断）：它改写**每一条 SQL**，影响面覆盖全部数据访问；而本模块自动化测试是 Mockito 单测（Mapper 被 mock），覆盖不到真实 SQL 的解析与改写——"没有真实 MySQL 就无法证明所有语句都能被正确改写"。策略是"先落地、默认关、验证后再开"：测试环境起真实 MySQL 置 `aihub.tenant.sql-guard.enabled=true`，回归登录 → RBAC → API Key → 配额全链路，无 SQL 异常后再上生产。

三个实现细节值得看：
- **全局表白名单** `GLOBAL_TABLES = Set.of("sys_tenant", "sys_menu", "sys_role_menu")`（第 45 行）——租户表本身、平台全局菜单、角色菜单授权不含租户维度，插件必须跳过（`ignoreTable` 第 111-117 行）。
- **无租户上下文时跳过**：`ignoreTable` 第一行判断 `TenantContext.getTenantId() == null` 返回 true——登录、内部接口行为与关闭时完全一致，开启不破坏既有链路。
- 开关支持环境变量覆盖：`AIHUB_TENANT_SQL_GUARD_ENABLED`（application.yml 第 75-78 行）。

---

## 7. 本章面试三问与参考回答

**Q1：JWT 无状态认证怎么解决"注销/踢人"问题？你们为什么不做续签？**

参考回答：JWT 签发后服务端不存会话，这是它天然适合微服务横向扩容的原因，代价是不可主动作废。常见解法是 Redis 黑名单或短 access + refresh 双令牌，但都会重新引入共享存储与一套刷新协议。我们评估管理端场景后选择 2 小时短有效期 + 过期重登，敏感操作（改密）后要求重新认证；同时把"将来加黑名单"的演进路径讲清楚：网关验签处多一跳 Redis 查询即可，架构上没有死结。关键是让面试官看到我在"收益/代价"框架下做决策，而不是背概念。

**Q2：BCrypt 比 MD5 强在哪？数据库里的密码哈希和注释说明的密码对不上，你怎么排查？**

参考回答：三点——自动随机盐（防彩虹表，同密码每次哈希不同）、慢哈希（成本因子 10 即 2^10 轮，GPU 爆破成本指数级抬升）、自适应（硬件升级只调因子）。排查哈希不符不能反解，我用的对拍验证：把候选明文逐个 `matches(候选, 库里哈希)`，谁 true 谁是真实密码。我们项目真实发生过：V1 种子注释写 admin123，实际哈希对应 123456，登录必失败；因为 BCrypt 无法反解，最后靠对拍定位，再用增量迁移 V10 修正（历史 Flyway 脚本有 checksum 校验不能改）。修复后我还总结了一条规矩：种子哈希必须从真实明文现场生成。

**Q3：多租户行级隔离，怎么防止"开发者忘写 tenant_id 条件"导致数据越权？**

参考回答：我们做了五道防线纵深：①租户 ID 放 JWT claims 签名防伪造；②网关强制剥离并覆盖 X-Tenant-Id 请求头；③下游拦截器 fail-fast，头缺失直接拒绝，TenantContext 用 ThreadLocal 承载、请求结束清理防串台；④Service 层约定查询必带 eq(tenant_id)，按 ID 操作前必须 mustGetOwn 验归属（防 IDOR）；⑤MyBatis-Plus 的 TenantLineInnerInterceptor 做框架级兜底自动追加 WHERE——它默认关闭，因为改写所有 SQL 而单测 mock 了 Mapper 验证不到，策略是测试环境真实 MySQL 回归后再开。面试官如果追问"为什么不完全依赖⑤"，答案就是：改写器覆盖不到原生 @Select SQL 的全部场景，且插件静默失败比显式条件更难排查，所以人工纪律在前、框架兜底在后。

---

## 附：本章涉及源码路径索引

| 文件 | 关键内容 |
|---|---|
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/controller/AuthController.java` | 认证入口（captcha/register/login） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/AuthService.java` | 登录 ⓪~⑥ 步 + 注册事务 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/CaptchaService.java` | 验证码生成/校验（Caffeine 一次性消费） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/vo/AuthLoginReqVO.java` 等 | 登录/注册 VO（校验注解） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/security/JwtTokenProvider.java` | JWT 签发/解析/claims |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/CryptoConfig.java` | BCrypt Bean |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/WebMvcConfig.java` | 三拦截器注册（order 0/1/2） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/MybatisPlusConfig.java` | 分页插件 + 租户 SQL 兜底（默认关） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/PermissionController.java` | routes/codes/info 菜单下发 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/MenuController.java` | 菜单 CRUD（@RequirePermission） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java` | 用户 CRUD/重置密码/分配角色 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/RoleController.java` | 角色 CRUD/菜单授权 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysMenuService.java` | 菜单树组装（buildChildren） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java` | 用户服务（防自锁/mustGetOwn） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysRoleService.java` | 角色服务（整批替换授权） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/PermissionService.java` | 权限解析 + Caffeine 缓存 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/interceptor/PermissionInterceptor.java` | 注解执法者 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/annotation/RequirePermission.java` | 权限注解定义 |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/MenuVO.java` | 平铺菜单 VO（含变更注释） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/user/entity/SysUserDO.java` | 用户 DO（passwordHash 字段） |
| `aihub/aihub-platform-service/src/main/java/com/aihub/platform/user/mapper/SysUserMapper.java` | @Select 租户+用户名查询 |
| `aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantResolveInterceptor.java` | 租户头 → ThreadLocal |
| `aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantContext.java` | ThreadLocal 上下文 + clear |
| `aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java` | 网关验签 → 剥头写头 |
| `aihub/aihub-platform-service/src/main/resources/application.yml` | 端口 8081/JWT 配置/SQL 兜底开关 |
| `aihub/aihub-platform-service/src/main/resources/db/migration/V1__init_platform.sql` | 建表 + demo 种子（事故源头） |
| `aihub/aihub-platform-service/src/main/resources/db/migration/V7__register_and_rbac.sql` | RBAC 建表 + 内置菜单树 |
| `aihub/aihub-platform-service/src/main/resources/db/migration/V8__fix_user_role_addtime.sql` | sys_user_role 补列 |
| `aihub/aihub-platform-service/src/main/resources/db/migration/V9__demo_rbac_seed.sql` | demo 租户 RBAC 补种 |
| `aihub/aihub-platform-service/src/main/resources/db/migration/V10__fix_admin_seed_password.sql` | 种子密码哈希修复 |
