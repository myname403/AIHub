# 11 · Java 语言进阶 —— 集合、泛型、反射与注解、Stream

> 锚点：你天天在 AIHub 里用 `@Data`、`@TableName`、`Wrappers.lambdaQuery()`、`record`……
> 却可能说不清它们**为什么**能工作。本篇把这些"日用而不知"的东西一次讲透。
> 所有 demo 可直接在 IDEA 里建个 main 方法跑。

---

## 1. 集合框架——选型是第一能力

### 1.1 一张图记住家族

```
Collection
├── List（有序可重复）      ArrayList(数组) / LinkedList(双向链表) / CopyOnWriteArrayList(并发)
├── Set（不重复）           HashSet(哈希) / LinkedHashSet(保插入序) / TreeSet(排序)
└── Map（键值对，单独一族） HashMap / LinkedHashMap / TreeMap / ConcurrentHashMap(并发)
```

### 1.2 项目里的选型案例与理由

```java
// AIHub 里的真实选型：
new ArrayList<>()                    // 列表展示：随机访问多、追加多 → 数组实现最优
new ConcurrentHashMap<>()            // ChatClientFactory 的装配缓存：多线程读写 → 并发安全
Map<Long, List<SysMenuDO>> byParent  // 菜单树分组：groupingBy 收集后按键取子节点
new StringBuilder()                  // 拼接提示词/SQL：循环拼接绝不 String +=
```

**ArrayList vs LinkedList 面试标准答案**：底层分别是动态数组与双向链表；随机访问 O(1) vs O(n)；中间插入理论 O(n) vs O(1) **但实际 ArrayList 常更快**（内存连续对 CPU 缓存友好 + 扩容摊还成本低）——所以**默认无脑 ArrayList**，只有在头尾频繁增删的队列场景才考虑 LinkedList。

### 1.3 必会的三个易错点

```java
// ① 遍历时删除 → ConcurrentModificationException
for (String s : list) { if (s.startsWith("x")) list.remove(s); }   // ✗
list.removeIf(s -> s.startsWith("x"));                              // ✓ 或 Iterator.remove()

// ② asList 的坑：返回的是固定长度视图
List<String> fixed = Arrays.asList("a", "b");
fixed.add("c");                          // ✗ UnsupportedOperationException
List<String> mutable = new ArrayList<>(List.of("a", "b"));          // ✓ 要可变就包一层

// ③ HashMap 遍历顺序不保证——需要插入序用 LinkedHashMap，需要排序用 TreeMap
```

---

## 2. 泛型——类型安全的本质

### 2.1 从项目代码看泛型的两层作用

```java
// 第一层：编译期类型检查（R<T> 泛型让"取数据"不用强转且类型错误编译期暴露）
R<SysUser> r1 = R.ok(user);
SysUser u = r1.getData();          // 无需强转

// 第二层：泛型方法 + 类型推断（MyBatis-Plus lambdaQuery 为何类型安全）
Wrappers.<SysRoleDO>lambdaQuery().eq(SysRoleDO::getTenantId, tenantId)
//                       ↑ 方法引用带类型信息，字段写错编译报错；字符串 SQL 字段写错只有运行时炸
```

### 2.2 擦除与边界（面试考点）

```java
// 泛型只存在于编译期，运行时被"擦除"——所以这些不合法：
new T()                             // ✗ 不知道 T 是什么
new T[10]                           // ✗
if (obj instanceof List<String>)    // ✗ 擦除后 List<String> 和 List<Integer> 是同一个类
if (obj instanceof List<?>)         // ✓ 通配符可以

// 通配符规则：PECS（Producer Extends, Consumer Super）
List<? extends Number> read;        // 只读生产者：能取 Number 及子类
List<? super Integer> write;        // 只写消费者：能放 Integer 及其父类可接收的
```

**为什么 record 和 enum 不能继承类**：它们已隐式继承（Record/Enum），Java 单继承限制——但可以实现接口。

---

## 3. 反射与注解——框架魔法的引擎

### 3.1 你天天在用反射（只是不知道）

AIHub 里至少四处框架魔法全靠反射：
- `@RequirePermission` → PermissionInterceptor **反射读取**方法上的注解做鉴权；
- `@TableName/@TableId` → MyBatis-Plus **反射扫描** DO 类生成 SQL；
- `@Data` 生成的方法、`@Autowired` 字段 → Spring **反射注入**；
- `@Test` → JUnit **反射调用**测试方法。

### 3.2 最小 demo——手写一个"迷你权限校验"

```java
// 第 1 步：定义注解（@Retention 决定"活到运行时"，反射才能读到）
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface RequirePermission {
    String value();
}

// 第 2 步：模拟框架——用反射读取注解并校验（PermissionInterceptor 的灵魂就这几行）
class Framework {
    static Set<String> userPerms = Set.of("system:user:create");

    static void invoke(Object controller, String method) throws Exception {
        Method m = controller.getClass().getMethod(method);
        RequirePermission p = m.getAnnotation(RequirePermission.class);
        if (p != null && !userPerms.contains(p.value())) {
            throw new RuntimeException("403 无权限：" + p.value());   // 拦截器里抛 403
        }
        m.invoke(controller);   // 校验过了才真正执行方法
    }
}

// 第 3 步：使用——和 AIHub 的 UserController 一模一样
class UserController {
    @RequirePermission("system:user:create")
    public void create() { System.out.println("用户创建成功"); }
}

// 跑起来：有权限执行方法，没权限 403
Framework.invoke(new UserController(), "create");
```

