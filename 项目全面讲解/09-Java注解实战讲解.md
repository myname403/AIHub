# 09 - Java 注解实战讲解（本项目真实用例版）

> 本文是 AIHub 微服务项目的注解专题讲解。**所有讲解的注解都来自对 `aihub/` 目录的真实扫描**（`git grep -oh "@[A-Z][A-Za-z]*" -- "aihub/*.java"` 统计频率取前 30 圈定范围），每个注解都给出项目里的真实文件路径、行号与代码片段。项目里不存在的注解（如 `@RefreshScope`）一个都不写，读者可以放心把本文当作"这个项目注解事实清单"来读。
>
> 阅读约定：所有路径相对 `aihub/` 目录；`文件#L行号` 表示该源文件的第几行；每个注解按 **作用与原理 / 项目中的真实用例 / 常见误用 / 面试怎么说** 四段式展开。

---

## 目录

1. [注解频率 Top30：本项目的注解地图](#1-注解频率-top30本项目的注解地图)
2. [注解的本质](#2-注解的本质)
3. [Lombok 组（重点：构造器注入为何优于字段注入）](#3-lombok-组)
4. [Spring 核心组](#4-spring-核心组)
5. [Spring Cloud 组](#5-spring-cloud-组)
6. [MyBatis-Plus 组（重点：@TableLogic 软删原理）](#6-mybatis-plus-组)
7. [Web 与校验组](#7-web-与校验组)
8. [@Transactional 失效场景（结合项目代码）](#8-transactional-失效场景)
9. [自定义注解实战与设计示例](#9-自定义注解实战与设计示例)
10. [面试高频问答 10 连](#10-面试高频问答-10-连)

---

## 1. 注解频率 Top30：本项目的注解地图

先上真实扫描结果（命令：`git grep -oh "@[A-Z][A-Za-z]*" -- "aihub/*.java"`，再按出现次数排序取前 30）：

| 排名 | 注解 | 次数 | 所属家族 | 本文讲解位置 |
|---:|---|---:|---|---|
| 1 | `@Test` | 317 | JUnit 5 | §3.8 |
| 2 | `@Override` | 189 | Java 内置 | §4.10 |
| 3 | `@Data` | 76 | Lombok | §3.1 |
| 4 | `@Value` | 52 | Spring | §4.6 |
| 5 | `@RequiredArgsConstructor` | 50 | Lombok | §3.2（重点） |
| 6 | `@Slf4j` | 42 | Lombok | §3.3 |
| 7 | `@RequestBody` | 35 | Spring MVC | §4.5 |
| 8 | `@GetMapping` | 35 | Spring MVC | §4.5 |
| 9 | `@Component` | 33 | Spring | §4.2 |
| 10 | `@Param` | 30 | MyBatis | §6.5 |
| 11 | `@PostMapping` | 28 | Spring MVC | §4.5 |
| 12 | `@TableName` | 28 | MyBatis-Plus | §6.1 |
| 13 | `@NotBlank` | 28 | Jakarta Validation | §7.2 |
| 14 | `@PathVariable` | 26 | Spring MVC | §4.5 |
| 15 | `@TableId` | 26 | MyBatis-Plus | §6.2 |
| 16 | `@RequirePermission` | **25** | **项目自定义** | §9.1（重点） |
| 17 | `@Valid` | 25 | Jakarta Validation | §7.1 |
| 18 | `@Bean` | 23 | Spring | §4.4 |
| 19 | `@RequestMapping` | 21 | Spring MVC | §4.5 |
| 20 | `@RestController` | 20 | Spring MVC | §4.3 |
| 21 | `@Configuration` | 19 | Spring | §4.4 |
| 22 | `@MapperScan` | 17 | MyBatis | §6.4 |
| 23 | `@Tool` | 17 | Spring AI（MCP 工具） | §5.4 |
| 24 | `@Select` | 17 | MyBatis | §6.5 |
| 25 | `@TableLogic` | 16 | MyBatis-Plus | §6.3（重点） |
| 26 | `@ToolParam` | 14 | Spring AI（MCP 工具） | §5.4 |
| 27 | `@Service` | 14 | Spring | §4.1 |
| 28 | `@ConfigurationProperties` | 13 | Spring Boot | §4.7（重点） |
| 29 | `@Mock` | 13 | Mockito | §3.8 |
| 30 | `@PutMapping` | 10 | Spring MVC | §4.5 |

**第 31 名及以后速览**（合计 193 次）：`@DeleteMapping` 8、`@Transactional` 7（§8 全章重点）、`@BeforeEach` 7、`@SpringBootApplication` 7、`@RequestParam` 7、`@EnableDiscoveryClient` 6、`@ConditionalOnProperty` 6（§5.3）、`@ExtendWith` 6、`@JsonIgnoreProperties` 6（§7.5）、`@Repository` 6（§4.2）、`@EnableConfigurationProperties` 5、`@AfterEach` 5、`@EnableFeignClients` 4、`@ExceptionHandler` 4（§7.4）、`@InjectMocks` 4、`@JsonInclude` 3（§7.5）、`@SpringBootTest` 3、`@RestControllerAdvice` 2（§7.3）、`@DateTimeFormat` 2（§7.6）、`@PostConstruct` 2（§4.8）、`@PreDestroy` 2（§4.8）、`@Lazy` 2（§4.9）、`@FeignClient` 2（§5.2）、`@Mapper` 2、`@Insert` 2、`@Update` 2、其余 1 次的（`@Getter`、`@NoArgsConstructor`、`@AllArgsConstructor`、`@Target`、`@Retention`、`@ControllerAdvice`、`@EnableWebSocket`、`@ComponentScan`、`@Import`、`@ResponseBody`、`@FunctionalInterface`、`@ConditionalOnMissingBean`、`@TempDir`、`@BeforeAll`、`@AfterAll`、`@JsonCreator` 等）。

**关于这份清单的三点诚实说明**（面试时也值得说，显得你真扫描过）：

1. **`@Slf4j` 在扫描里显示为 `@Slf`**：扫描正则 `@[A-Z][A-Za-z]*` 遇到数字就截断，`@Slf4j` 里的 `4` 不在字母范围内，所以原始输出把 42 次 `@Slf4j` 计成了 `@Slf`，表中已还原。
2. **计数包含注释与 Javadoc 里的提及**：本项目教学注释极其密集（源码里有大量 `{@code @Xxx}` 形式的讲解注释），所以少数注解的次数"虚高"——例如 `@Scheduled`/`@EnableScheduling`（各计 2 次）实际只出现在 `EventOutboxDispatcher.java` 的注释里（"为什么用自带守护线程而不是 @Scheduled"的设计讨论），`@SaCheckPermission`/`@PreAuthorize`/`@JsonCreator`/`@TableField` 的计数也全部来自注释文本。**这些"只在注释里出现"的注解不在本文讲解范围**；正文中每个讲解的注解都有真实生效代码。
3. **`@Autowired` 全项目只有 2 次，且都在测试类**（`AiHubMcpStdioApplicationTest.java#L29`、`AiHubMcpSseApplicationTest.java#L30` 的 `contextLoads` 冒烟测试）。生产代码的依赖注入全部走 `@RequiredArgsConstructor` + final 字段构造器注入——这正是 §3.2 要重点讲的工程决策。

---

## 2. 注解的本质

**先说"为什么"**：一个类、方法、参数本身只携带"它是谁"的信息（名字、签名），不携带"它该被怎么对待"的信息。注解就是贴在程序元素上的**元数据标签**——它自己没有任何行为，全部语义由"读它的人"赋予。同一个 `@Override`，Java 编译器读它是"检查父类是否真有这个方法"；你自定义一个同名注解，编译器则完全不理它。

**注解的三要素**：

- **声明**：`public @interface Xxx { String value(); }`——`@interface` 定义注解类型，属性写法是"方法风格"，本质是配置项。
- **元注解**（贴注解的注解）声明它的"性格"：
  - `@Target`：能贴在哪（`TYPE`/`METHOD`/`FIELD`/`PARAMETER`…）；
  - `@Retention`：活多久（`SOURCE` 只到编译期 / `CLASS` 到字节码 / `RUNTIME` 运行期反射可读）；
  - `@Documented`、`@Inherited`、`@Repeatable`（本项目未用到后三个，不展开）。
- **消费**：注解被谁读、怎么读，决定它的全部价值。三种消费方式在本项目各有一个真实代表：

| 消费方式 | 时机 | 本项目代表 | 谁在读 |
|---|---|---|---|
| 编译期 APT（注解处理器） | 编译时 | Lombok 组（`@Data` 等） | Lombok 注解处理器直接改写 AST，生成 getter/setter/构造器 |
| 容器启动期扫描 | 启动时 | `@Component`/`@Service`/`@Configuration` | Spring 类路径扫描，注册为 BeanDefinition |
| 运行期反射 | 每次请求 | `@RequirePermission`（自定义） | `PermissionInterceptor` 用 `getMethodAnnotation()` 反射读取并校验权限 |

**编译期 vs 运行期的一句话区别**：`SOURCE` 级注解（如 `@Override`、Lombok 全家）**在编译产物里已经消失**——反编译一个 `@Data` 类，你会看到真实的 getter/setter 字节码，而不是任何 Lombok 痕迹；`RUNTIME` 级注解则**长驻字节码**，运行期可随时 `getAnnotation()` 读取。选 `RUNTIME` 还是 `SOURCE`，取决于你的"执法者"是编译器/注解处理器，还是运行中的框架代码。

**面试怎么说**：注解本质是继承自 `java.lang.annotation.Annotation` 的接口，JVM 不给它任何行为；它的全部意义在于"声明式地把元数据和程序元素绑定，把处理逻辑让给框架"。用一句话收尾："注解负责**声明**，消费者负责**解释执行**——Spring 的强大不是因为注解多，而是因为它把读注解、执行语义的引擎做得足够完善。"

---

## 3. Lombok 组

Lombok 是"编译期注解处理器"的代表：所有 Lombok 注解在 `.class` 文件里都不存在，只存在于源码，编译时被展开成真实代码。本项目 Lombok 使用密度极高（`@Data` 76 次 + `@RequiredArgsConstructor` 50 次 + `@Slf4j` 42 次），且有一条明确的工程纪律：**依赖注入只用构造器注入，不用字段注入**。

### 3.1 `@Data` —— 一行顶六行

**作用与原理**：`@Data` 是组合注解，等价于 `@Getter` + `@Setter` + `@ToString` + `@EqualsAndHashCode` + `@RequiredArgsConstructor`（仅对 final 未初始化字段生成构造参数）。编译期由 Lombok 注解处理器把这些方法直接写进 AST，源码里干干净净。

**项目中的真实用例**：统一响应体 `R` 是全项目流量最大注解的载体——

```java
// aihub/aihub-common/src/main/java/com/aihub/common/result/R.java#L36-L52
@Data   // Lombok 注解：编译期自动生成所有字段的 getter/setter、toString、equals、hashCode
@JsonInclude(JsonInclude.Include.NON_NULL)
public class R<T> implements Serializable {
    private int code;
    private String message;
    private T data;
    private String traceId;
```

再如 DO 数据对象 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/entity/SysApiKeyDO.java#L33-L35`（`@Data` + `@TableName` 搭配是本项目 DO 的标准姿势），请求 VO `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/SysUserCreateReqVO.java#L9-L10`。

**常见误用**：
- **给有继承关系的类直接加 `@Data`**：`@EqualsAndHashCode` 默认不调用父类字段，两个子类实例可能被判定相等，需要 `@EqualsAndHashCode(callSuper = true)` 显式声明。本项目 DO 都是平铺结构没有继承，所以没踩这坑。
- **拿 `@Data` 的 DO 直接对外输出**：`toString()` 会把 `passwordHash` 打进日志，`getter` 会让敏感字段跟着序列化出门。本项目的防御是 VO 分层——`SysUserService.java#L28-L30` 的注释原话："passwordHash 在 DO → VO 转换时天然被丢弃，不再依赖'转换完记得手动 setNull'这种容易漏的人肉防线"。
- **在需要不可变语义的类上用 `@Data`**：全字段可变。配置类需要"可变 JavaBean"时 `@Data` 是对的（见 §4.7 的热更新原理），领域值对象则应该考虑 record。

**面试怎么说**："我在项目里给 DO 和 VO 统一用 `@Data`，但对外契约永远走 VO 层而不是 DO，因为 `@Data` 生成的 toString/getter 会把敏感字段带出门——这是用分层规范弥补 Lombok 粗粒度的典型例子。"

### 3.2 `@RequiredArgsConstructor`（重点）—— final 字段实现构造器注入

**作用与原理**：为所有 **final 且未初始化** 的字段（以及 `@NonNull` 字段）生成一个构造器，参数顺序与字段声明顺序一致。它与 Spring 的组合拳是：

```java
@Service
@RequiredArgsConstructor
public class XxxService {
    private final A a;   // → 进入构造器参数
    private final B b;   // → 进入构造器参数
}
```

Spring 4.3+ 规定：**单构造器的 Bean 无需任何注入注解**，容器自动用它构造。于是"final 字段 + Lombok 构造器 + Spring 单构造器自动注入"三件事拼出了零样板、全 final 的注入方式。

**为什么它优于 `@Autowired` 字段注入**（这是本项目的核心工程决策，面试必讲）：

| 维度 | 构造器注入（本项目） | 字段注入 `@Autowired` |
|---|---|---|
| 不可变性 | 字段可声明 final，线程安全、状态可推理 | 字段不能 final，任何代码都能改 |
| 必填语义 | 少一个依赖**启动即失败**（fail-fast），问题在部署期暴露 | 容器反射塞值，缺失要到**第一次调用时 NPE**，问题在生产期爆发 |
| 可测试性 | `new SysUserService(mockA, mockB)` 脱离 Spring 直接构造单测 | 必须 `@InjectMocks` 反射注入或起 Spring 上下文 |
| 循环依赖 | 启动直接报错，**逼你解耦** | 容器用三级缓存"偷偷"化解，坏设计被掩盖 |
| 依赖可见性 | 构造器签名即依赖清单，一眼看全 | 依赖散落在私有字段里 |

**项目中的真实用例**：生产代码 50 处 `@RequiredArgsConstructor`，最典型的是用户管理服务——

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java#L39-L50
@Service
@RequiredArgsConstructor
public class SysUserService {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("^\\w{3,32}$");

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final PermissionService permissionService;
    private final PasswordEncoder passwordEncoder;
```

五个依赖全是 final，构造器由 Lombok 生成，Spring 单构造器自动注入。Controller 层同样如此——`UserController.java#L36-L41`（`@RestController` + `@RequiredArgsConstructor` + `private final SysUserService userService`）。网关过滤器里还有一段"教科书注释"：

```java
// aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#L66-L73
/**
 * 构造器注入：@Value 从配置读 JWT 密钥（来自环境变量/Nacos，绝不硬编码）。
 * 构造器注入优于字段注入：依赖不可变（final）、便于测试、Spring 官方推荐。
 */
public AuthGlobalFilter(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                        ApiKeyCache apiKeyCache) {
```

而 `@Autowired` 在生产代码中的次数是 **0**——仅有的 2 次在两个 MCP 模块的 `contextLoads` 测试类里（`aihub/aihub-mcp-stdio/src/test/java/com/aihub/mcp/stdio/AiHubMcpStdioApplicationTest.java#L29`），那里注入的是 `ApplicationContext` 本身，属于测试基建。

**常见误用**：
- **字段不加 final**：`@RequiredArgsConstructor` 只认 final 未初始化字段，字段漏写 final 后该依赖悄悄退出构造器，注入失效、运行期 NPE。
- **同类中混入第二个构造器却不加 `@Autowired` 标注**：Spring 发现多个构造器且无标注时不知道选哪个，直接启动失败。本项目规避方式就是纪律：一个类只允许一个注入构造器。
- **依赖太多不觉得痛**：构造器注入会"胖构造器"自曝——参数超过 7 个就该拆类了。`SysUserService` 5 个依赖已接近本项目的舒适上限。

**面试怎么说**："我们项目用 `@RequiredArgsConstructor` + final 字段做构造器注入，全项目 `@Autowired` 只在两个测试类里出现。理由是四点：final 不可变、缺依赖启动即失败、单测可以 new、循环依赖无处遁形。这也是 Spring 官方 4.3 之后推荐的姿势。"

### 3.3 `@Slf4j` —— 日志门面的零样板化

**作用与原理**：在类中生成 `private static final org.slf4j.Logger log = LoggerFactory.getLogger(类名.class);`。logger 名随类名自动生成，换日志实现（logback/log4j2）不改一行代码——SLF4J 只是门面。

**项目中的真实用例**：

```java
// aihub/aihub-common/src/main/java/com/aihub/common/exception/GlobalExceptionHandler.java#L29-L31
@Slf4j   // Lombok：生成 private static final Logger log 字段，可直接 log.error(...)
@RestControllerAdvice
public class GlobalExceptionHandler {
```

兜底处理器打完整堆栈：同文件 `#L64-L68` 的 `log.error("未处理异常", e)`——第二个参数传异常对象才会打印堆栈。另一个用例：`aihub/aihub-ai-service/src/main/java/com/aihub/ai/config/TenantSqlGuardConfig.java#L60`。

**常见误用**：
- **占位符用字符串拼接**：`log.debug("user:" + user)` 即使 debug 关闭也会先拼串；正确写法 `log.debug("user:{}", user)`。
- **`log.error` 传异常只传 message**：`log.error(e.getMessage())` 丢失堆栈，排障时只剩一句谜语。
- **在父类标 `@Slf4j` 子类复用**：logger 名永远是父类全名，日志按类过滤时子类日志会被归错档。

**面试怎么说**："日志的三个细节我在项目里很在意：占位符代替拼接避免无效开销、`log.error(msg, e)` 第二参数保住堆栈、logger 名与类一致便于按类过滤——`@Slf4j` 把这些默认做对了，但用的人得知道错的时候错在哪。"

### 3.4 其余 Lombok 成员速览

- **`@Getter` / `@NoArgsConstructor` / `@AllArgsConstructor`**（各 2 次）：细粒度替代品。`@Data` 是"全家桶"，这三者是"单点菜"，适合只想暴露读、或需要保留无参构造器的场景（Jackson 反序列化常需要无参构造器）。
- **`@Value`（Lombok 版）**：本项目未使用（扫描到的 52 次 `@Value` 全部是 Spring 的配置注入，见 §4.6），不展开。

---

## 4. Spring 核心组

### 4.1 `@Service` —— 业务层的身份声明

**作用与原理**：`@Component` 的语义化别名，把类注册为 Spring 单例 Bean。框架行为上与 `@Component` 完全相同，区别只在于"人读的语义"：`@Service`=业务逻辑、`@Repository`=数据访问、`@Controller`=Web 层。语义化的价值在 AOP 时才显性化——很多切面按层切（如事务切面历史上按 `@Service` 包路径匹配）。

**项目中的真实用例**：`SysUserService.java#L39`（见 §3.2 代码），以及全项目 14 处 Service 类。注意本项目的 Service **不实现接口**——构造器注入 + 单测 new 的模式下，"面向接口编程"由仓储层（domain.spi）承担，Service 层直接是类。

**常见误用**：
- **在 Service 里做 HTTP 组装/协议转换**：层职责漂移，Controller 收到的应该是业务结果而不是 HTML 片段。
- **把 `@Service` 加在工具类上**：无状态工具类用静态方法即可，注册成 Bean 反而诱导注入"服务定位器"式的坏味道。

**面试怎么说**："我理解 `@Service` 和 `@Component` 的差别不在功能在语义，语义的价值在于让团队的分层约定可以被工具（切面、扫描规则、架构测试）看见。"

### 4.2 `@Component` 与 `@Repository` —— 通用与专用

**作用与原理**：`@Component` 是最泛化的 Bean 声明；`@Repository` 额外做一件事——把平台特定的数据访问异常（如 SQL 异常）翻译成 Spring 统一的 `DataAccessException` 体系，同时语义上标记"这是数据访问层"。

**项目中的真实用例**：
- `@Component`：`PermissionInterceptor.java#L32`（拦截器要被 `WebMvcConfig` 注入，必须先进容器）；`AuthGlobalFilter.java#L53`——注释原话："注册为 Spring Bean，Spring Cloud Gateway 自动收集所有 GlobalFilter 类型的 Bean"，这就是"容器收集 + 按类型装配"的机制红利。
- `@Repository`：`aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/persistence/DbAppRepository.java#L19`——AI 服务仓储模式中 `domain.spi` 接口的 `infra.persistence` 实现类，标注 `@Repository` 表明"我是仓储实现，异常请帮我翻译"。

**常见误用**：
- **拦截器/过滤器不进容器**：`new` 出来的拦截器无法注入依赖，也无法被配置类收集——本项目 `PermissionInterceptor` 标 `@Component` 后在 `WebMvcConfig.java#L36` 用 final 字段注入再注册，而不是 `new PermissionInterceptor()`。
- **以为 `@Repository` 的异常翻译是自动的**：翻译靠 `PersistenceExceptionTranslationPostProcessor` 织入代理，类上没有 `@Repository` 就没有这层代理。

**面试怎么说**："我项目里拦截器都是 `@Component` + 配置类注入注册，这样拦截器自己也能用构造器注入拿 Service；仓储实现类用 `@Repository` 拿到 Spring 的数据访问异常翻译。"

### 4.3 `@RestController` —— REST 接口的聚合注解

**作用与原理**：`@Controller` + `@ResponseBody`。后者把每个方法的返回值直接写进 HTTP 响应体（经 `HttpMessageConverter` 转 JSON），而不是交给视图解析器找 JSP/模板。本项目前后端分离，所有响应都是 JSON，所以清一色 `@RestController`。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java#L36-L41
@RestController
@RequestMapping("/api/platform/system/user")
@RequiredArgsConstructor
public class UserController {

    private final SysUserService userService;
```

全项目 20 个 `@RestController`，Controller 层统一的"三层注解头"：`@RestController` + `@RequestMapping`（类级路径前缀）+ `@RequiredArgsConstructor`。

**常见误用**：
- **类级 `@RestController` 内混写返回视图的方法**：`@ResponseBody` 是类级的，想返回视图得每方法去掉；与其纠结不如 REST 就纯 REST。
- **在方法里直接返回 DO**：见 §3.1 的敏感字段问题，统一返回 `R<VO>`。

**面试怎么说**："`@RestController` = `@Controller` + `@ResponseBody`，我把统一响应体 `R<T>` 作为所有方法的返回类型，code/message/data/traceId 四件套，前端永远不用猜响应结构。"

### 4.4 `@Configuration` 与 `@Bean` —— Java 配置的基石

**作用与原理**：`@Configuration` 声明"这个类是配置源"，Spring 启动时处理它；`@Bean` 标在方法上，方法返回值注册进容器，方法名默认即 Bean 名。`@Configuration` 类会被 CGLIB 代理（`proxyBeanMethods=true` 默认），保证类内互调 `@Bean` 方法时仍返回**同一个单例**而不是 new 出新对象。

**项目中的真实用例**：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/config/TenantSqlGuardConfig.java#L60-L79
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "aihub.tenant.sql-guard", name = "enabled", havingValue = "true")
public class TenantSqlGuardConfig {
    ...
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(
            @Value("${aihub.tenant.sql-guard.ignore-tables:}") String extraIgnoreTables) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 顺序遵循 MP 官方建议：多租户 → 分页（先改 WHERE，再套 LIMIT/COUNT，避免分页 SQL 难以解析）
        interceptor.addInnerInterceptor(new TenantLineInnerInterceptor(
                new AiTenantLineHandler(splitToSet(extraIgnoreTables))));
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
```

这个例子同时示范了三件事：`@Configuration` 注册配置源、`@Bean` 方法参数直接从容器/配置解析、`@ConditionalOnProperty` 控制整个配置类是否生效（§5.3 详讲）。MVC 侧的对应物是 `WebMvcConfig.java#L31-L33`（`@Configuration implements WebMvcConfigurer`）。

**常见误用**：
- **在 `@Bean` 方法里 new 一个带依赖的组件却手动传 null**：`@Bean` 方法参数声明出来，Spring 会自动解析——本项目 `extraIgnoreTables` 就是声明在方法参数上的 `@Value` 注入。
- **配置类里 `@Bean` 方法互调导致多例**：把 `@Configuration` 误写成 `@Component` 后失去 CGLIB 代理，互调每次都 new 新对象，单例被破坏。

**面试怎么说**："配置类的本质是'用 Java 代码描述 Bean 装配'，比 XML 类型安全、比自动装配可控。我们项目里所有拦截器、MyBatis-Plus 插件、向量库客户端都走 `@Configuration` + `@Bean`，且 `@Bean` 方法的依赖一律写进方法参数让容器解析。"

### 4.5 `@RequestMapping` 家族（`@GetMapping` / `@PostMapping` / `@PutMapping` / `@DeleteMapping` / `@RequestBody` / `@PathVariable` / `@RequestParam`）

**作用与原理**：把 URL + HTTP 方法 + 参数绑定到 Java 方法。`@GetMapping` 等是 `@RequestMapping(method = GET)` 的语义化缩写。参数侧三兄弟各司其职：`@RequestBody` 读**请求体**（JSON→对象，一个方法只能有一个）；`@PathVariable` 取**路径段**（`/{id}`）；`@RequestParam` 取**查询串/表单键值**（可带 defaultValue）。语义上还隐含了 REST 动词约定：GET 读、POST 建、PUT 改、DELETE 删。

**项目中的真实用例**（一个 Controller 内看全四个动词 + 三种参数）：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java#L44-L71
@RequirePermission("system:user:query")
@GetMapping("/page")                                   // GET 读
public R<Page<SysUserRespVO>> page(SysUserPageReqVO pageReqVO) { ... }

@RequirePermission("system:user:create")
@PostMapping                                           // POST 建
public R<Long> create(@Valid @RequestBody SysUserCreateReqVO reqVO) { ... }

@RequirePermission("system:user:update")
@PutMapping("/{id}")                                   // PUT 改 + 路径参数
public R<Void> update(@PathVariable Long id, @RequestBody SysUserUpdateReqVO reqVO) { ... }

@RequirePermission("system:user:delete")
@DeleteMapping("/{id}")                                // DELETE 删
public R<Void> delete(@PathVariable Long id) { ... }
```

`@RequestParam` 带默认值的真实用例——用量看板：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/controller/UsageController.java#L36-L39
@GetMapping("/overview")
public R<UsageQueryService.UsageOverview> overview(
        @RequestParam(value = "days", defaultValue = "7") int days) {
```

一个容易忽略的用法：**`@GetMapping` 也出现在 Feign 客户端接口上**（`aihub/aihub-api/src/main/java/com/aihub/api/client/PlatformClient.java#L45-L46`），这里的 `@GetMapping`/`@PostMapping`/`@RequestParam` 不再是"处理请求"而是"声明请求"——Spring MVC 和 OpenFeign 共享同一套参数注解，但消费者不同，这是同一注解两种语义的绝佳例子。

**常见误用**：
- **REST 语义误用**：删除用 GET、更新用 POST——爬虫预取/浏览器预检就会误触发写操作；本项目删除严格 `@DeleteMapping`。
- **`@RequestBody` 与 `@RequestParam` 混用于同一次传输**：JSON 体里取不到 `@RequestParam`，查询串也进不了 `@RequestBody`，混用前先想清楚数据在哪。
- **`@PathVariable` 名字与路径占位符不一致**：编译器不查，404 时才发现；显式写 `@PathVariable("id")` 最稳。
- **两个 `@RequestBody`**：HTTP 请求体只有一个流，第二个参数永远为 null。

**面试怎么说**："我用方法级映射注解表达 REST 动词、参数注解表达数据来源，并区分'服务端处理请求'（Controller）和'客户端声明请求'（Feign 接口）两种语境——同一套注解，读的方向相反。"

### 4.6 `@Value` —— 配置项注入（本项目全部用在构造器参数上）

**作用与原理**：从 `Environment` 解析 `${key:default}` 占位符填进注入点。支持 SpEL（`#{...}`）、默认值、类型转换。**本项目 52 次 `@Value` 全部出现在构造器参数上**，与 `@RequiredArgsConstructor`（无配置依赖时）或显式构造器（有配置依赖时）配合，不存在"字段 `@Value`"——这是刻意的：配置也是依赖，依赖就该走构造器。

**项目中的真实用例**：

```java
// aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java#L69-L73
public AuthGlobalFilter(@Value("${aihub.security.jwt-secret}") String jwtSecret,
                        ApiKeyCache apiKeyCache) {
    this.secretKey = jwtSecret.getBytes(StandardCharsets.UTF_8);
    this.apiKeyCache = apiKeyCache;
}
```

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/outbox/EventOutboxDispatcher.java#L56-L66
public EventOutboxDispatcher(AiEventOutboxMapper outboxMapper,
                             PlatformClient platformClient,
                             @Value("${aihub.outbox.batch-size:50}") int batchSize,
                             @Value("${aihub.outbox.max-retry:3}") int maxRetry,
                             @Value("${aihub.outbox.fixed-delay-seconds:30}") long fixedDelaySeconds) {
```

注意冒号语法 `${key:默认值}`——配置缺失时给兜底值而不是启动失败，这是"可降级配置"的写法；而 `jwt-secret` 故意不带默认值，**缺了就该启动失败**（安全配置宁可 fail-fast）。

**常见误用**：
- **在 final 字段上直接 `@Value`**：final 字段不能被容器反射赋值（`@Value` 底层是反射写字段），不报错但也不生效（或启动报错，取决于版本）——本项目构造器参数式写法天然规避。
- **把密钥等敏感配置写死在 `@Value` 里当默认值**：默认值会进代码仓库，本项目 `jwt-secret` 无默认值、来自环境变量/Nacos。
- **期望 Nacos 改配置后 `@Value` 自动刷新**：见下一节，`@Value` 只在启动时绑定一次。

**面试怎么说**："我们项目 `@Value` 只出现在构造器参数上，和普通依赖并列，因为配置也是依赖。可降级的配置写默认值，安全配置不写默认值强制 fail-fast——这个区分我在网关 JWT 密钥和 outbox 批量参数的写法对比里落地了。"

### 4.7 `@ConfigurationProperties`（重点）—— 可重绑定的配置组

**作用与原理**：把一组 `prefix` 下的配置批量绑定到一个 JavaBean（setter 注入），并支持校验。与 `@Value` 的本质差异：`@Value` 是**启动时一次性**解析；`@ConfigurationProperties` Bean 会被 spring-cloud-context 的 `ConfigurationPropertiesRebinder` 在收到 `EnvironmentChangeEvent` 时**整体重新绑定**——这就是 Nacos 配置热更新（RefreshEvent）能生效的机制基础。

**项目中的真实用例**——这个类把"为什么"写进了 Javadoc，是本项目配置实战的精华：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/ai/config/AgentProperties.java#L9-L21
 * 为什么从 @Value 改为 @ConfigurationProperties：
 * @Value 字段只在启动时绑定一次，Nacos 推送配置变更后不会回填；
 * @ConfigurationProperties bean 会被 spring-cloud-context 的
 * ConfigurationPropertiesRebinder 在收到 EnvironmentChangeEvent
 * （Nacos 配置变更触发 RefreshEvent）时整体重新绑定——Nacos 上改完配置，
 * 下一次 Agent 执行读到的就是新值，全程无需重启。
 *
 * 因此本类必须保持「可变 JavaBean」风格：record / 构造器绑定的不可变对象
 * 无法被重绑定（rebind 走的是 setter），写成不可变类会静默失去热更新能力。
@Data
@ConfigurationProperties(prefix = "aihub.agent")
public class AgentProperties {
    private int maxSubTasks = 3;
    private long maxTokens = 0;
    private long timeoutMs = 180000;
```

同族还有 `aihub.rag`、`aihub.chat-client`（`aihub-ai-service/.../infra/ai/config/`）、`aihub.storage`（`StorageProperties.java#L20`）、`aihub.mcp`（`aihub-mcp-service/.../config/AiHubMcpProperties.java#L16`）等 13 处。激活方式两种：`@EnableConfigurationProperties(Xxx.class)`（如 `StorageConfiguration.java#L19`、`McpChannelAuthConfiguration.java#L18`）或 `@Component` 标注类本身。

**常见误用**：
- **写成不可变类（record/构造器绑定）还想热更新**：rebind 走 setter，不可变类静默失去热更新能力——本项目 Javadoc 原话级别的教训。
- **前缀拼写不一致**：`aihub.agent` 与配置文件的 `aihub.agent.` 键必须严格对应，绑不上的字段不报错只是 null/默认值，排查全靠对比。
- **需要热刷新的项散落 `@Value`**：改配置要重启的服务和"配置中心"名不副实；本项目策略是**会调优的参数组用 `@ConfigurationProperties`，一次性组装参数用 `@Value`**。

**面试怎么说**："我项目里把 Agent 预算参数从 `@Value` 迁到了 `@ConfigurationProperties`，原因不是风格而是能力：前者启动绑定一次，后者接 Nacos 的 RefreshEvent 会被 Rebinder 整体重绑定，改配置不重启。代价是类必须保持可变 JavaBean——rebind 走 setter，不可变对象会静默失去热更。"

### 4.8 `@PostConstruct` / `@PreDestroy` —— Bean 生命周期的起止钩子

**作用与原理**：单例 Bean 依赖注入完成后回调 `@PostConstruct` 方法；容器关闭、`@PreDestroy` 方法执行。用来做"组件自启动"与"优雅关停"，不用实现 Spring 的 `InitializingBean/DisposableBean` 接口（保持对框架零侵入）。

**项目中的真实用例**——发件箱投递器的启动与关停：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/outbox/EventOutboxDispatcher.java#L68-L86
@PostConstruct
void start() {
    scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "aihub-outbox-");
        t.setDaemon(true);   // 守护线程：不阻止 JVM 退出
        return t;
    });
    scheduler.scheduleWithFixedDelay(this::pollSafely, ...);
}

@PreDestroy
void stop() {
    if (scheduler != null) {
        scheduler.shutdownNow();
    }
}
```

**常见误用**：
- **在 `@PostConstruct` 里启动非守护线程**：Spring 容器关闭了线程还活着，测试挂起、端口占用——本项目配套 `@PreDestroy` + `shutdownNow()`，两钩子成对出现。
- **在 `@PostConstruct` 里依赖其他 Bean 却写成构造器逻辑**：时机不对（构造时依赖还没注入完）。
- **`@PostConstruct` 抛异常导致整个应用启动失败**：初始化要幂等、可重试，投递失败这类问题应该交给循环里的重试逻辑而不是让启动失败。

**面试怎么说**："后台轮询组件的生命周期我用 `@PostConstruct` 起线程、`@PreDestroy` 关线程，成对出现；线程设成 daemon 防止 JVM 退不出去，轮询循环里再兜一层 try-catch 防止调度线程被异常杀死——这是本项目 outbox 投递器的写法。"

### 4.9 `@Lazy` —— 打破构造器注入时代的循环依赖

**作用与原理**：`@Lazy` 注入的是代理，第一次真正使用时才解析目标 Bean。字段注入时代循环依赖被三级缓存悄悄消化，构造器注入让循环依赖**启动即报错**——此时 `@Lazy` 是标准解法之一：把环上的一条边改成"用到再取"。

**项目中的真实用例**：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/ai/agent/PlanningAgent.java#L59-L63
public PlanningAgent(ChatClientFactory chatClientFactory,
                     @Lazy AgentRegistry registry,
                     AgentTaskRepository taskRepository,
                     AgentCancelRegistry cancelRegistry,
                     AgentProperties agentProperties) {
```

PlanningAgent 需要向 AgentRegistry 注册自己（或经它查找兄弟 Agent），而 AgentRegistry 又要收集所有 Agent——天然互指。`@Lazy` 让 registry 以代理身份进构造器，环被解开且代码依然是全 final 构造器注入。

**常见误用**：
- **把 `@Lazy` 当循环依赖的万能胶**：环本身就是设计问题，先用注册中心/事件/接口拆分化解，`@Lazy` 是不得已的最后一环。
- **以为 `@Lazy` Bean 的错误会延迟发现**：代理第一次调用才初始化，启动期检查不到目标 Bean 的问题，掩盖 fail-fast。

**面试怎么说**："我们全项目构造器注入，循环依赖无处遁形。唯一一处环是 PlanningAgent 和 AgentRegistry 互相需要，我在注入点标了 `@Lazy`——注入的是代理，首次使用才解析。我的原则是环先用设计解，`@Lazy` 只留给'代理注册自身'这种结构上合理的环。"

### 4.10 `@Override` —— 编译器背书的契约

**作用与原理**：`SOURCE` 保留级注解，唯一作用是让编译器检查"这个方法真的是覆盖父类/接口方法"。拼错方法名、参数类型不匹配时立刻编译失败。

**项目中的真实用例**：全项目 189 处。例如 `PermissionInterceptor.java#L38`（`preHandle` 覆盖 `HandlerInterceptor` 接口方法）、`MybatisPlusConfig.java#L99-L111`（`TenantLineHandler` 的三个回调方法）、`AuthGlobalFilter.java#L82`（`GlobalFilter.filter`）。

**常见误用**：该标不标——不标 `@Override` 时"以为在覆盖、其实在重载"的错误没有任何编译期提示（经典案例：参数写成了包装类型 `Long` 而父类是 `long`）。

**面试怎么说**："我要求所有覆盖一律标 `@Override`，它是零成本的编译期契约检查——这也是'用声明把正确性交给工具'的最小例子。"

### 4.11 测试注解速览（JUnit 5 + Mockito）

Top30 里测试注解占了三席（`@Test` 317、`@Mock` 13、`@PutMapping` 之外还有 `@BeforeEach` 7、`@ExtendWith` 6、`@InjectMocks` 4、`@SpringBootTest` 3、`@TempDir` 1），值得一个 compact 四段式：

- **作用与原理**：`@ExtendWith(MockitoExtension.class)` 把 Mockito 的扩展挂进 JUnit 5 生命周期，`@Mock` 生成接口/类的 Mockito 代理，`@InjectMocks` 把 mock 注入被测对象；`@Test` 标记测试方法，`@BeforeEach/@AfterEach` 提供前后置钩子；`@SpringBootTest` 拉起真实 Spring 上下文（重量级，仅冒烟测试用）；`@TempDir` 注入临时目录（文件类测试）。
- **项目中的真实用例**：`aihub/aihub-platform-service/src/test/java/com/aihub/platform/quota/service/QuotaServiceTest.java#L24-L36`（`@ExtendWith(MockitoExtension.class)` + `@Mock` × 3 + `@InjectMocks`）；`aihub/aihub-platform-service/src/test/java/com/aihub/platform/config/MybatisPlusConfigTest.java#L27-L34`（`@AfterEach` 里 `TenantContext.clear()` 防 ThreadLocal 串扰 + `@Test`）；`aihub/aihub-mcp-stdio/src/test/java/com/aihub/mcp/stdio/AiHubMcpStdioApplicationTest.java#L25-L29`（`@SpringBootTest` + `@Autowired` 冒烟验证上下文可启动）。
- **常见误用**：滥用 `@SpringBootTest` 做逻辑单测——上下文启动慢且引入环境依赖，本项目策略是"逻辑用 Mockito 纯单测，上下文完整性用极少量 `@SpringBootTest` 冒烟"；忘记 `@AfterEach` 清理 ThreadLocal 导致用例间串扰（本项目 `TenantContext.clear()` 的注释原话："ThreadLocal 必须清理，否则污染同线程的其它用例"）。
- **面试怎么说**："我的单测策略是金字塔：Mockito 纯单测占绝大多数（不起容器、毫秒级），`@SpringBootTest` 只做 contextLoads 冒烟——快反馈、低脆弱。"

---

## 5. Spring Cloud 组

### 5.1 `@EnableDiscoveryClient` —— 服务注册发现的总开关

**作用与原理**：激活服务发现客户端，启动时把 `spring.application.name` + 本机地址注册到注册中心（本项目是 Nacos 3.0.3），并让 `lb://` 路由/Feign 按服务名从注册中心取实例列表。注意：Spring Cloud Commons 从 Edgware 起其实"发现了类路径上的注册中心实现就自动注册"，该注解更多是**显式声明意图**。

**项目中的真实用例**：三个可被调用的服务启动类都标注——

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/PlatformApplication.java#L37
@EnableDiscoveryClient
@MapperScan({...})
@SpringBootApplication(scanBasePackages = "com.aihub")
public class PlatformApplication {
```

同款：`aihub/aihub-ai-service/src/main/java/com/aihub/ai/AiServiceApplication.java#L45`、`aihub/aihub-gateway/src/main/java/com/aihub/gateway/GatewayApplication.java#L41`。网关路由表里 `lb://aihub-platform-service` 的 `lb://` 前缀就靠它解析成真实实例地址。

**常见误用**：
- **只配了 Nacos 地址忘了开鉴权参数**：本项目真实事故——Nacos 3.0.3 默认开启鉴权，客户端不带 username/password 注册直接 403，服务"看起来启动成功"但注册中心里没有它。
- **服务名带大写/下划线不统一**：`lb://` 路由名、Feign name、`spring.application.name` 三处必须严格一致。

**面试怎么说**："注册发现我踩过一个真问题：Nacos 3.0.3 开鉴权后客户端注册 403，现象是服务进程活着但注册中心没有实例。解法是给每个服务客户端配置 username/password——这个案例让我明白'注册失败不是异常而是静默缺失'，健康检查要看注册中心而不是进程。"

### 5.2 `@EnableFeignClients` 与 `@FeignClient` —— 声明式服务间调用

**作用与原理**：`@FeignClient` 把一个接口变成 HTTP 客户端：启动时 Feign 为接口生成动态代理，调用方法 = 按注解声明组装 HTTP 请求 → 从注册中心按 `name` 负载均衡寻址 → 发请求 → 反序列化返回值。`@EnableFeignClients` 负责扫描出这些接口并注册代理 Bean。

**项目中的真实用例**：

```java
// aihub/aihub-api/src/main/java/com/aihub/api/client/PlatformClient.java#L38-L50
@FeignClient(
        name = "aihub-platform-service",   // 目标服务名：必须在 Nacos 注册中心里叫这个名字
        path = "/internal"                 // 所有方法的公共路径前缀
)
public interface PlatformClient {

    @GetMapping("/tenants/check")
    R<TenantBrief> checkTenant(@RequestParam("tenantId") Long tenantId);

    @PostMapping("/quota/check")
    R<QuotaResult> checkAndConsume(@RequestBody QuotaRequest request);
```

消费者侧：`AiServiceApplication.java#L46` 的 `@EnableFeignClients(basePackages = "com.aihub.api")` 限定扫描范围。提供者侧 `PlatformApplication.java#L23` 注释原话："无需 @EnableFeignClients——只有'要调用别人'的服务才需要启用 Feign 客户端"——不调用别人就不开扫描，启动更快、注入面更小。契约接口与 DTO 独立成 `aihub-api` 模块，调用方和提供方依赖同一 jar，改契约时编译器立刻暴露两端不一致（契约优先设计）。

**常见误用**：
- **`name` 写错或没配**：Feign 按服务名查 Nacos，名字对不上运行时 `UnknownHostException` 而不是启动失败。
- **Feign 接口里塞业务方法**：Feign 只该是"HTTP 契约镜像"，本项目硬约束"只允许租户校验/配额扣减/审计上报三类跨服务调用"（`PlatformClient.java#L33-L35` Javadoc）。
- **指望 Feign 调用参与本地事务**：见 §8 的跨服务"事务失效"。

**面试怎么说**："Feign 的本质是'接口 + 注解 → 动态代理 → 模板化 HTTP'。我们把它和契约模块（aihub-api）绑定：接口和 DTO 放独立模块，两端依赖同一 jar，契约漂移在编译期就爆炸。"

### 5.3 `@ConditionalOnProperty` —— 按配置开关装配

**作用与原理**：`@Conditional` 家族的一员——满足条件才把组件/配置类注册进容器。`prefix + name` 定位配置键，`havingValue` 比对值，`matchIfMissing=true` 表示键不存在时也算命中。这是"可选功能"的标准开关：不用的功能连 Bean 都不创建，零开销。

**项目中的真实用例**（6 处，全部是"可选能力"开关）：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/config/TenantSqlGuardConfig.java#L62
@ConditionalOnProperty(prefix = "aihub.tenant.sql-guard", name = "enabled", havingValue = "true")
public class TenantSqlGuardConfig {
```

- `aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/storage/StorageConfiguration.java#L24,L42`：本地存储 vs MinIO 二选一（`havingValue="local"` 配 `matchIfMissing=true` 作默认，`havingValue="minio"` 作可选项）。
- `aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/outbox/EventOutboxDispatcher.java#L42`：outbox 投递器默认开（`matchIfMissing = true`）。
- `aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/McpChannelAuthConfiguration.java#L17`：MCP 通道鉴权按需开启。
- `aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/browser/BrowserConfiguration.java#L26`：浏览器 Agent 可选启用。

`TenantSqlGuardConfig` 的条件语义最有讲究：**能力默认关闭**。`MybatisPlusConfig.java#L79-L89` 的 Javadoc 解释了为什么——租户 SQL 兜底改写每一条 SQL，影响面覆盖全部数据访问，而模块的自动化测试是 Mockito 单测（Mapper 被 mock），覆盖不到真实 SQL 解析改写，"没有真实 MySQL 就无法证明所有语句都能被正确改写，因此采取先落地、默认关、验证后再开"。

**常见误用**：
- **忘了 `matchIfMissing` 的语义**：不写时键缺失=不装配（默认关）；想"默认开"必须显式 `matchIfMissing=true`。两种默认策略都是合法设计，但要**想清楚再选**——本项目正是"先想清楚"的示范：危险能力默认关、安全默认开。
- **开关藏在深配置里没人知道**：本项目在开启时主动 `log.warn("★ 租户 SQL 兜底已开启...")`（`TenantSqlGuardConfig.java#L80-L82`），让高危开关在日志里可见。

**面试怎么说**："条件装配是'零成本可选功能'的关键：没开的配置连 Bean 都不存在。我项目里最典型的是租户 SQL 兜底——能力真实存在但默认关闭，因为 Mockito 单测证明不了 SQL 改写的正确性，所以先落地、默认关、真实库回归后再开，开启时打 warn 日志留痕。"

### 5.4 `@Tool` / `@ToolParam` —— 把 Java 方法变成模型可调用的 MCP 工具

**作用与原理**：Spring AI 的工具注解。`@Tool(name, description)` 把方法注册为 MCP 工具——启动时框架反射读取方法签名生成 JSON Schema，发布到 MCP 客户端；**`description` 是大模型决定"用不用、怎么用、怎么传参"的唯一依据**。`@ToolParam(description, required)` 声明参数语义，`required=false` 的参数模型可不传、方法内用默认值兜底。

**项目中的真实用例**——MCP 工具集（对模型的"说明书"写得极其认真）：

```java
// aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/tools/AiHubTools.java#L109-L121（import 在 #L10-L11）
@Tool(name = "aihub_search_knowledge",
        description = """
                在 AIHub 知识库中做语义检索，返回最相关的文档片段（含来源文档名与相似度）。
                适用于「查资料 / 找依据 / 引用原文」这类需求……
                返回的每条结果都带相似度百分比：低于 60% 通常意味着知识库里没有相关内容，
                此时应如实告知用户「知识库中未找到相关内容」，不要强行作答。""")
public String searchKnowledge(
        @ToolParam(description = "知识库 ID，可先用 aihub_list_knowledge_bases 获取")
        Long kbId,
        @ToolParam(description = "检索问题或关键词，用自然语言即可")
        String query,
        @ToolParam(description = "返回条数，默认 5，最多 20", required = false)
        Integer topK) {
```

同文件还有 `aihub_list_applications`（`#L54`）、`aihub_list_knowledge_bases`（`#L83`）、`aihub_ask`（`#L147`），共 17 个 `@Tool`。AI 服务内部还有一组进程内工具：`aihub-ai-service/.../infra/ai/tools/KnowledgeTools.java#L30-L32`（`@Tool(description=...)` + `@ToolParam`，RAG 检索工具）。

**常见误用**：
- **description 写成方法注释**："查询知识库方法"——模型不知道何时该用、传什么；本项目的写法是"何时用/何时不该用/结果怎么理解"三段说明书。
- **抛异常代替返回错误文本**：`AiHubTools.java#L25-L28` 注释原话："抛出去会变成 JSON-RPC error，模型的反应通常是直接放弃；而'鉴权未通过，请检查 API Key'这类提示能让它继续推理"——工具层把异常转成可读文本是 Agent 质量的关键设计。
- **返回复杂对象**：本项目刻意全部返回 String（`#L20-L24` 注释），因为 MCP 工具结果要能被任意客户端消费，纯文本最不容易错。

**面试怎么说**："`@Tool` 本质是'注解声明 + 反射生成 JSON Schema + MCP 协议发布'。我的经验是 description 的质量直接决定 Agent 质量——我会写清'何时用、何时不该用、结果怎么解读'，并且工具内部把异常转成给模型看的文本而不是抛错，让模型能换参数重试。"

---

## 6. MyBatis-Plus 组

### 6.1 `@TableName` —— 类与表的映射锚点

**作用与原理**：声明 DO 对应的数据库表名。MP 默认把驼峰类名转下划线表名（`SysApiKeyDO` → `sys_api_key_d_o`，错的），所以显式标注是自文档化 + 防猜错的正确姿势。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/entity/SysApiKeyDO.java#L33-L35
@Data
@TableName("sys_api_key")
public class SysApiKeyDO {
```

全项目 28 处，覆盖两个库的全部表：platform 库的 `sys_user`/`sys_role`/`sys_menu`/`sys_tenant`/`sys_api_key`/`ai_quota_*`，ai 库的 `ai_app`/`ai_knowledge_base`/`ai_event_outbox` 等。

**常见误用**：
- **表名靠默认映射猜**：`AiApiKeyDO` 这种带连续大写字母的类名映射结果反直觉，显式 `@TableName("ai_api_key")` 一锤定音。
- **DO 上挂业务方法**：DO 只该是"一行数据的影子"，行为放 Service/领域模型。

**面试怎么说**："DO 层我坚持两个显式：表名显式标注、不依赖驼峰猜测；字段映射依赖 MP 默认 camelToUnderline，所以全项目仅 1 处注释性提及 `@TableField`、0 处实际使用——命名规范统一后，默认映射就是最少的注解。"

### 6.2 `@TableId(type = IdType.ASSIGN_ID)` —— 分布式主键策略

**作用与原理**：声明主键与生成策略。`ASSIGN_ID` = 插入时由 MP 自动生成**雪花 ID**（64 位 = 时间戳 + 机器号 + 序列），趋势递增、不依赖数据库自增、天生适合分库分表。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/entity/SysApiKeyDO.java#L37-L39
/** 主键（雪花 ID） */
@TableId(type = IdType.ASSIGN_ID)
private Long id;
```

业务代码里甚至直接用 MP 的 `IdWorker` 预生成 ID 做关联插入——`AuthService.java#L168-L174`（注册流程先 `Long tenantId = IdWorker.getId()` 生成租户 ID，再把同值写进 `SysTenantDO.id` 和 `admin.setTenantId(tenantId)`），一次事务里主子表外键对齐不依赖"插入后回读自增键"。

**常见误用**：
- **分布式部署还用 `AUTO`（数据库自增）**：多实例写同一库没问题，但分库分表后自增键必然冲突；且自增 ID 可被外部枚举（越权遍历）。
- **手写 ID 却与 `@TableId` 策略打架**：要么让框架生成，要么像本项目一样统一走 `IdWorker` 预生成，不要混着来。

**面试怎么说**："为什么选雪花 ID 不选自增：第一，多租户 SaaS 数据量增长后要分库分表，自增键跨库必撞；第二，注册流程要在一个事务里写 5 张互相关联的表，`IdWorker` 预生成 ID 让主子表对齐不需要回读；第三，外部不可枚举。"

### 6.3 `@TableLogic`（重点）—— 软删的声明式实现

**作用与原理**：标注逻辑删除字段后，MP 对该表的 SQL 行为整体改写：
- `deleteById/delete(wrapper)` → 实际执行 `UPDATE ... SET deleted=1`；
- `select/selectById/selectCount` 等所有 MP 内置查询 → 自动追加 `WHERE deleted=0`；
- 全程零业务代码，"删除"语义从物理行消失变成标记位翻转，可审计、可恢复。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/entity/SysApiKeyDO.java#L71-L73
/** 逻辑删除标记：0=未删 1=已删（@TableLogic 自动维护） */
@TableLogic
private Integer deleted;
```

全项目 16 处 DO 都带它（`SysMenuDO.java#L67`、`SysRoleDO.java#L53`、`SysUserRoleDO.java#L38`、ai 库的 `AiAppDO.java#L31` 等）。删除动作的真实消费方：`SysUserService.java#L133-L143` 的 `delete()` 直接调 `userMapper.deleteById(userId)`，代码里看不到 `deleted` 字样——改写发生在框架层。唯一相关的业务代码是唯一性校验（`SysUserService.java#L91-L96` 按 `tenantId+username` 查存在数），因为自动追加的 `deleted=0` 让"已删用户名可复用"成为免费获得的行为。

**软删原理的完整链条**（面试展开版）：MP 启动时解析实体元数据建立 TableInfo（含 `@TableLogic` 字段）；SQL 注入器在生成 CRUD 模板时把逻辑删除条件编译进去；运行期对 `delete` 系列改写为 UPDATE、对 `select` 系列追加过滤。**改写范围是 MP 的内置方法与条件构造器**——这正是下一个用例的关键。

**常见误用**：
- **以为所有 SQL 都会被过滤**：`@Select` 手写原生 SQL **不会**自动追加 `deleted=0`！本项目源码注释就是警示牌——

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/user/mapper/SysUserMapper.java#L22-L25
 * deleted=0 手动带逻辑删除条件：@Select 原生 SQL 不会自动加 @TableLogic 过滤。
 */
@Select("select * from sys_user where tenant_id = #{tenantId} and username = #{username} and deleted = 0 limit 1")
SysUserDO selectByTenantAndUsername(Long tenantId, String username);
```

- **唯一索引与软删打架**：`uk_tenant_username` 唯一索引含已删行时，"删了再建同名"会撞索引——需要把 deleted 进索引键（如 `uk(tenant_id, username, deleted)`）或改用删除时间戳列。
- **统计口径分叉**：有的查询走 MP（自动过滤）有的走手写 SQL（忘了过滤），报表数字对不上——对策是代码评审把"手写 SQL 必带 deleted"列为红线。

**面试怎么说**："软删的原理是 MP 在 SQL 注入层做改写：delete 变 UPDATE、select 自动追加 deleted=0。但边界必须清楚——`@Select` 手写 SQL 不受管，我们项目登录查询就手动带了 deleted=0，注释还专门写了原因。另一个坑是唯一索引和软删的冲突，设计索引时要把删除标记纳入键。"

### 6.4 `@MapperScan` 与 `@Mapper` —— Mapper 接口的注册方式

**作用与原理**：Mapper 是**没有实现类的接口**，运行期由 MyBatis 生成 JDK 动态代理（这就是"为什么不写实现却能注入"）。注册方式两种：接口上逐个标 `@Mapper`，或启动类 `@MapperScan("包名")` 批量扫描。二选一即可，重复不报错但徒增噪音。

**项目中的真实用例**——本项目统一走 `@MapperScan`，全项目 `@Mapper` 仅 2 次：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/PlatformApplication.java#L38-L46
// 本服务的 Mapper 接口未标 @Mapper，必须显式扫描注册（漏掉会报
// "required a bean of type '...Mapper' that could not be found"）
@MapperScan({"com.aihub.platform.user.mapper",
        "com.aihub.platform.apikey.mapper",
        "com.aihub.platform.quota.mapper",
        "com.aihub.platform.system.mapper"})
@SpringBootApplication(scanBasePackages = "com.aihub")
public class PlatformApplication {
```

`aihub/aihub-ai-service/src/main/java/com/aihub/ai/AiServiceApplication.java#L47` 同款（`@MapperScan("com.aihub.ai.infra.persistence.mapper")`，注释原话："Mapper 是接口没有实现类，MyBatis 运行时动态代理生成实现"）。注意 `scanBasePackages = "com.aihub"` 扩大到 common 的原因（`#L44-L45` 注释）：让公共模块的 `GlobalExceptionHandler` 也被扫描注册，否则业务异常会变成原生 500 而不是统一响应体——组件扫描范围是跨模块协作的隐形契约。

**常见误用**：
- **`@MapperScan` 包路径写大不写小**：扫进无关包拖慢启动、误注册同名接口；本项目精确到 mapper 包。
- **以为加了 `@MapperScan` 还需每个接口再标 `@Mapper`**：冗余噪音，选一种并写进团队规范。

**面试怎么说**："Mapper 能注入是因为 JDK 动态代理——接口没有实现，MyBatis 运行期生成代理 Bean。我们统一 `@MapperScan` 精确扫描 mapper 包，不逐个标 `@Mapper`，注释里连'漏配时报什么错'都写清楚了。"

### 6.5 `@Select` / `@Insert` / `@Update` / `@Param` —— 注解 SQL 与参数命名

**作用与原理**：简单 SQL 可直接以注解写在 Mapper 接口方法上（本项目规范：复杂 SQL 用 XML 或 MP 条件构造器）。`#{param}` 是预编译占位符（防 SQL 注入），多参数方法必须用 `@Param("名")` 声明参数名供 SQL 引用——注意区别于 Spring MVC 的 `@RequestParam`，这是 MyBatis 的 `org.apache.ibatis.annotations.Param`。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/mapper/AiQuotaUsageMapper.java#L23-L33
@Insert("""
        insert into ai_quota_usage (id, tenant_id, app_id, dimension, period_key, used_value)
        values (#{id}, #{tenantId}, #{appId}, #{dimension}, #{periodKey}, #{amount})
        on duplicate key update used_value = used_value + #{amount}
        """)
int upsertConsume(@Param("id") long id,
                  @Param("tenantId") Long tenantId,
                  ...);
```

upsert（`INSERT ... ON DUPLICATE KEY UPDATE`）保证配额扣减原子性，并发下无"先查后插撞唯一键"竞态。统计查询的 `@Select` + `@Param` 组合见 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/mapper/AiUsageRecordMapper.java#L41-L42`（`selectOverview(@Param("tenantId") ..., @Param("from") ...)`），单参数免标注的对比见 `SysUserMapper.java#L25`（`selectByTenantAndUsername(Long tenantId, String username)` 未标 `@Param`——MyBatis 3.4.1+ 配合 `-parameters` 编译参数可用真实参数名，但**多参数场景显式 `@Param` 永远最稳**，本项目 30 处 `@Param` 集中在多参数方法上）。

**常见误用**：
- **`${}` 拼接用户输入**：字符串拼接直通 SQL 层=注入漏洞；本项目规范"永不用 `${}` 拼用户输入"（`SysUserMapper.java#L14-L16` 注释原话）。
- **复杂报表 SQL 硬塞注解**：text block 也救不了 50 行 SQL 的可维护性，该进 XML。
- **多参数不加 `@Param`**：SQL 里 `#{tenantId}` 解析不到参数名，报"Parameter not found"且报错信息不指向根因。

**面试怎么说**："注解 SQL 我只给简单语句用，复杂统计进 XML。两条铁律：`#{}` 预编译、永不用 `${}` 拼用户输入；多参数必标 `@Param`。项目里配额扣减用的 upsert 注解 SQL 是个亮点——一句 `ON DUPLICATE KEY UPDATE` 解决高并发累加。"

### 6.6 DO 与注解的协作全貌

以 `SysApiKeyDO` 为例，一个 DO 类上的注解分工：`@Data`（样板代码，§3.1）+ `@TableName`（类↔表，§6.1）+ `@TableId(ASSIGN_ID)`（主键策略，§6.2）+ `@TableLogic`（软删，§6.3）。**没有 `@TableField`**——因为字段名驼峰与列名下划线靠 MP 默认映射对齐（`SysApiKeyDO.java#L27-L28` 注释原话："MP 默认 camelToUnderline 映射，无需 @TableField"）。租户字段 `tenantId` 不需要任何注解，隔离由两层机制保证：业务层每查询带 `TenantContext.requireTenantId()` 条件（§9.2），框架层有可开关的租户 SQL 兜底拦截器（§5.3、§6.4 的 `TenantSqlGuardConfig` / `MybatisPlusConfig`）。

---

## 7. Web 与校验组

### 7.1 `@Valid` —— 让校验注解长出牙齿

**作用与原理**：触发参数的 Bean Validation 级联校验。放在 `@RequestBody` 参数上时，Spring MVC 在绑定期执行 VO 字段上的约束注解（`@NotBlank`/`@NotNull`/`@Size`…），失败抛 `MethodArgumentNotValidException`，被全局异常处理器转成友好提示。校验声明在 VO 字段上、执行点在 Controller 入口——"声明与执法分离"。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java#L51-L55
@RequirePermission("system:user:create")
@PostMapping
public R<Long> create(@Valid @RequestBody SysUserCreateReqVO reqVO) {
    return R.ok(userService.create(reqVO));
}
```

SSE 流式接口同样校验入参（`aihub/aihub-ai-service/src/main/java/com/aihub/ai/web/ChatController.java#L103-L104`：`chatSse(@Valid @RequestBody ChatRequest request)`）——流式接口更要入口挡脏数据，因为处理已提交到异步线程，事后报错用户端只剩断流。

**常见误用**：
- **`@RequestBody` 后面忘加 `@Valid`**：VO 上的 `@NotBlank` 全部变成摆设，静默放行空字段。
- **以为 `@Valid` 能替代业务校验**：格式校验（非空/长度/格式）归 `@Valid`，业务规则（用户名重复、租户码保留字）归 Service——本项目 `SysUserService.create()` 里仍手写正则与唯一性检查，两层校验职责分明。
- **嵌套对象校验漏级联**：VO 里嵌 VO 不加 `@Valid` 字段注解就不会深入校验。

**面试怎么说**："我项目的校验分两层：入口用 `@Valid` + 字段约束做格式校验，错误消息直接面向用户；业务校验（唯一性、归属、保留字）在 Service。特别提醒 `@Valid` 忘写是 silent bug——所有约束静默失效，所以我把 `@Valid @RequestBody` 写成一个不可分割的组合。"

### 7.2 `@NotBlank` —— 最常用的字段约束

**作用与原理**：Bean Validation 约束注解，要求字符串非 null 且去掉首尾空白后长度 > 0（区别：`@NotNull` 只挡 null、空串照过；`@NotEmpty` 挡 null 和空串但允许 `"  "`）。`message` 属性定制提示语，经 §7.4 的处理器直达前端。

**项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/SysUserCreateReqVO.java#L8-L22
/** 新增子用户请求 VO（Service 层负责密码强度等业务校验） */
@Data
public class SysUserCreateReqVO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 昵称，留空默认取用户名 */
    private String nickname;

    @NotBlank(message = "密码不能为空")
    private String password;
```

注意 `nickname` 故意不加约束——"留空默认取用户名"是合法业务语义（`SysUserService.java#L102-L103` 有兜底逻辑），**该空就得空**。

**常见误用**：
- **`@NotBlank` 用在非字符串字段**：编译都过不了（约束有类型限定），应该用 `@NotNull`/`@Min` 等。
- **提示语写技术黑话**："parameter username violated constraint"——提示语是给用户看的，本项目全部人话："用户名不能为空"。
- **只在 VO 校验就认为数据可信**：VO 校验只覆盖"前端来的路"，Service 内部调用链（如 outbox 重放的请求）不过 Controller，关键规则必须在 Service 再验一次。

**面试怎么说**："我选约束注解时先分清三兄弟：`@NotNull`、`@NotEmpty`、`@NotBlank`——空白字符串在用户输入场景几乎都该被拒，所以表单字段我用 `@NotBlank`；但'可选字段'一个注解都不加，把'留空取默认值'的语义留给 Service。"

### 7.3 `@RestControllerAdvice` —— 全局横切的响应加工厂

**作用与原理**：`@ControllerAdvice` + `@ResponseBody`。定义一个对所有 `@RestController` 生效的增强器切面：本类里的 `@ExceptionHandler` 方法成为全局异常出口。Controller/Service 里只管"该抛就抛"，异常的"翻译成统一响应体"集中在一处。

**项目中的真实用例**：

```java
// aihub/aihub-common/src/main/java/com/aihub/common/exception/GlobalExceptionHandler.java#L29-L41
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)   // 声明本方法处理哪种异常；Spring 按类型就近匹配
    public R<Void> handleBiz(BizException e) {
        // 业务异常属于正常业务分支（如密码错误），记 error 级别会污染告警
        return R.fail(e.getCode(), e.getMessage());
    }
```

它能被所有服务共享的原因藏在启动类的扫描范围里：`PlatformApplication.java#L44-L46` 的 `@SpringBootApplication(scanBasePackages = "com.aihub")`——把扫描范围从默认包根扩大到 `com.aihub`，common 模块的这个处理器才能被注册。注释原话："否则 BizException 会变成原生 500 而不是 {"code":10001,...} 的统一响应体"。

**常见误用**：
- **以为 Advice 包打天下**：`GlobalExceptionHandler.java#L25-L26` 注释明确警告"网关（WebFlux）过滤器里抛的异常不走这里，网关需要单独处理"——`@RestControllerAdvice` 只作用于 Servlet MVC 的 Controller 调用链，过滤器/异步线程不在其中。
- **Advice 里所有异常都 `log.error`**：业务异常（密码错误）是正常分支，全 error 会污染告警——本项目 `handleBiz` 有意不写日志，注释说明了理由。

**面试怎么说**："全局异常处理的价值是让业务代码'只抛不接'。我项目在 common 模块放一个 `@RestControllerAdvice`，靠启动类 `scanBasePackages="com.aihub"` 让所有服务共享。边界我特意确认过：它管不到 WebFlux 网关过滤器和异步线程，那些要另行处理。"

### 7.4 `@ExceptionHandler` —— 异常到响应的映射表

**作用与原理**：声明本方法处理哪类异常，Spring 按类型**就近原则**匹配（先找最具体的类型，找不到再逐级上溯父类）。一个方法可声明多种异常；放在 `@RestControllerAdvice` 类里即成全局处理器。

**项目中的真实用例**——三层兜底网（同一文件）：

```java
// aihub/aihub-common/src/main/java/com/aihub/common/exception/GlobalExceptionHandler.java
@ExceptionHandler(BizException.class)                                                    // L37：业务异常 → 原样透出业务码
public R<Void> handleBiz(BizException e) { ... }

@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})          // L48：校验异常 → 取第一条字段错误提示
public R<Void> handleValidate(Exception e) { ... }

@ExceptionHandler(Exception.class)                                                       // L64：兜底 → 日志记完整堆栈，前端只给笼统提示
public R<Void> handleException(Exception e) {
    log.error("未处理异常", e);
    return R.fail(ResultCode.SYSTEM_ERROR);
}
```

兜底处理的安全考量（`#L59-L63` 注释）："完整堆栈只写日志，给前端的永远是笼统的'系统内部错误'，避免把数据库表名、类名、模型地址等内部信息泄露出去。"

**常见误用**：
- **兜底方法把 `e.getMessage()` 直接返回给前端**：内部信息（SQL 片段、类名）泄露，本项目只返回 `ResultCode.SYSTEM_ERROR` 的固定文案。
- **在 `@ExceptionHandler` 里再抛异常**：等于异常出口二次崩溃，结果是不确定的错误页。
- **期望它处理过滤器里的异常**：见 §7.3 边界。

**面试怎么说**："异常映射我按'就近匹配'设计三层：BizException 透业务码、校验异常取字段提示、Exception 兜底。安全规则是'内情进日志、外情进响应'——堆栈永远只写日志，前端拿到的是固定错误码。"

### 7.5 Jackson 组：`@JsonInclude` 与 `@JsonIgnoreProperties`

**`@JsonInclude(NON_NULL)`**

- **作用与原理**：序列化时跳过 null 字段，让 JSON 更干净。
- **项目中的真实用例**：

```java
// aihub/aihub-common/src/main/java/com/aihub/common/result/R.java#L37-L38
@JsonInclude(JsonInclude.Include.NON_NULL)  // 转 JSON 时跳过值为 null 的字段（如失败时不会出现 "data": null）
public class R<T> implements Serializable {
```

放在统一响应体上意味着全项目所有接口受益——失败响应里永远不会出现 `"data": null`。
- **常见误用**：只在个别 VO 上加，导致响应结构漂移；正确做法是像本项目一样钉在 `R` 上一次性生效。
- **面试怎么说**："统一响应体上钉 `NON_NULL`，前端判断数据存在性只需判 key 是否出现，不用再判 null——契约层面的洁癖，一处声明全局生效。"

**`@JsonIgnoreProperties(ignoreUnknown = true)`**

- **作用与原理**：反序列化时忽略 JSON 里多余的字段。服务端加字段、旧客户端不认识也不会炸。
- **项目中的真实用例**：MCP 模块的契约镜像类——`aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/KbBrief.java#L14`、`AppBrief.java#L14`、`ApiEnvelope.java#L17-L18`（服务端 `R<T>` 的客户端镜像，`#L9-L15` 注释解释了为何不复用服务端类："服务端重构内部 DTO 时不会连带打破 MCP 侧"）。
- **常见误用**：客户端 DTO 不加它——服务端一加字段，旧客户端全线反序列化失败。跨服务消费方向的 DTO 必须加。
- **面试怎么说**："序列化方向和反序列化方向的注解是两件事：响应体管'输出干净'（NON_NULL），消费方 DTO 管'输入宽容'（ignoreUnknown）。我们把后者用在 MCP 客户端镜像类上，让服务端契约演进出向兼容。"

### 7.6 `@DateTimeFormat` —— 入参日期绑定

- **作用与原理**：告诉 Spring 如何把查询参数里的字符串解析成日期时间类型（`@RequestBody` JSON 走 Jackson 配置，不走它）。
- **项目中的真实用例**：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/controller/ApiKeyController.java#L106-L107
 * @DateTimeFormat：告诉 Spring 前端传的是 ISO 格式字符串（如 2026-12-31T23:59:59），自动转 LocalDateTime
@DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
```
- **常见误用**：手写 `pattern = "yyyy-MM-dd HH:mm:ss"` 与前端格式漂移；用 ISO 标准格式（`iso = ISO.DATE_TIME`）双方零沟通成本。
- **面试怎么说**："GET 参数的时间字段用 `@DateTimeFormat(iso = DATE_TIME)` 绑 ISO 字符串，POST 体里的时间由 Jackson 全局配置——两套入口两套转换，别混。"

---

## 8. `@Transactional` 失效场景（重点章，结合项目代码）

全项目 7 处 `@Transactional`，**无一例外都写了 `rollbackFor = Exception.class`**：

| 位置 | 方法 | 事务保护的动作 |
|---|---|---|
| `AuthService.java#L132` | `register` | 注册写 5 张表（租户/用户/角色/角色菜单/用户角色） |
| `SysUserService.java#L81` | `create` | 建用户 + 级联分配角色 |
| `SysUserService.java#L133` | `delete` | 解绑角色 + 删用户（两表一致） |
| `SysUserService.java#L158` | `assignRoles` | 整批替换用户角色绑定 |
| `SysRoleService.java#L109` | `delete` | 删角色菜单授权 + 删角色 |
| `SysRoleService.java#L127` | `assignMenus` | 先删旧授权再插新授权（单事务） |

**原理一句话**：`@Transactional` 靠 AOP 代理实现——调用方实际调用的是代理对象，代理在目标方法前开启/加入事务、方法正常返回则提交、按规则回滚。**所有"失效"都源于一件事：代码没有走代理，或走代理时代理没按你以为的规则回滚。**

### 8.1 失效场景一：默认只回滚 RuntimeException —— 项目的统一防线

**原理**：`rollbackFor` 缺省值是 `RuntimeException + Error`。抛出受检异常（如自定义 checked 异常、`IOException`）时**事务照样提交**——这是 Spring 团队的设计哲学（受检异常被视为"可恢复的业务分支"），但常与直觉相反。

**项目中的真实规避**：7 处全部显式 `rollbackFor = Exception.class`。以注册流程为例：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/AuthService.java#L126-L133
 * <p><b>@Transactional（重要）：</b>本方法要写 5 张表（租户/用户/角色/角色菜单/用户角色）。
 * 事务保证"要么全部成功、要么全部回滚"——比如授权菜单时失败，不能留下
 * "有租户有管理员但没有角色"的脏数据。
 */
@Transactional(rollbackFor = Exception.class)
public AuthRegisterRespVO register(AuthRegisterReqVO reqVO) {
```

方法内部抛的 `BizException` 是 `RuntimeException` 子类，即使不写 `rollbackFor` 也会回滚——但**规范不依赖每个方法的异常谱系**：万一将来有人把某个中间步骤改成抛受检异常，缺省配置就是静默脏数据。统一 `rollbackFor = Exception.class` 是"用固定写法消除记忆负担"的团队纪律。

**面试怎么说**："默认只回滚非受检异常，这是 `@Transactional` 第一大坑。我的做法是全项目统一 `rollbackFor = Exception.class`，不逐个方法判断异常类型——7 处事务注解一个不例外，规范的一致性比局部聪明更重要。"

### 8.2 失效场景二：自调用绕过代理 —— 项目里"正确但危险"的真实案例

**原理**：同类内 `this.methodB()` 调用不经过代理对象，`methodB` 上的 `@Transactional` **静默失效**（没有代理就没有事务拦截）。

**项目中的真实用例**（先看代码再讲为什么它没出事）：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java#L81-L110
@Transactional(rollbackFor = Exception.class)
public Long create(SysUserCreateReqVO reqVO) {
    ...                                     // userMapper.insert(user);  L106
    if (reqVO.getRoleIds() != null && !reqVO.getRoleIds().isEmpty()) {
        assignRoles(userId, reqVO.getRoleIds());   // L108：同类内调用！
    }
    return userId;
}

// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java#L158-L159
@Transactional(rollbackFor = Exception.class)
public void assignRoles(Long userId, List<Long> roleIds) { ... }
```

`create()` 第 108 行直接调 `this.assignRoles()`——**内层的 `@Transactional` 没有生效**（不是 REQUIRED 加入，而是压根没被拦截）。但结果依然正确，因为外层 `create()` 的事务已经打开，内层所有 SQL 自动加入同一个物理事务。**它今天没出事，是因为外层恰好有事务**；一旦有人重构时删掉 `create()` 上的注解，`assignRoles` 的注解不会"顶上来"，删+插就变成无事务裸奔——这是最隐蔽的定时炸弹形态：失效不报错、不写日志、只在特定重构后才爆炸。

**项目的规避纪律**（评审红线，源码事实支撑）：
1. 事务注解**只标对外入口方法**（Controller 直达的 Service 方法），内部协作方法一律不标、依赖外层事务——本项目的 7 处注解全部是"对外入口"级别；
2. 确需内部方法独立事务时，**不写自调用**：把方法挪到另一个 Service 类（走代理），或注入自身代理（`self` 字段，类上 `@Lazy`）。项目选择了更朴素的方案：能靠外层事务就靠外层，不制造嵌套注解。

**面试怎么说**："自调用失效是 `@Transactional` 最经典的坑，我在项目里专门分析过：`SysUserService.create()` 里调 `this.assignRoles()`，内层注解其实没生效，只是外层事务兜住了——这类'碰巧正确'最危险。我的纪律是：事务只标对外入口，内部方法不重复标注；真要独立事务就拆类走代理，绝不自调用。"

### 8.3 失效场景三：非 public 方法 —— 代理的天然边界

**原理**：JDK 动态代理只能拦接口方法（天生 public）；CGLIB 代理虽能子类化类，但 Spring 的事务拦截器只处理 public 方法（Spring Framework 6/Boot 3 起对 protected/package-private 上的 `@Transactional` 直接**启动报警并忽略**）。

**项目中的真实规避**：7 处注解方法全部 public（`register`/`create`/`delete`/`assignRoles`/`assignMenus` 等）。对比佐证：同类的**非入口**私有方法（如 `SysUserService.java#L53` 的 `private static toRespVO`、`SysRoleService.java#L144` 的 `private mustGetOwn`）从不标事务——它们要么是纯转换，要么运行在外层事务内。

**常见误用**：给包级可见的"内部批量方法"标 `@Transactional` 以为有事务——注解静默无效。验证方法很简单：在方法内 `TransactionSynchronizationManager.isActualTransactionActive()` 打个日志，或直接看启动警告。

**面试怎么说**："事务代理的边界是 public 方法。我评审时看到非 public 方法带 `@Transactional` 会直接打回——Boot 3 起这种情况启动就有警告，但很多人不看来这种日志。"

### 8.4 失效场景四：异常被吞 —— 回滚的触发器被拆掉了

**原理**：回滚的触发条件是"异常**穿过**代理边界"。业务代码里 `try-catch` 把异常吃掉、或 catch 后只记日志不重抛，代理看到的就是一次"正常返回"——**事务照常提交**，脏数据落库。

**项目中的真实规避**——异常管线的分工恰好保证了"该抛的都能穿出去"：

- Service 层"该抛就抛"：`AuthService.java#L103-L108`（密码错误抛 `BizException`，不 catch）；
- 全局处理器在**事务之外**接力：`GlobalExceptionHandler` 的调用时机是 Controller 调用链回溯阶段，此时事务代理早已因异常回滚完毕——**顺序是"先回滚、再翻译响应"**，两者不会互相干扰；
- 真正需要"失败可继续"的场景（outbox 轮询），项目把它设计在**事务边界之外**：`EventOutboxDispatcher.java#L88-L98` 的 `pollSafely()` 明确注释"任何异常都只记日志，绝不让调度线程死掉"——轮询本身无事务，只有单条投递的内部逻辑才谈事务，"吞异常"发生在无事务层，不会造成"以为回滚了其实提交了"的错觉。

**常见误用**：
```java
@Transactional
public void xx() {
    try { doWrite(); } catch (Exception e) { log.error("失败", e); }   // 吞了 → 提交！
}
```
正确姿势三选一：catch 后重抛；catch 后手动 `TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()`；或者根本不 catch 让它穿出去（本项目默认）。

**面试怎么说**："回滚的触发器是'异常穿过代理边界'。我们项目的管线是 Service 只抛不接、全局 Advice 在事务外兜底翻译响应，所以'异常被吞'天然没有发生土壤；唯一的 catch 在无事务的轮询层，语义是'这轮失败下轮再来'。"

### 8.5 失效场景五（微服务版）：跨服务调用没有事务可传播

**原理**：`@Transactional` 的边界是**一个数据库连接**。Feign 调用是独立 HTTP 请求、打到另一个服务的独立连接——调用方回滚**不可能**回滚被调方的已提交数据。这是微服务里的"事务失效"终极形态。

**项目中的真实规避**——架构级约束 + 补偿模式：

```java
// aihub/aihub-api/src/main/java/com/aihub/api/client/PlatformClient.java#L33-L35
 * <p><b>架构约束（见 docs/07-微服务与中间件设计.md 第 2 节）：</b>
 * 只允许「租户校验 / 配额扣减 / 审计上报」三类跨服务调用，且均为无事务调用。
```

AI 服务调平台服务扣配额、上报用量时，不指望分布式事务，而是：调用失败时把用量**落 outbox 表**（本地事务保证不丢），由 `EventOutboxDispatcher`（`aihub-ai-service/.../infra/outbox/EventOutboxDispatcher.java`）后台轮询重试补偿——这就是**事务性发件箱（Transactional Outbox）模式**的落地。接口幂等性由 `requestId` 保证（`PlatformClient.java#L48` 注释："幂等，由 requestId 保证——网络重试导致同一 requestId 重复到达时只扣一次"）。

**面试怎么说**："微服务里 `@Transactional` 管不到别人家数据库。我们的解法是三件套：收紧跨服务调用面（只许三类且声明无事务）、本地 outbox 表 + 后台补偿（不丢）、requestId 幂等去重（重试不重复扣）。这比引 Seata 轻量得多，也够用。"

### 8.6 失效场景六：多线程 —— 事务上下文不跨线程

**原理**：事务绑定在 ThreadLocal 的连接资源上，`@Transactional` 方法内另开线程执行写操作，新线程拿到的是**另一个连接**，不在事务内。

**项目中的真实用例**——SSE 流式接口的线程切换：

```java
// aihub/aihub-ai-service/src/main/java/com/aihub/ai/web/ChatController.java#L107-L116
// 流式任务在独立线程执行：必须把链路 ID 带过去，否则日志断链
streamExecutor.submit(TraceContext.wrap(() -> {
    StreamSink sink = new SseStreamSink(emitter);
    try {
        chatAppService.chatStream(turn, sink);
    } catch (Exception e) { ... }
}));
```

`chatStream` 跑在线程池线程里——如果它在 Service 层标了 `@Transactional` 并期待与提交线程同事务，就会踩坑。项目的处理：流式对话**逐事件落库**（对话/消息记录在提交线程或各自短事务中完成），流式输出过程本身不设计成长事务；`TraceContext.wrap()` 显式跨线程传递的是**链路 ID**（靠手动包装而非 ThreadLocal 自动传播）——连 ThreadLocal 都不指望自动跨线程，事务更是如此。

**面试怎么说**："事务和 ThreadLocal 都不跨线程。SSE 场景我把流式执行放到独立线程，traceId 用包装器手动带过去——写库各自短事务，不搞'一个大事务包住整个流式输出'，那既锁库又失效。"

---

## 9. 自定义注解实战与设计示例

### 9.1 项目真实存在的自定义注解：`@RequirePermission`

本项目**有**自定义注解——RBAC 权限校验注解 `@RequirePermission`，25 处使用，是"注解三要素"（声明/元注解/消费）的完整闭环示范。

**第一要素：声明**（注意 `@Target`/`@Retention` 这两个元注解本身也在项目扫描中出现，各 1 次，就在这个文件里）：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/annotation/RequirePermission.java#L26-L31
@Target(ElementType.METHOD)          // 只能标在方法上
@Retention(RetentionPolicy.RUNTIME)  // 运行期可反射读取（拦截器靠这个）
public @interface RequirePermission {

    /** 需要的权限标识，如 "system:user:create" */
    String value();
}
```

两个元注解的选型理由：
- `@Target(METHOD)`：权限校验的粒度是"接口"，贴类上会变成"整个 Controller 一个权限"，过粗。若将来要类级兜底，再扩展 `@Target({METHOD, TYPE})`（源码注释 `PermissionInterceptor.java#L45` 预留了这个演进："方法级优先，可再扩展类级兜底"）。
- `@Retention(RUNTIME)`：**必须**——消费方是运行期的拦截器反射读取。写 `SOURCE` 或 `CLASS` 注解在字节码里活着但反射读不到，拦截器永远拿 null，权限校验静默全放行——这是自定义注解最致命的静默错误。

**第二要素：使用**——Controller 方法上声明权限标识（命名规范 `模块:资源:动作`）：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java#L44-L48
@RequirePermission("system:user:query")
@GetMapping("/page")
public R<Page<SysUserRespVO>> page(SysUserPageReqVO pageReqVO) {
    return R.ok(userService.page(pageReqVO.getPageNo(), pageReqVO.getPageSize(), pageReqVO.getKeyword()));
}
```

25 处使用覆盖 `system:user:*`、`system:role:*`、`system:menu:*` 等管理接口；**个人信息接口刻意不标**（`UserController.java#L96-L109`）——"没有注解 = 只要求登录态"是宽松默认策略（`PermissionInterceptor.java#L28-L29` 注释："登录即认证通过，敏感接口才逐个标权限"）。

**第三要素：消费**——拦截器在请求进 Controller 前反射读取：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/interceptor/PermissionInterceptor.java#L38-L59
@Override
public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                         Object handler) {
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

**注册**（拦截器不会自动生效，要"挂"到 MVC 上并排在租户上下文之后）：

```java
// aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/WebMvcConfig.java#L55-L61
// 权限校验：标注了 @RequirePermission 的接口才做 RBAC 校验；
registry.addInterceptor(permissionInterceptor)
        .addPathPatterns("/**")
        .excludePathPatterns("/auth/**", "/actuator/**", "/error", "/internal/**",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
        .order(2);
```

执行顺序设计（`WebMvcConfig.java#L18-L25` 注释）：`TraceId(0) → Tenant(1) → Permission(2)`——**权限校验依赖租户/用户上下文**（`TenantContext.requireTenantId()`），所以 order 必须排在租户拦截器之后；排除名单把登录/内部契约/文档端点划出信任边界。

**四段式小结**：
- **作用与原理**：声明式权限——注解只声明"需要什么权限"（数据），拦截器负责"怎么校验"（行为），权限集合查询 + Caffeine 缓存在 `PermissionService`，授权变更时主动失效。
- **项目中的真实用例**：如上，全链路 `RequirePermission.java → UserController.java#L44 → PermissionInterceptor.java#L46 → WebMvcConfig.java#L57`。
- **常见误用**：① `@Retention` 写错 → 注解静默失效全放行；② 忘注册拦截器 → 注解成了装饰品；③ 顺序排错（权限校验先于租户解析）→ `requireTenantId()` 永远抛"租户上下文缺失"；④ 在 exclude 名单里误加敏感路径 → 接口裸奔。
- **面试怎么说**："我自研了一个轻量权限注解，对标 `@PreAuthorize` 的思想但零依赖：`@RequirePermission("system:user:create")` 标方法，拦截器 `getMethodAnnotation` 反射读取，查用户→角色→角色菜单→权限标识链路后比对。两个设计决策值得说：默认放行（登录即可，敏感接口逐个标）是宽松策略的取舍；拦截器 order 排在租户解析之后是因为校验依赖 ThreadLocal 上下文——注解系统的难点从来不是定义注解，而是消费时序。"

**为什么选拦截器而不是 AOP 切面**（面试必追问）：Controller 方法调用前校验是"Web 层关注点"，`HandlerInterceptor` 天然拿到 `HandlerMethod` 与 `getMethodAnnotation`，还能参与 MVC 的排除路径配置；AOP 切面也能做（切 `@annotation(...)`），但拿到的是方法反射对象、与 MVC 的路径排除体系脱节。**拦截器方案还天然避免了 AOP 的代理边界问题**（§8.2 的自调用坑在拦截器语境不存在）。

### 9.2 设计示例：`@CurrentTenant` 参数解析注解（⚠️ 设计示例，非现有代码）

项目多租户的取值现状是**在 Controller/Service 里显式调用** `TenantContext.requireTenantId()`（真实代码如 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/controller/ApiKeyController.java#L66`、`aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/controller/UsageController.java#L39`）。这是把"横切数据"变成"显式依赖"的**重构设计示例**：自定义一个参数注解，让租户 ID 直接作为方法参数注入。**以下是设计稿，项目中不存在这份代码**；命名用 `@CurrentTenant` 而非 `@TenantContext`——因为 `TenantContext` 已是 aihub-common 里真实存在的 ThreadLocal 工具类（`aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantContext.java`），重名会造成混淆。

**① 注解定义**（新增文件：`aihub-common/.../tenant/CurrentTenant.java`，设计稿）：

```java
package com.aihub.common.tenant;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 把当前租户 ID 绑定到 Controller 方法参数上（设计示例，非现有代码）。
 * 值来源与 TenantContext.requireTenantId() 完全一致：
 * 网关从 JWT 解析 → X-Tenant-Id 请求头 → TenantResolveInterceptor 写入 ThreadLocal。
 */
@Target(ElementType.PARAMETER)      // 只能贴在参数上
@Retention(RetentionPolicy.RUNTIME) // 解析器运行期反射读取
@Documented
public @interface CurrentTenant {

    /** true=租户上下文缺失时抛 TENANT_MISMATCH；false=注入 null，由业务自行判断 */
    boolean required() default true;
}
```

选型说明：`Target(PARAMETER)` 粒度最小；`Retention(RUNTIME)` 同 §9.1 的理由；`required` 属性给"登录态可选"的接口（如公开统计页）留活口，默认值取最严。

**② 解析器**（新增文件：`aihub-common/.../tenant/CurrentTenantArgumentResolver.java`，设计稿）：

```java
package com.aihub.common.tenant;

import com.aihub.common.exception.BizException;
import com.aihub.common.result.ResultCode;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * @CurrentTenant 的"执法者"（设计示例，非现有代码）。
 * Spring MVC 在绑定 Controller 参数时回调本解析器，与 PermissionInterceptor
 * 的时序关系：拦截器 order(1) 租户解析先写入 ThreadLocal → 参数解析发生在其后 → 天然可读。
 */
public class CurrentTenantArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentTenant.class)
                && (parameter.getParameterType() == Long.class
                    || parameter.getParameterType() == long.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        CurrentTenant anno = parameter.getParameterAnnotation(CurrentTenant.class);
        Long tenantId = TenantContext.getTenantId();
        if (tenantId == null && anno != null && anno.required()) {
            // 与 TenantContext.requireTenantId() 同源同语义：缺失即视为越权风险
            throw new BizException(ResultCode.TENANT_MISMATCH, "租户上下文缺失");
        }
        return tenantId;
    }
}
```

**③ 注册**（改动点：现有 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/WebMvcConfig.java#L43` 的 `addInterceptors` 同级追加回调，设计稿示意）：

```java
@Override
public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new CurrentTenantArgumentResolver());
}
```

**④ 使用**（改造前 vs 改造后；改造后为设计稿）：

```java
// 改造前（现有真实代码）—— aihub/.../apikey/controller/ApiKeyController.java#L66
Long tenantId = TenantContext.requireTenantId();

// 改造后（设计示例）
@GetMapping
public R<Page<ApiKeyRespVO>> page(@CurrentTenant Long tenantId, ...) { ... }
```

**设计的四个收益**（也是面试讲解点）：
1. **显式依赖**：租户 ID 出现在方法签名里，单测 `new ApiKeyController(service)` 后直接传 `1L` 即可，不必操纵 ThreadLocal——对比 `TenantContext.set(7L, 9L)` 那种测试前的状态铺垫（真实存在于 `MybatisPlusConfigTest.java#L34`）；
2. **时序安全**：参数解析发生在拦截器链之后，ThreadLocal 一定已写入；而 Service 深处的 `requireTenantId()` 在异步线程（SSE 的 `streamExecutor`）里会拿到 null——参数注入在 Controller 主线程完成取值再随参数下传，天然免疫跨线程丢失；
3. **职责归位**：`required=false` 的语义在注解属性上自我描述，不需要每个业务方法手写"取不到怎么办"；
4. **复用**：实现放在 aihub-common，所有 Servlet 服务共享（与 `TenantResolveInterceptor` 同住）。

**设计的边界**（同样要讲）：参数注入只覆盖"入口处取一次"的场景；Service 内部深处（如 outbox 后台线程）没有"参数"可注入，仍需 `TenantContext.getTenantId()` 或显式传参——所以它是**补充**而非替代。这个取舍与 Feign 跨服务透传（`aihub/aihub-api/src/main/java/com/aihub/api/config/FeignTenantConfig.java#L46-L53` 在出站请求里重读 `TenantContext` 并塞头）共同构成完整的租户传递链。

---

## 10. 面试高频问答 10 连

**Q1：注解的本质是什么？`@Override` 和 `@Component` 的消费时机有何不同？**
A：注解是继承 `Annotation` 接口的元数据标记，本身无行为，语义由消费者赋予。`@Override` 是 `SOURCE` 级，消费发生在编译期（编译器做契约检查，class 文件里不存在）；`@Component` 是 `RUNTIME` 级，消费发生在启动期（Spring 类路径扫描注册 BeanDefinition）。本项目三种消费方式齐全：Lombok 编译期改 AST、Spring 启动期扫描、`@RequirePermission` 运行期被拦截器反射读取。

**Q2：为什么你们项目全用构造器注入，`@Autowired` 只出现在测试里？**
A：`@RequiredArgsConstructor` + final 字段 + Spring 4.3 单构造器自动注入的组合。四点优势：final 不可变；缺依赖启动即失败（fail-fast）；单测可直接 new 不起容器；循环依赖启动报错逼出好设计。字段注入的 NPE 会推迟到生产首次调用、还掩盖循环依赖。

**Q3：`@Value` 和 `@ConfigurationProperties` 怎么选？Nacos 热更新为什么后者能生效？**
A：一次性组装参数用 `@Value`（项目里全在构造器参数上），可调优的参数组用 `@ConfigurationProperties`。热更新机制：Nacos 变更触发 RefreshEvent → `ConfigurationPropertiesRebinder` 对 Bean 整体重新绑定（走 setter）→ `@Value` 无此待遇只绑一次。代价：Properties 类必须保持可变 JavaBean，record 会静默失去热更——我把这个约束写进了 AgentProperties 的 Javadoc。

**Q4：`@Transactional` 原理？说四个失效场景及你们项目的规避。**
A：AOP 代理在方法前后管理事务，失效都源于"没走代理或没触发回滚规则"。四场景：① 默认只回滚非受检异常→项目 7 处统一 `rollbackFor = Exception.class`；② 自调用绕代理→事务只标对外入口，内部方法靠外层事务；③ 非 public 方法→全部 public；④ 异常被吞→Service 只抛不接，全局 Advice 在事务外兜底。加分项：跨服务调用天然无事务，用 outbox 补偿 + requestId 幂等替代分布式事务。

**Q5：你们有个自调用调用链没出问题，为什么还要说它危险？**
A：`SysUserService.create()`（@Transactional）内部 `this.assignRoles()`（也标了 @Transactional）。内层注解实际失效——但外层事务开着，内层 SQL 加入同一物理事务，结果碰巧正确。危险在于：若重构删掉外层注解，内层不会"顶上"，删+插变裸奔。失效不报错、只在特定重构后爆炸，这是最隐蔽的坑。

**Q6：`@TableLogic` 软删原理？什么情况下会失效？**
A：MP 在 SQL 注入层改写：delete 变 `UPDATE deleted=1`，select 自动追加 `WHERE deleted=0`。失效边界：`@Select` 手写原生 SQL 不受管——我们登录查询手动带 `deleted=0`，注释写明原因。另一个坑：唯一索引含已删行会让"删后重建同名"撞索引，索引键要纳入删除标记。

**Q7：自定义权限注解怎么实现？为什么选拦截器不选 AOP？**
A：`@Target(METHOD)` + `@Retention(RUNTIME)` 声明，Controller 方法标注 `@RequirePermission("system:user:create")`，拦截器 `preHandle` 里 `handlerMethod.getMethodAnnotation()` 反射读取，查用户→角色→菜单→权限标识后比对，无权限抛 FORBIDDEN。选拦截器因为它是 Web 层关注点：天然拿 HandlerMethod、能配 excludePathPatterns、参与 order 时序（排在租户解析之后，因为校验依赖 ThreadLocal 上下文），还避开 AOP 自调用问题。

**Q8：`@MapperScan` 和 `@Mapper` 什么关系？Mapper 是接口为什么能注入？**
A：两种注册方式选一即可，本项目统一 `@MapperScan` 精确扫描 mapper 包（全项目 `@Mapper` 仅 2 次）。能注入是因为 MyBatis 启动时为接口生成 JDK 动态代理 Bean——这就是"没有实现类却能构造器注入"的原理，本质和 Feign 客户端接口是同一套动态代理思路。

**Q9：`@Tool`/`@ToolParam` 是给谁看的？为什么说 description 决定 Agent 质量？**
A：给大模型看的。启动期框架反射方法签名生成 JSON Schema 经 MCP 协议发布；运行期模型完全依据 name/description/参数描述决定"调不调、怎么调、传什么参"。我们的工具 description 按"何时用/何时不该用/结果怎么解读"写说明书，工具内部把异常转成给模型看的文本而不是抛 JSON-RPC error——让模型能换参数重试而不是直接放弃。

**Q10：`@RestControllerAdvice` 的边界在哪？**
A：只管 Servlet MVC 的 Controller 调用链。三个不管：WebFlux 网关过滤器异常（网关单独处理，我们源码注释明确写了）；拦截器抛出的异常它能接（在 MVC 流程内）但异步线程（SSE 线程池）里的异常接不到；过滤器链的 401/403 语义另行处理。理解边界比记住用法重要——`@RestControllerAdvice` 不是全局异常保险箱，是 MVC 调用链的异常出口。

---

## 附：本文引用文件自检清单

以下路径均在文中被引用，且已用 PowerShell `Test-Path` 逐一验证存在（相对 `e:\微服务\javaAI\javaai\`）：

```
aihub/aihub-common/src/main/java/com/aihub/common/result/R.java
aihub/aihub-common/src/main/java/com/aihub/common/exception/GlobalExceptionHandler.java
aihub/aihub-common/src/main/java/com/aihub/common/tenant/TenantContext.java
aihub/aihub-api/src/main/java/com/aihub/api/client/PlatformClient.java
aihub/aihub-api/src/main/java/com/aihub/api/config/FeignTenantConfig.java
aihub/aihub-gateway/src/main/java/com/aihub/gateway/GatewayApplication.java
aihub/aihub-gateway/src/main/java/com/aihub/gateway/filter/AuthGlobalFilter.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/PlatformApplication.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/auth/service/AuthService.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/WebMvcConfig.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/config/MybatisPlusConfig.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/annotation/RequirePermission.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/UserController.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/interceptor/PermissionInterceptor.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysUserService.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/service/SysRoleService.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/SysUserCreateReqVO.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/entity/SysApiKeyDO.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/controller/ApiKeyController.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/apikey/mapper/SysApiKeyMapper.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/user/mapper/SysUserMapper.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/controller/UsageController.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/mapper/AiUsageRecordMapper.java
aihub/aihub-platform-service/src/main/java/com/aihub/platform/quota/mapper/AiQuotaUsageMapper.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/AiServiceApplication.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/web/ChatController.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/config/TenantSqlGuardConfig.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/ai/config/AgentProperties.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/ai/agent/PlanningAgent.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/ai/tools/KnowledgeTools.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/outbox/EventOutboxDispatcher.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/persistence/DbAppRepository.java
aihub/aihub-ai-service/src/main/java/com/aihub/ai/infra/storage/StorageConfiguration.java
aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/tools/AiHubTools.java
aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/model/KbBrief.java
aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/api/ApiEnvelope.java
aihub/aihub-mcp-service/src/main/java/com/aihub/mcp/config/AiHubMcpConfiguration.java
aihub/aihub-mcp-sse/src/main/java/com/aihub/mcp/sse/auth/McpChannelAuthConfiguration.java
aihub/aihub-platform-service/src/test/java/com/aihub/platform/config/MybatisPlusConfigTest.java
aihub/aihub-platform-service/src/test/java/com/aihub/platform/quota/service/QuotaServiceTest.java
aihub/aihub-ai-service/src/test/java/com/aihub/ai/application/ModelAdminServiceTest.java
aihub/aihub-mcp-stdio/src/test/java/com/aihub/mcp/stdio/AiHubMcpStdioApplicationTest.java
```

> 注：§9.2 中 `CurrentTenant.java`、`CurrentTenantArgumentResolver.java` 及"改造后"代码为**明确标注的设计示例**，不在自检清单内，也不存在于项目中——这是有意为之的教学设计，非遗漏。
