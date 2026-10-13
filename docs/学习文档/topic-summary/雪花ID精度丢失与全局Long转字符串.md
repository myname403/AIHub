# 专题总结 · 雪花 ID 精度丢失与全局 Long 转字符串

> 源码位置：`aihub/aihub-common/src/main/java/com/aihub/common/web/JacksonLongToStringConfig.java`
> 关键词：雪花 ID、JavaScript Number 精度、Jackson 序列化、Long → String
> 一句话记住：**后端 19 位 Long 主键，前端 JS "装不下"，末几位被抹平；根治靠全局把长整型序列化成字符串。**

---

## 1. 问题现象（症状长什么样）

本项目所有实体主键都用**雪花算法**生成，是 19 位十进制的 `Long`（约 2.1×10¹⁸）。上线后出现一类很隐蔽的故障：

- 列表页能正常显示、能查到数据；
- 但一旦拿着某个 ID 去做**回传后端**的操作——编辑、删除、"路由指向模型"、绑定关系等——
  后端报「数据不存在 / 不属于当前租户 / id 对不上」；
- 诡异的是：**数据库里的数据完全正确，代码逻辑也没错**，问题只出在"这个数字"上。

根本症状一句话概括：**同一个 id，前端拿到手的值，和后端发出去的值，末尾几位不一样了。**

---

## 2. 根因：JS Number 的精度天花板

| 项目 | 值 | 说明 |
|---|---|---|
| 雪花 ID 量级 | 约 `2.1×10¹⁸`（19 位） | Java `Long` 能精确表示到 `9.2×10¹⁸`，后端侧没问题 |
| JS 安全整数上限 | `Number.MAX_SAFE_INTEGER = 2⁵³-1 ≈ 9.007×10¹⁵`（16 位） | 超过这个数就不保证精确 |

JavaScript 的 `Number` 底层是 **IEEE-754 双精度浮点数**，只有 52 位尾数，能**精确**表示的整数上限是 `2⁵³-1`。

雪花 ID 是 19 位，**远远超过** 16 位的安全上限。所以当后端把这种大整数以 **JSON 数字**（不带引号）下发时：

```
后端真实 id：  2106668143920308226
        ↓ 浏览器 JSON.parse 按 double 解析
前端拿到：      2106668143920308224   ← 末位已被四舍五入抹平
        ↓ 前端原样回传
后端拿去查库：  ...224 ≠ ...226  → 查无此人
```

**数据本身没错，是数字在 JS 里"装不下"了。** 这是浮点精度的固有缺陷，不是某个接口的 bug。

---

## 3. 30 秒复现实验

在 PowerShell 里执行（用 double 模拟 JS 的双精度行为）：

```powershell
[double]"2106668143920308226"
```

输出 `2106668143920308224` —— **末位已经变了**。这正是前端拿到 JSON 数字后会经历的事（JS Number 同款双精度浮点）。

再对照库里真实值确认：

```powershell
docker exec aihub-mysql mysql -uroot -proot123456 -e "select id from aihub_platform.sys_tenant where code='12'"
```

得到的是 `...226`。**补丁没打之前，前端拿着 `...224` 来回传，后端查无此人。**

---

## 4. 解决方案：全局 Jackson 把长整型序列化为字符串

**思路**：既然数字"装不下"，那就别用数字下发——**统一把长整型序列化成 JSON 字符串**（带引号）。前端拿到的是精确的 `"2106668143920308226"`，原样回传即可。

回传方向也无需额外处理：后端 `@RequestBody Long` / `@PathVariable Long` **能正常反序列化字符串数字**，Jackson 会自动把 `"123"` 转成 `Long`。

### 4.1 源码（`JacksonLongToStringConfig.java`）

```java
@Configuration
public class JacksonLongToStringConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> {
            // 包装类型 Long 与基本类型 long 都转字符串，覆盖实体 id 及数量字段
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);
            // BigInteger 同样是任意长度整数，一并处理，避免个别统计/游标字段踩坑
            builder.serializerByType(BigInteger.class, ToStringSerializer.instance);
        };
    }
}
```

### 4.2 逐行拆解

| 代码 | 作用 | 为什么这么写 |
|---|---|---|
| `@Configuration` + `@Bean` | 注册一个 `Jackson2ObjectMapperBuilderCustomizer` | Spring Boot 自动装配 ObjectMapper 时会应用所有此类定制器，无需手动 new |
| `serializerByType(Long.class, ...)` | 包装类型 `Long` 转字符串 | 实体主键字段大多是 `Long`（对象类型） |
| `serializerByType(Long.TYPE, ...)` | 基本类型 `long` 转字符串 | `Long.TYPE` 就是 `long.class`，覆盖 `long` 裸类型，防漏 |
| `serializerByType(BigInteger.class, ...)` | `BigInteger` 转字符串 | 任意长度整数同样会溢出，统计/游标字段一并防坑 |
| `ToStringSerializer.instance` | 调 `toString()` 输出为字符串 | Jackson 内置序列化器，直接产出带引号的字符串 |

