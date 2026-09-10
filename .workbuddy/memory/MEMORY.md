# AIHub 项目长期约定

> 项目：`E:\微服务\javaAI\javaai` — AIHub 通用 AI 能力中台（多租户微服务）
> 栈：Java 17 / Spring Boot 3.5.0 / Spring Cloud Alibaba 2025.0.0.0 / Spring AI 1.1.5 / Maven 多模块 / MyBatis-Plus + Flyway

## 一、构建环境（★ 必读，踩过多次）

- 本机 PATH 里的 `java` 是 1.8，**必须显式指定 JDK**：
  ```powershell
  $env:JAVA_HOME='D:\Java_JDK\jdk21.0.8_9'
  Set-Location 'E:\微服务\javaAI\javaai\aihub'
  & 'D:\Java_JDK\maven-mvnd-1.0.2-windows-amd64\bin\mvnd.cmd' test
  ```
- **只能用 PowerShell 调 `mvnd.cmd`**。在 bash 下调 `mvnd.sh` 会把 `D:\...` 错转成 `\d\...`，
  报 `Could not get a real path from path \d\Java_JDK\...`。
- PowerShell 里若命令输出被管道吞掉，用 `*>&1 | Tee-Object -FilePath <log> | Out-Null`
  落盘后再 grep，否则看不到 BUILD 结果。

## 二、分层架构（ArchUnit 构建期强制，地位高于一切）

`web → application → domain ← infra`

- **domain 零框架依赖**：不得出现 Spring / Spring AI / MyBatis / Redis 类型。
- **application 不得依赖 infra / web**。需要 infra 能力时，**先在 domain 建 SPI 端口**，
  实现放 infra。已用过两次：
  - `domain/spi/MetricsRecorder`（含 `NOOP` 常量实现）← `infra/metrics/AiMetrics`
  - `domain/spi/IngestFileStore` ← `infra/storage/LocalIngestFileStore`
- **web 不得触碰 Spring AI 与 infra**。
- `infra.ai` 不得依赖 `infra.persistence` / `infra.security`。
- **坑**：`static final String` 常量会被编译期内联，字节码里没有类引用，
  因此**能绕过 ArchUnit 检测**。曾经 `ChatAppService` 引 `PlanningAgent.NAME` 就这样漏了网。
  解法是 domain 建 `AgentNames`。

## 三、多租户（安全红线）

1. 租户 ID **只能**从 JWT / API Key 解析，绝不接受前端传参。
2. 网关强制剥离客户端伪造的 `X-Tenant-Id` / `X-User-Id` 后重新注入。
3. 仓储层所有方法**显式要求传 tenantId**，不做「猜租户」。
4. 更新/删除语句把 `tenantId` 作为 WHERE 条件本身（而不是先查再改），
   影响行数为 0 即视为越权。
5. 两库独立（aihub_platform / aihub_ai），**禁止跨库 JOIN**。

## 四、密钥与凭据

- 模型 API Key：AES-GCM 加密落库（`AesGcmTextCipher`，密钥 `aihub.security.data-key`）。
  **响应与日志永不出现明文**，`ModelInfo` 只暴露 `hasApiKey` 布尔值。
- 开放 API Key：只存 `HMAC-SHA256(key, api-key-secret)`，明文仅签发时返回一次。
  用 HMAC 而非 bcrypt —— 鉴权在每请求热路径，且 Key 本身是高熵随机值。
- 含密钥的 DTO/命令对象（如 `ModelCommand`）**必须覆写 `toString()` 遮掉明文**，
  否则 record 默认 toString 会把密钥打进日志。

## 五、命名与框架约定（踩过的坑）

- **Jackson 把 `isDefault()` 映射成 JSON 属性 `default`**（boolean getter 的 `is` 前缀规则）。
  布尔字段命名避开 `isXxx`，用 `defaultModel` / `Boolean defaultModel`（Lombok → `getDefaultModel()`）。
- **MyBatis-Plus `update(entity, wrapper)` 默认忽略 null 字段**（`FieldStrategy.NOT_NULL`）。
  「编辑时不传的字段保持原值」正是靠它 —— 这是隐式契约，改动时务必配合单测钉住
  （见 `DbModelAdminRepositoryTest.updateWithoutApiKeyLeavesCipherUntouched`）。
- 聚合查询行对象用 **POJO（Lombok `@Data`）而非 record**，避免依赖驱动的 record 构造器映射。
- 不可变列表（`List.of()`）**不能原地 sort**，必须先 `new ArrayList<>(...)`。
- 排序比较器**不能用三元式冒充**（`(a,b) -> x ? -1 : 1` 不满足传递性，元素 ≥3 时抛
  `Comparison method violates its general contract`），用 `Comparator.comparing(...)`。
- MyBatis 配置已开 `map-underscore-to-camel-case: true`，聚合别名可直接写 `token_in`。

## 六、测试与验证

- 单测：JUnit5 + Mockito（严格桩，多余的 stub 会报 `UnnecessaryStubbingException`）。
- 指标测试用 `SimpleMeterRegistry`；工具类用 `ReflectionTestUtils` 注入 `@Value` 字段。
- 改动后跑全量：`mvnd test`（当前 **180 个用例**：common 30 / platform 41 / ai-service 109）。
- 提交前再跑一次 `mvnd -DskipTests install` 确认 4 份可执行 jar 产出正常。
- 测试会**真实抓到实现 bug**（历史上抓到过 convOf 边界、retry 绕过终态写入等），
  失败时优先怀疑实现而不是改断言。

## 七、Flyway 迁移

- platform：V1~V6；ai-service：V1~V8。**新增迁移一律用新版本号，不改历史脚本**。
- 脚本需幂等：`CREATE TABLE IF NOT EXISTS`、种子数据用 `ON DUPLICATE KEY UPDATE`。