**再看 @Data 生成了什么**：Lombok 在**编译期**（注意不是反射，是注解处理器 APT）改写语法树，给类塞进 getter/setter/equals/hashCode/toString。所以在 IDEA 里按 Ctrl+F12 看编译后的类结构，能看到源码里没有的方法。

---

## 4. Stream API——声明式数据处理

### 4.1 项目里的真实例子拆解

```java
// QuotaService：角色 ID 列表的提取链
roleIds = userRoleMapper.selectList(...)      // 查出关联行
        .stream()                             // 变成流
        .map(SysUserRoleDO::getRoleId)        // 每行映射成 roleId
        .toList();                            // 收集成不可变列表

// UsageQueryService：空值兜底 + 算术
tokenIn + tokenOut, tokenIn == null ? 0 : tokenIn   // Stream 前的朴素写法也有价值
```

### 4.2 五个核心操作的最小 demo

```java
List<String> names = List.of("Alice", "Bob", "Adam", "Cindy");

// filter(过滤) + map(映射) + collect(收集) —— 三板斧
List<String> result = names.stream()
        .filter(n -> n.startsWith("A"))       // 惰性：此刻什么都不执行
        .map(String::toUpperCase)             // 惰性
        .toList();                            // 终止操作才真正执行
// → [ALICE, ADAM]

// 分组（AIHub 菜单树就是这么组装的）
Map<Integer, List<String>> byLen = names.stream()
        .collect(Collectors.groupingBy(String::length));

// 排序 + 去重 + 截取（AIHub 用量榜 top N 的写法）
names.stream().distinct().sorted().limit(2).toList();

// 求和/统计（配额 Token 合计）
int total = List.of(1, 2, 3).stream().mapToInt(Integer::intValue).sum();
```

**惰性求值**是 Stream 的灵魂：中间操作（filter/map/sorted）只是"记录 recipe"，遇到终止操作（collect/forEach/sum）才一次性执行——甚至能自动优化（filter 后 count 不再 map 无关元素）。

### 4.3 什么时候别用 Stream

- 简单 for 循环带复杂副作用（改外部变量）——Stream 强调无副作用，硬写可读性崩；
- 需要提前 return/break 的长逻辑——循环更直观；
- 性能敏感的基础类型大量运算——优先 `IntStream` 原始类型流避免装箱。

---

## 5. record 与新语法——Java 17 你该会的

```java
// record：不可变数据载体（AIHub 的 ChatTurn/StreamEvent/DTO 全在用）
public record ChatTurn(Long tenantId, String userText, String scene) {}
// 一行 = final 字段 + 构造器 + 访问器(userText() 不是 getUserText()) + equals/hashCode/toString

// switch 表达式：值返回 + 箭头语法（AuthController 的场景分发）
String label = switch (scene) {
    case "chat" -> "普通对话";
    case "rag"  -> "知识库问答";
    default     -> "未知场景";
};

// text block：多行字符串（PlanningAgent 的 LLM 提示词模板）
String prompt = """
        可用执行 Agent：
        %s
        用户目标：%s
        """.formatted(descriptions, goal);

// instanceof 模式匹配：判断+强转一步到位（GlobalExceptionHandler 里在用）
if (e instanceof MethodArgumentNotValidException m) {
    return m.getBindingResult().getFieldError();
}

// 密封类（sealed）：限定谁能继承我——错误/结果类型的理想载体
public sealed interface AgentResult permits Done, Failed, Canceled {}
```

---

## 6. 面试视角速记

| 高频问题 | 一句话回答（展开见 19 篇） |
|---|---|
| HashMap 原理？ | 数组+链表/红黑树；链表长度超 8 且数组≥64 转红黑树；扩容 2 倍 |
| ConcurrentHashMap 怎么保证并发安全？ | CAS + synchronized 锁单个桶（JDK8 起），锁粒度到头节点 |
| 反射有什么用/缺点？ | 框架基石（注解处理）；缺点：慢、绕过编译期检查、破坏封装 |
| Stream 和 for 循环谁快？ | 简单场景 for 略快（无装箱/调用开销），可读性才是选择标准 |
| == 与 equals？ | == 比引用；equals 比内容（重写后）；record 自动重写 |

---

## 7. 自测与进阶

1. 为什么 AIHub 的缓存用 `ConcurrentHashMap` 而不是 `HashMap + synchronized`？
2. `Wrappers.lambdaQuery().eq(SysRoleDO::getTenantId, ...)` 的类型安全是怎么实现的？（提示：方法引用的泛型推导）
3. 手写一遍"反射 + 注解"demo（本文 3.2），然后说出 PermissionInterceptor 与它的三个差异。
4. record 与 Lombok @Data 什么时候选哪个？（record 无 setter、可带校验逻辑、密封性强）
5. 进阶方向：反射性能优化（MethodHandle/LambdaMetafactory）、字节码（ASM/ByteBuddy）、编译期注解处理器（自己写一个 @Builder）。