**关键点**：这里是"追加类型定制"（`serializerByType`），只影响这三类整数的输出形式，**不改动**全局 ObjectMapper 的其他行为（日期格式、命名策略等）。

---

## 5. 作用范围与边界（哪些生效、哪些不生效）

理解边界，才不会误以为"怎么还没生效"或"怎么把别的也影响了"。

| 服务 / 场景 | 是否受影响 | 说明 |
|---|---|---|
| **平台服务**（platform-service） | ✅ 生效 | `scanBasePackages="com.aihub"` 扫到 `com.aihub.common`，需重启生效 |
| **AI 服务**（ai-service） | ✅ 生效 | 同上，扫描 `com.aihub` 全包 |
| **网关**（gateway） | ❌ 不受影响 | WebFlux 且只扫 `com.aihub.gateway`,不加载本类；网关也不序列化业务实体，本就不需要 |
| 服务内部 `new ObjectMapper()` 自用序列化 | ❌ 不受影响 | 如 NDJSON 事件帧、发件箱（Outbox）落库等**自建** ObjectMapper，只走默认配置，仍是数字 |

**重要区分**：该定制**只作用于 Spring MVC 响应序列化用的那台由容器管理的 ObjectMapper**。各服务里手工 `new ObjectMapper()` 出来的实例（用于内部事件、落库序列化等）不受这个 Bean 影响，保持数字输出——这也是刻意保留的，因为那些场景不经过前端 JS，不存在精度问题。

**部署注意**：改完需要**重启** AI 服务和平台服务才生效（Bean 在启动时装配）。

---

## 6. 为什么不用其他"看起来也行"的方案

| 备选方案 | 为什么不选 |
|---|---|
| 前端用 `toFixed` / BigInt 处理 | 治标不治本，每个用到 id 的地方都得防；且 JSON 已解析为 double，精度在解析那一刻就已丢失，前端再补救信息已缺失 |
| 缩短 ID（如自增、雪花截短） | 破坏雪花算法的分布式唯一性与趋势递增特性，代价远大于收益 |
| 只在个别接口手动 `String.valueOf(id)` | 散点维护、极易漏，新接口默认还是数字；全局方案一处配置覆盖所有回传场景 |
| 全局序列化成字符串（本方案） | ✅ 一处配置、前后端零心智负担、不回退、不影响其他类型 |

---

## 7. 前端侧配套（el-select 等组件匹配失效）

后端下发的是字符串 id（如 `"2106668143920308226"`），前端做**下拉选中匹配**（`el-select`/`el-tree` 的 value 比对）时也要注意：

- 匹配双方类型要一致（都用字符串），否则 `"123" !== 123` 会导致选中态匹配不上；
- 该补丁之后，全链路 id 都应以**字符串**形态流转，避免中途又 `Number(id)` 转回去把精度再丢一次。

---

## 8. 自测五问

1. 雪花 ID 为什么在浏览器里会"变"？（19 位 Long > JS 安全整数 2⁵³-1，JSON 按 double 解析末几位被抹）
2. 根治方案的一句话是什么？（全局把 Long/long/BigInteger 序列化成字符串）
3. 前端回传字符串数字，后端能接吗？（能，`@RequestBody Long`/`@PathVariable Long` 自动反序列化）
4. 网关需要这个补丁吗？（不需要，WebFlux 且不序列化业务实体）
5. 服务内部 `new ObjectMapper()` 的产物会被转成字符串吗？（不会，只有容器管理的 MVC ObjectMapper 受影响）

---

## 9. 相关文件与位置速查

| 内容 | 位置 |
|---|---|
| 全局配置类 | `aihub/aihub-common/src/main/java/com/aihub/common/web/JacksonLongToStringConfig.java` |
| 配套讲解（记忆卡/实验） | `aihub/aihub-common/公共模块讲解/02-记忆与实验.md` |
| 生效服务 | `aihub-platform-service`、`aihub-ai-service`（均 `scanBasePackages="com.aihub"`） |
| 不受影响服务 | `aihub-gateway`（WebFlux，仅扫 `com.aihub.gateway`） |

> 关联记忆条目：`雪花ID精度丢失问题及全局Long转字符串解决方案`（project_introduction）。
