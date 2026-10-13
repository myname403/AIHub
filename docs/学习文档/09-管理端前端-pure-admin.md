# 09 · 管理端前端 pure-admin —— 学习文档

> 源码位置：`aihub-admin-ui/`（Vue 3 + TypeScript + Element Plus + Pinia，基于开源骨架 pure-admin-thin 改造）
> 运行：`corepack pnpm@9.15.9 dev`（端口 8849），访问 http://localhost:8849
> 本文延续 08 的通俗风格，每个机制都配最小 demo。**建议先读完 08 再读本文**（Promise/流式等基础在 08 讲过）。

---

## 1. 管理端与用户端的关系

两个前端**共用同一套后端**（网关 8080），但定位完全不同：

| | 用户端（uni-app，5173） | 管理端（pure-admin，8849） |
|---|---|---|
| 用户 | 终端使用者 | 租户管理员 |
| 形态 | 手机优先 | 桌面浏览器 |
| 功能 | 对话 / 知识库问答 / Agent | 模型配置 / 用户权限 / 用量看板 |
| 交互 | 轻交互、流式打字 | 重表格表单（Element Plus 组件库） |

管理端骨架 [pure-admin-thin](https://github.com/pure-admin/pure-admin-thin)（MIT 开源）提供了：登录页、布局（侧边栏+标签页+顶栏）、路由守卫、权限框架、主题切换。**我们做的事是"适配 + 填业务"**：改请求层对齐后端响应格式、接动态路由、写 8 个业务页面。学会"在成熟骨架上做适配"是实际工作中最常用的技能。

---

## 2. 前置知识：四个概念的最小 demo

### 2.1 TypeScript——给 JS 加类型

```ts
// JS 的问题：函数参数想传什么都行，错要到运行时才发现
function getUser(id) { return http.get('/user/' + id) }
getUser('abc')   // 不报错，但请求必然失败

// TS：类型写在参数后面，IDE 实时标红，编译期就拦截
interface User { id: number; username: string }
function getUser(id: number): Promise<User> { return http.get('/user/' + id) }
getUser('abc')   // ← 编辑器直接划红线：string 不能赋给 number
```

本项目所有 `.ts` 文件都是 TS。`interface` 声明"数据长什么样"（如 `UserResult`），是前后端契约在前端的体现。

### 2.2 Vue3 组合式 API——`<script setup>`

```vue
<script setup lang="ts">
import { ref, reactive, onMounted, computed } from "vue";

// ref：单个值的响应式（js 里读写要 .value，模板里自动解包）
const loading = ref(false);
loading.value = true;

// reactive：对象/多个值的响应式（直接改属性，不用 .value）
const form = reactive({ username: "", nickname: "" });
form.username = "admin";

// computed：由其他数据算出来的值，依赖变了自动重算
const count = ref(1);
const double = computed(() => count.value * 2);   // count 变，double 自动变

// onMounted：页面挂载完成后执行（发首屏请求的时机）
onMounted(() => { console.log("页面渲染完了，可以发请求了"); });
</script>
```

**为什么叫"组合式"**：Vue2 把一个功能的 data/methods/watch 拆在三处；Vue3 允许"一个功能的逻辑写在一起"（如用户页的 loading、请求、分页全在一个块里），复用时抽成函数即可。

### 2.3 Pinia——全局状态仓库

跨页面共享的数据（如登录用户信息）放 Pinia（Vue 官方状态库）：

```ts
// 最小 Pinia demo：一个计数器仓库
import { defineStore } from "pinia";
export const useCounterStore = defineStore("counter", {
  state: () => ({ count: 0 }),                 // 数据
  actions: { increment() { this.count++ } }    // 方法
});
// 任何组件里：
const store = useCounterStore();
store.increment();                              // 改了这里，所有用 count 的组件同步更新
```

本项目的 `store/modules/user.ts` 就是一个 Pinia 仓库：存 token、用户名、角色、按钮权限码——登录页写入，顶栏头像和权限指令读取。

### 2.4 Axios 拦截器——所有请求的统一关卡

```ts
// 最小拦截器 demo：发请求自动加 token，收响应统一处理错误
axios.interceptors.request.use((config) => {
  config.headers.Authorization = "Bearer " + localStorage.getItem("token");
  return config;                                // ★ 必须返回 config
});
axios.interceptors.response.use(
  (res) => res.data,                            // 成功：直接剥掉 HTTP 壳
  (err) => { alert("出错了"); return Promise.reject(err); }
);
```

本项目的拦截器（`src/utils/http/index.ts`）在这个基础上多做一层：**解包后端统一响应 `R{code, message, data}`**——`code===0` 时直接把 `data` 交给调用方（页面代码里 `await http.request(...)` 拿到的就是业务数据本身，不用每处都写 `res.data.data`）；`code===10002`（未认证）时清 token 踢回登录页。这是"框架层解决重复劳动"的典型。

---

## 3. 我们对骨架的四点适配（改造清单）

### 3.1 vite 代理——开发环境免 CORS

```ts
// vite.config.ts：把 /auth 与 /api 转发到网关 8080
proxy: {
  "/auth": { target: "http://localhost:8080", changeOrigin: true },
  "/api":  { target: "http://localhost:8080", changeOrigin: true }
}
```

浏览器请求 `localhost:8849/auth/login`，vite 开发服务器**偷偷转发**到 8080 再把结果带回——浏览器以为是同源，没有跨域问题。生产环境由 nginx 做同样的事。

### 3.2 请求层解包 R（`utils/http/index.ts` 响应拦截器）

```ts
if (body && typeof body.code === "number") {
  if (body.code === 0) return body.data;        // 成功 → 直接交业务数据
  if (body.code === 10002) {                    // 未认证 → 踢回登录
    removeToken(); window.location.href = "/#/login";
  }
  message(body.message || "操作失败", "error"); // 业务失败 → 弹提示
  return Promise.reject(new Error(body.message));
}
```

### 3.3 登录流（`api/user.ts` 的 getLogin）

管理端登录比用户端多一步——**登录成功立刻拉权限信息**：

```
POST /auth/login → 拿 token
  → GET /api/platform/system/permission/info（带新 token）
  → 拿到 { routes: 菜单树, permissions: 按钮权限码 }
  → 缓存路由树（给 initRouter 用）+ 组装成 pure-admin 期望的用户信息结构
```

登录表单比用户端多了**租户码输入框**（多租户系统登录三要素：租户码 + 账号 + 密码 + 验证码）。顺手修过一个骨架坑：pure-admin 默认密码校验是"8-18 位两种组合"，会把 demo 的 6 位纯数字密码挡住——登录环节的格式限制没有安全意义（那是注册/改密时的事），已放宽为非空校验。

### 3.4 动态路由（`api/routes.ts` + `router/utils.ts`）——RBAC 的前端落点

这是管理端最核心的机制。原理：

```
管理员 admin 登录   → 后端返回菜单树 [首页, AI管理(3), 系统管理(4), 用量]  → 看到全部
运营  staff 登录    → 后端只返回被授权的 [首页, 用量]                  → 只看到这两个菜单
```

后端返回的是 MenuNode（`{id, name, path, component, icon, menuType, children}`），前端要做**格式翻译**：

```ts
// 后端节点 → pure-admin 路由对象
const toPureRoute = (node) => ({
  path: menu.path,                              // 如 /system/user
  component: menu.component ?? "empty",         // ★ 字符串！addAsyncRoutes 会自动映射到
                                                //   src/views/system/user/index.vue
  meta: { title: menu.name, icon: toIconify(menu.icon),
          rank: menu.sort, showLink: menu.visible !== 0 }
});
// 图标名也要翻译：后端存 Element Plus 图标名（HomeFilled）→ 前端 iconify 格式（ep:home-filled）
```

**component 是字符串**是整个机制的巧处：`router/utils.ts` 里 `import.meta.glob("/src/views/**/*.vue")` 把 views 下所有组件预登记成"路径 → 组件"字典，动态路由时按 component 字符串查字典挂载——所以**你建一个页面 + 后端加一条菜单记录，侧边栏就出现这个页面**，不用改任何路由代码。

按钮级权限同理：`permissions` 数组存权限码（如 `system:user:create`），页面按它显隐按钮（后端接口还有 `@RequirePermission` 二次校验——前端显隐只是体验，后端拦截才是安全）。

---

## 4. 八个业务页面讲解（都在 `src/views/`）

所有页面都是同一个模式：**加载列表（onMounted → http.request）→ 弹窗表单（reactive form + submit）→ 增删改后刷新**。以用户管理为例：

```
views/system/user/index.vue
├── 数据区：tableData / total / query(分页+搜索) / dialogVisible / form
├── loadData()     GET /api/platform/system/user/page → 填表格
├── openCreate()   清空 form 开弹窗
├── openEdit(row)  填 form（角色回显再调 GET /{id}/role-ids）
├── submit()       editId==null ? POST : PUT
└── doDelete()     DELETE + el-popconfirm 确认
```

| 页面 | 位置 | 特有知识点 |
|---|---|---|
| 用户管理 | views/system/user | 分页表格、重置密码弹窗、多选角色下拉 |
| 角色管理 | views/system/role | **el-tree 菜单授权树**（勾选/半选，保存时 checked+halfChecked 合并提交）|
| 菜单管理 | views/system/menu | el-table 树形展示（row-key + tree-props） |
| 模型管理 | views/ai/model | 双卡片布局（模型列表 + 场景路由）、密钥"留空不改"交互 |
| 应用管理 | views/ai/app | 系统提示词 textarea、温度滑杆 |
| 知识库 | views/ai/kb | 最简单的标准页 |
| 用量统计 | views/usage | 指标卡 + 时间窗切换 |
| API Key | views/system/apikey | "明文只显示一次"的弹窗交互 + 复制按钮 |
| 仪表盘 | views/welcome | 指标卡 + 快捷入口 + 配额进度条 |

**角色授权树的两个细节**（源码有注释）：回显时只勾叶子节点（父节点交给 el-tree 的父子联动自动推算，否则会全选父节点）；保存时**半选的父节点也要提交**（否则"菜单授权了但父目录没授权"，侧边栏出不来）。

---

## 5. 骨架文件导览（改过/需要知道的）

| 文件 | 改动 |
|---|---|
| `src/api/user.ts` | ★ 登录流重写（对接 /auth/login + permission/info） |
| `src/api/routes.ts` | ★ 后端菜单树 → pure-admin 路由的转换器 |
| `src/utils/http/index.ts` | ★ 响应拦截器解包 R + 10002 踢登录 |
| `src/views/login/index.vue` | 加租户码/验证码输入框 |
| `src/views/login/utils/rule.ts` | 放宽密码校验（登录环节只验非空） |
| `vite.config.ts` | 加 /auth、/api 代理 |
| `.env` / `.env.development` | VITE_PORT=8849（8848 与 Nacos 冲突） |

骨架自带但你需要知道的：`src/router/`（静态路由+守卫）、`src/store/`（Pinia 仓库群）、`src/layout/`（整体布局）、`src/directives/`（v-permission 等指令）。

---

## 6. 常见坑速查

| 坑 | 现象 | 解法 |
|---|---|---|
| 8848 打不开 | 看到的是 Nacos 控制台 | 管理端在 **8849**（8848 与 Nacos 冲突） |
| 登录提示密码格式错误 | 6 位密码被挡 | 骨架默认校验太严，已放宽（见 rule.ts） |
| 登录后菜单空 | 白页只有侧边栏 | 该用户没有角色/权限码（RBAC 生效的表现，去角色管理授权） |
| 改了后端菜单树前端没变 | 菜单还是旧的 | 重新登录一次（路由树是登录时拉取的） |
| pnpm 装不上 | Node 版本报错 | 用 nvm 的 Node 24 + corepack pnpm@9.15.9 |
| 请求 401 循环跳登录 | 永远在登录页 | Nacos/网关没起，或 token 过期后拦截器清了 token |

---

## 7. 学习自测

1. 管理端登录比用户端多拿一样什么东西？（提示：permission/info 返回的 routes 和 permissions 分别给谁用）
2. 动态路由的完整链路：从后端菜单树到侧边栏出现菜单，中间发生了哪三步？
3. component 字段是字符串，浏览器怎么知道它对应哪个 .vue 文件？
4. http 响应拦截器帮你省掉了页面里的哪些重复代码？10002 的特殊处理是什么？
5. 角色授权树保存时为什么要"checked + halfChecked"合并提交？
6. 为什么说"前端按钮显隐只是体验，后端拦截才是安全"？
7. vite 代理解决了什么问题？生产环境谁来做这件事？

---

*前端两篇（08/09）完。整个项目共 9 份学习文档：后端 7 份 + 前端 2 份。*
