# 10 - 管理端前端 aihub-admin-ui 讲解

> 本文是 AIHub 项目全面讲解系列的第十篇，讲解管理端前端 `aihub-admin-ui`。本文自成一体，不依赖系列其他篇章即可独立阅读。文中引用的源码路径均以项目根目录（`javaai/`）为基准，`#L` 后跟行号，例如 `aihub-admin-ui/src/api/routes.ts#L70-88` 表示该文件第 70 到 88 行。阅读本文只需具备 Vue3 基础；涉及后端契约的部分会顺带说明后端长什么样，不需要先读后端文档。

---

## 1. 定位与技术栈

### 1.1 它在项目里的位置

AIHub 是一个多租户 AI 平台，有三类角色：

- **平台管理员 / 企业管理员**：在浏览器里管理模型、应用、知识库、子用户、角色、菜单、用量配额 —— 这就是 `aihub-admin-ui` 的用户；
- **终端用户**：通过用户端 H5/小程序（`aihub-app`，本系列第 11 篇）对话、查知识库；
- **开放调用方**：拿 API Key 走网关调开放接口。

`aihub-admin-ui` 部署后监听 **8849** 端口（开发期是 Vite dev server，生产期是 nginx 容器），所有请求通过两个前缀打到网关 8080：

- `/auth/**` —— 认证类接口（登录、验证码），由网关路由到 platform-service 8081；
- `/api/**` —— 业务类接口（菜单、用户、模型、知识库、用量），同样经网关鉴权后分发。

也就是说前端**永远不直连任何微服务**，它只认识网关。这让前端部署与后端服务扩缩容完全解耦。

### 1.2 pure-admin-thin 6.2.0：站在成熟脚手架上

`aihub-admin-ui/package.json#L2-3` 写明项目本体是 **pure-admin-thin 6.2.0**——vue-pure-admin 官方的"瘦身版"模板（去掉了完整版的示例页面，保留核心框架）。选择它而不是从零搭建或用 element-plus 官方 starter，看中的是它开箱自带一套**企业后台最难写也最容易写烂的部分**：

1. **动态路由 + 权限菜单体系**：登录后从后端拉菜单树、`addRoute` 动态注册、侧边栏按权限过滤渲染——这正好是 AIHub RBAC 需要的"页面级权限"底座；
2. **完善的布局层**：`src/layout` 里是侧边栏、多标签页、面包屑、主题切换、全屏、响应式折叠等一整套壳子，业务页面只管往 `src/views` 里填；
3. **工程化全家桶**：ESLint 9（flat config）+ Prettier + Stylelint + husky + lint-staged + commitlint，`pnpm lint` 一条命令全过。

### 1.3 技术栈矩阵

| 技术 | 版本（package.json 实际锁定范围） | 在本项目里的角色 |
|---|---|---|
| Vue | `^3.5.22`（`package.json#L72`） | 视图层框架，全部页面用 `<script setup>` 组合式 API |
| TypeScript | `^5.9.3`（`package.json#L119`） | 全量 TS，`pnpm typecheck` 走 tsc + vue-tsc 双检查 |
| Vite | `^7.1.12`（`package.json#L122`） | 构建/开发服务器，负责代理、插件、打包 |
| Element Plus | `^2.11.5`（`package.json#L60`） | UI 组件库：表格、表单、弹窗、树、分页全部来自它 |
| Pinia | `^3.0.3`（`package.json#L66`） | 状态管理：用户态（`pure-user`）、权限菜单（`pure-permission`）、多标签页（`pure-multiTags`） |
| vue-router | `^4.6.3`（`package.json#L73`） | 路由：hash 模式 + 动态 addRoute |
| Tailwind CSS | `^4.1.16`（`package.json#L118`，配套 `@tailwindcss/vite#L85`） | 原子化样式，页面里大量 `p-4`、`flex justify-between`、`rounded-xl` |
| axios | `^1.12.2`（`package.json#L57`） | 网络层，被封装成 `PureHttp` 类（见第 4 章） |
| ECharts | `^6.0.0`（`package.json#L59`） | 用量页趋势图（按需引入，见 7.2） |
| js-cookie / responsive-storage | `package.json#L61/L69` | token 双存储：cookie 放 token，localStorage 放用户信息 |

Tailwind 4 值得单独说一句：它不再需要 `tailwind.config.js` 和 PostCSS 插件链，改为 `@tailwindcss/vite` 直接作为 Vite 插件介入（`aihub-admin-ui/build/plugins.ts#L20`），CSS 侧只在 `src/style/tailwind.css` 里 `@import "tailwindcss"` 即可。这让"组件库（Element Plus）管交互组件 + Tailwind 管布局间距"的双轨样式方案零配置共存。

### 1.4 AIHub 对模板做了哪些"动刀"

脚手架只是毛坯，AIHub 适配主要体现在四刀：

1. **砍掉 mock 服务器**：`build/plugins.ts#L42-47` 的注释记录了原因——模板自带 `vite-plugin-fake-server` 对接的是模板自己的 `/login`、`/get-async-routes` 假接口，与真实后端契约对不上，还可能把 mock 打进生产包，所以彻底移除（`mock/` 目录下的 `asyncRoutes.ts` 等文件只是模板残留，不再生效）；
2. **登录改造**：从"用户名+密码"改成"**租户码 + 用户名 + 密码 + 图形验证码**"（多租户 SaaS 必须知道你是哪家企业），见 `src/views/login/index.vue#L43-49`；
3. **动态路由对接真实接口**：`getAsyncRoutes` 从读 mock 改为调 `/api/platform/system/permission/info`，并做了平铺结构解析与刷新兜底（第 5 章重点）；
4. **响应体适配**：后端统一响应体是 `R{code, message, data}`（`code=0` 成功），`PureHttp` 响应拦截器统一解包，业务代码永远只拿 `data`（4.5 节）。

---

## 2. 目录职责表

```
aihub-admin-ui/
├── build/            # Vite 构建期脚本（plugins/cdn/compress/info/optimize/utils）
├── mock/             # 模板残留的 mock 文件，已随 fake-server 插件移除而失效
├── deploy/nginx/     # 生产容器 nginx 配置模板（envsubst 渲染）
├── public/           # 原样拷贝的静态资源，platform-config.json 全局配置在这里
├── types/            # 全局 TS 声明（路由配置、window 扩展、组件 shim）
├── src/
│   ├── api/          # 接口层：user.ts 登录、routes.ts 动态路由获取
│   ├── assets/       # 本地静态资源（svg、字体图标、登录背景）
│   ├── components/   # 通用组件（ReIcon 图标、ReAuth/RePerms 按钮权限、ReDialog…）
│   ├── config/       # 运行时全局配置加载（platform-config.json）
│   ├── directives/   # 自定义指令（含 v-auth 按钮权限指令）
│   ├── layout/       # 布局壳：侧边栏、导航、标签页、主题
│   ├── plugins/      # elementPlus 与 echarts 的按需注册
│   ├── router/       # 路由核心：index.ts 实例、utils.ts 动态路由算法、modules/ 静态路由
│   ├── store/        # Pinia：modules/user（用户态）、permission（菜单）、multiTags（标签页）
│   ├── style/        # 全局样式（reset、tailwind、主题 scss 变量）
│   ├── utils/        # 工具：http/（axios 封装）、auth.ts（token 存取）、message 等
│   └── views/        # 业务页面：welcome、usage、system/{user,role,menu,apikey}、ai/{model,app,kb}、login、error
├── .env*             # Vite 环境变量（端口、路由模式、CDN 开关）
├── .npmrc            # pnpm 行为配置（shell-emulator 等）
├── Dockerfile        # 两阶段构建：node:20 打包 → nginx 托管
└── vite.config.ts    # Vite 入口配置（代理、插件、打包策略）
```

几个容易误判的目录：

- `api/` 里只有 `user.ts` 和 `routes.ts` 两个文件——**其余页面的接口调用直接写在各自的 `.vue` 里**（`http.request(...)`）。这是本项目刻意的取舍：管理端页面不多，为每页建一个 api 文件反而增加跳转成本；只有"登录/动态路由"这种被多处依赖的接口才值得独立成层；
- `mock/` 看起来像还在用，实际上 `vite-plugin-fake-server` 已从 `package.json` 依赖中移除，这些文件不会被任何构建流程加载；
- `build/` 不参与运行时，全是 `vite.config.ts` 的辅助脚本：`plugins.ts` 汇总插件、`utils.ts` 提供 `wrapperEnv`（把 `VITE_PORT="8849"` 这类字符串环境变量转成对应类型）、`optimize.ts` 声明依赖预构建清单。

---

## 3. 启动与构建

### 3.1 环境要求：Node 必须 20.19+

`aihub-admin-ui/package.json#L132-135` 的 engines 字段是硬约束：

```json
// aihub-admin-ui/package.json#L132-135
"engines": {
  "node": "^20.19.0 || >=22.13.0",
  "pnpm": ">=9"
}
```

这不是形式主义。**Vite 7 官方要求 Node 20.19+ / 22.12+**（依赖 Node 内置 `crypto.getRandomValues` 与新版 ESM 特性）。本项目开发时真实踩过这个坑：本机 Node 16.20.2 下启动直接报 `crypto.getRandomValues is not a function`，用 nvm 切到 24.14.0 后解决——这也是为什么项目根下还有一份 `.nvmrc` 锁版本。教训：**engines 声明 + .nvmrc 要在项目初始化时写好，而不是等报错再补**。

### 3.2 pnpm 为什么是强制的

`aihub-admin-ui/package.json#L22`：

```json
// aihub-admin-ui/package.json#L22
"preinstall": "npx only-allow pnpm"
```

pnpm 安装依赖时会先执行 `preinstall` 钩子，`only-allow pnpm` 检测到有人用 `npm install` / `yarn install` 会直接报错退出。为什么这么凶？两个原因：

1. **幽灵依赖防护**：pnpm 默认严格隔离，只有 package.json 里声明的依赖才能 import。项目代码里用到 `@pureadmin/utils`、`responsive-storage` 这些"间接但显式"的依赖，用 npm 装可能因为提升规则不同而装出不同的 node_modules 结构；
2. **lockfile 一致性**：`pnpm-lock.yaml` 是唯一被提交的锁文件，Dockerfile 里 `pnpm install --frozen-lockfile`（`aihub-admin-ui/Dockerfile#L10`）要求本地与 CI/容器装出的依赖树逐字节一致。

### 3.3 shell-emulator：Windows 上的隐形功臣

`aihub-admin-ui/.npmrc#L1-4`：

```ini
# aihub-admin-ui/.npmrc#L1
shell-emulator=true
```

看一眼 dev 脚本：`"dev": "NODE_OPTIONS=--max-old-space-size=4096 vite"`（`aihub-admin-ui/package.json#L7`）。这种 `环境变量=值 命令` 的写法是 Unix shell 语法，**Windows 的 cmd/PowerShell 原生不认识**，直接跑会报 "'NODE_OPTIONS' 不是内部或外部命令"。pnpm 的 `shell-emulator=true` 让它内置的类 shell 模拟器（基于 shelljs）来解释脚本，跨平台统一行为。没有这一行，Windows 同事第一次 `pnpm dev` 就会怀疑人生。

顺带解读 `.npmrc` 另外两行：`shamefully-hoist=true` 把依赖平铺提升到 node_modules 根（兼容一些硬编码路径的老工具，如某些 icon 处理器）；`enable-pre-post-scripts=false` 关闭隐式 pre/post 脚本执行——注意这不影响 `preinstall`，`only-allow` 走的是 pnpm 特有的生命周期，依然生效。

### 3.4 端口、路由模式与环境变量

```properties
# aihub-admin-ui/.env#L2
VITE_PORT = 8849
```

```properties
# aihub-admin-ui/.env.development#L8
VITE_ROUTER_HISTORY = "hash"
```

- 端口 8849 在 `.env` 中声明（所有模式共享），dev server、preview 都用它；
- 路由用 **hash 模式**（URL 形如 `/#/system/user`）。为什么不用 history 模式？hash 模式刷新时浏览器不会向服务器发 `/system/user` 这种"前端路由路径"的请求，nginx 只需要托管 index.html 一个入口，部署最省心。`src/router/utils.ts#L338-358` 的 `getHistoryMode` 解析这个环境变量并返回 `createWebHashHistory` 实例；
- `vite.config.ts#L13-14` 通过 `wrapperEnv(loadEnv(mode, root))` 把字符串环境变量转成正确类型后使用，`VITE_PORT` 就是在 `vite.config.ts#L24` 交给 `server.port` 的。

### 3.5 命令表

| 命令 | 做什么 | 背后发生了什么 |
|---|---|---|
| `pnpm install` | 装依赖 | 先跑 `only-allow pnpm` 校验包管理器 |
| `pnpm dev` | 启动开发服务器（8849） | `NODE_OPTIONS=--max-old-space-size=4096 vite`：给 Node 4GB 堆内存防大项目 OOM |
| `pnpm serve` | 同 `pnpm dev` | 只是别名（`package.json#L8`） |
| `pnpm build` | 生产构建 | `rimraf dist` 清目录 + 8GB 堆内存 `vite build`（`package.json#L9`） |
| `pnpm typecheck` | 类型检查 | `tsc --noEmit && vue-tsc --noEmit --skipLibCheck`（`package.json#L14`） |
| `pnpm lint` | 三连检查+修复 | eslint → prettier → stylelint（`package.json#L20`） |
| `pnpm preview` | 本地预览构建产物 | 起静态服务器跑 dist |

构建产物结构由 `vite.config.ts#L50-67` 决定：`chunkFileNames: "static/js/[name]-[hash].js"` 让 JS/CSS/图片按类型分目录、带 hash 指纹，这正是 nginx 配置里 `/static/` 长缓存策略（3.6）的前提。

### 3.6 生产构建与容器化部署

`aihub-admin-ui/Dockerfile` 是标准的两阶段构建：

```dockerfile
# aihub-admin-ui/Dockerfile#L1-13（节选）
FROM node:20-alpine as build-stage
RUN corepack prepare pnpm@latest --activate
COPY .npmrc package.json pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY . .
RUN pnpm build

FROM nginx:stable-alpine as production-stage
COPY --from=build-stage /app/dist /usr/share/nginx/html
COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
ENV BACKEND_URL=http://host.docker.internal:8080
```

关键点是 nginx 配置模板（`aihub-admin-ui/deploy/nginx/default.conf.template`）：

```nginx
# aihub-admin-ui/deploy/nginx/default.conf.template#L24-46（节选）
location / {
    try_files $uri $uri/ /index.html;   # hash 路由兜底
}
location /auth/ {
    proxy_pass ${BACKEND_URL};          # 认证接口转发到网关
    ...
}
location /api/ {
    proxy_pass ${BACKEND_URL};          # 业务接口转发到网关
    proxy_read_timeout 120s;            # 知识库等长耗时接口放宽超时
    ...
}
```

文件头注释记录了一个真实的坑：**旧版 Dockerfile 只 COPY 了 dist，没有装代理配置——容器里页面能打开，但所有接口 404**。因为开发期 `/auth`、`/api` 是 Vite dev server 在代理，生产容器里没有 Vite，必须有 nginx 做同样的事。这印证了一个原则：**前端代理只是开发期拐杖，生产环境的同源方案必须显式设计**。

---

## 4. 网络层

### 4.1 代理拓扑：/auth 与 /api 双前缀

开发期跨域方案在 `aihub-admin-ui/vite.config.ts#L29-38`：

```ts
// aihub-admin-ui/vite.config.ts#L29-38
proxy: {
  "/auth": {
    target: "http://localhost:8080",
    changeOrigin: true
  },
  "/api": {
    target: "http://localhost:8080",
    changeOrigin: true
  }
}
```

设计逻辑：网关 8080 的全部业务路由恰好被 `/auth/**` 与 `/api/**` 两个前缀覆盖，所以两条代理规则就够，不需要维护路由清单。`changeOrigin: true` 把请求头的 Host 改写为 target 的地址，网关侧不感知代理存在。

为什么开发期用 Vite 代理而不是直接在网关配 CORS？——因为生产环境反正是 nginx 反代同源（3.6），开发期再用代理就和生产行为一致；CORS 方案则要求网关、浏览器预检、错误响应（4xx/5xx 也会被 CORS 拦）三处都做对，调试成本更高。这套"**开发代理 = 生产反代**"的对偶关系，排障时能少猜一层。

### 4.2 PureHttp：一个类的两道拦截

axios 封装在 `aihub-admin-ui/src/utils/http/index.ts`，导出单例 `http`（`#L242`）。骨架是：

```ts
// aihub-admin-ui/src/utils/http/index.ts#L41-57（节选）
class PureHttp {
  constructor() {
    this.httpInterceptorsRequest();   // 装请求拦截器
    this.httpInterceptorsResponse();  // 装响应拦截器
  }
  private static requests = [];              // token 过期后暂存的待重发请求
  private static isRefreshing = false;       // 防止并发重复刷新
  private static axiosInstance = Axios.create(defaultConfig);
}
```

`defaultConfig`（`#L18-30`）定义了 10 秒超时、JSON 头，以及用 qs 做数组参数序列化（解决 axios 官方 issue #5142 的数组参数格式问题）。

### 4.3 请求拦截器：token 注入与无感刷新

每次请求发出前（`aihub-admin-ui/src/utils/http/index.ts#L70-123`）按顺序做三件事：

**第一步：白名单放行**（`#L82-85`）。`/login` 与 `/refresh-token` 结尾的请求不注入 token——逻辑很直白：登录时还没有 token；刷新 token 的请求如果还带着过期 token 就会死循环。

**第二步：读 token 并判断过期**（`#L86-91`）。`getToken()`（`aihub-admin-ui/src/utils/auth.ts#L35-40`）优先从 `authorized-token` cookie 里取，取不到再查 localStorage 的 `user-info`。过期判断是纯前端时钟：`parseInt(data.expires) - now <= 0`。`expires` 是登录时按后端 JWT 有效期 7200 秒算出的时间戳（`aihub-admin-ui/src/api/user.ts#L47`：`TOKEN_TTL_MS = 7200_000`，注释明确要求与后端 `aihub.security.jwt-expire-seconds=7200` 对齐）。

**第三步：分三路处理**：
- 未过期 → 直接注入 `Authorization: Bearer <token>`（`#L109-112`，`formatToken` 在 `utils/auth.ts#L126-128`）；
- 已过期且是第一个撞上的请求 → 标记 `isRefreshing = true`，调 `handRefreshToken` 刷新，成功后重放当前请求并唤醒队列里所有等待者（`#L92-106`）；
- 已过期但刷新已在进行 → `retryOriginalRequest`（`#L60-67`）把请求包成 Promise 压进 `requests` 队列，等刷新完成后统一注入新 token 再放行。

这套"**刷新期间把后续请求排队、完成后统一重放**"的机制，就是经典的无感刷新方案。AIHub 的特殊之处：后端目前没有独立的 refreshToken 接口，`refreshTokenApi`（`api/user.ts#L106-118`）直接返回原 token 并顺延过期时间，但**队列机制原样保留**——将来后端补上真正的刷新接口，前端只需改这一个函数。

### 4.4 响应拦截器：R 解包、10002/401 双通道踢出、traceId

后端统一响应体是 `R{code, message, data}`：HTTP 状态码几乎总是 200，业务成败看 `code`。响应拦截器（`aihub-admin-ui/src/utils/http/index.ts#L126-194`）因此承担"协议翻译"职责：

```ts
// aihub-admin-ui/src/utils/http/index.ts#L152-168（节选）
if (body && typeof body.code === "number") {
  if (body.code === 0) {
    return body.data;              // 成功：调用方直接拿业务数据
  }
  if (body.code === 10002) {       // 业务层"未认证/令牌失效"
    removeToken();
    window.location.href = "/#/login";
  }
  const text = withTrace(body.message || "操作失败", traceId);
  message(text, { type: "error" });
  return Promise.reject(new Error(text));
}
```

三个设计点：

1. **解包**：`code === 0` 时返回 `body.data`，业务代码里 `const page = await http.request("get", "/api/.../page")` 拿到的直接就是分页对象，不存在 `res.data.data` 这种套娃；
2. **10002 与 401 双通道**：`code: 10002` 是业务层未认证（HTTP 仍是 200），走响应成功分支处理；而 HTTP 层的 401（网关直接拒绝）走错误分支（`#L176-179`）。两条路径殊途同归：`removeToken()` 清空 cookie + localStorage，然后跳 `/#/login`。**不清登录态直接跳转的踢出是有 bug 的**——下次守卫读到残留 token 又以为已登录，来回打摆；
3. **traceId 链路追踪**：网关为每个响应生成/透传 `X-Trace-Id`（`#L151`），出错时 `withTrace` 把它拼进提示语（`#L37-39`）。用户报障时把这串 ID 贴给后端，在 gateway/platform/ai 三份日志里 grep 就能还原完整调用链——这是前后端协作里成本最低的排障协议。

`#L162-163` 还留了一条类型层面的注释：`message(text, { type: "error" })` 第二参必须传对象，直接传字符串 `"error"` 会被当成 MessageParams，vue-tsc 报 TS2559，运行时弹的是默认样式而不是错误样式——典型的"类型系统防了运行时事故"的例子。

### 4.5 常见误用

- **绕过 http 直接用 axios**：项目里有两处刻意用"原始 axios"——`api/user.ts#L71-74` 和 `api/routes.ts#L76-79`（拉权限树）。原因：此时登录流程还没走完、`setToken` 尚未执行，走 http 拦截器反而拿不到/解不了包，所以手动带 `Authorization: Bearer ${token}` 并手动取 `res.data.data.routes`。**新写代码时不要无脑模仿**：只有"登录回调链条中、token 尚未落库"这个窗口期才需要原始 axios；
- **拿 http 请求 SSE**：管理端没有流式需求；用户端的 SSE 对话走的是 uni-app 原生请求，与本项目无关；
- **在业务代码里自己弹 401 提示**：拦截器已经统一处理，重复弹窗只会打扰用户。

---

## 5. 登录与动态路由全流程

这是本项目前端最核心、也是踩坑最密集的一条链路。先给全景图，再逐跳拆解：

```
登录页                 用户 store              路由层                        后端
────────────────────────────────────────────────────────────────────────────────
onLogin
  │ loginByUsername
  │   └→ getLogin ────────── POST /auth/login ──────→ 网关 → platform-service
  │        │                    {tenantId,userId,token}
  │        ├─ GET /api/platform/system/permission/info（手动带 token）
  │        ├─ cacheRoutes(routes)  ← 写入 api/routes.ts 内存缓存
  │        └─ 返回 UserResult{accessToken, roles, permissions...}
  │   setToken(data)  ← cookie 存 token，localStorage 存用户信息
  │ initRouter()
  │   └→ getAsyncRoutes ── 有缓存直接用 / 无缓存则带 token 重拉 permission/info
  │   └→ handleAsyncRoutes
  │        ├─ addAsyncRoutes：component 字符串 → import.meta.glob 组件
  │        ├─ formatFlatteningRoutes → addRoute 逐条注册
  │        └─ handleWholeMenus：静态+动态菜单合并 → 侧边栏渲染
  │ router.push(getTopMenu(true).path)
  ▼
进入首页 welcome
```

### 5.1 登录页：表单、验证码与防抖

`aihub-admin-ui/src/views/login/index.vue#L43-49` 的表单模型就是多租户契约的具象化：

```ts
// aihub-admin-ui/src/views/login/index.vue#L43-49
const ruleForm = reactive({
  tenantCode: "demo",       // 租户码：企业标识，注册即开新租户
  username: "admin",
  password: "123456",
  captchaId: "",
  captchaCode: ""
});
```

图形验证码的拉取（`#L52-63`）有个容错细节：`/auth/captcha` 拉取失败时**静默吞掉异常**，不阻塞登录表单渲染——因为后端验证码有开关，关闭时接口不可用是正常状态。

登录动作 `onLogin`（`#L65-99`）的完整时序：表单校验 → `loginByUsername`（进 user store）→ 成功后 `initRouter()` 拉动态路由 → `router.push(getTopMenu(true).path)` 跳到第一个菜单。两处防抖/防重：`debounce(formRef => onLogin(...), 1000, true)`（`#L101-105`）防连点；回车监听（`#L107-114`）里用 `disabled` 和 `loading` 双标志拦住请求进行中的重复提交。

### 5.2 getLogin：两次请求拼装 UserResult

`aihub-admin-ui/src/api/user.ts#L60-98` 是登录的数据编排骨架：

```ts
// aihub-admin-ui/src/api/user.ts#L62-82（节选）
const loginData = await http.request("post", "/auth/login", { data });
const token = loginData?.token;
if (!token) throw new Error("登录失败：未获取到令牌");

const permRes = await Axios.get("/api/platform/system/permission/info", {
  headers: { Authorization: `Bearer ${token}` },   // setToken 还没执行，手动带
  timeout: 10000
});
const permData = permRes.data?.data ?? { routes: [], permissions: [] };
cacheRoutes(permData.routes ?? []);   // 缓存给 getAsyncRoutes 用，避免二次请求
```

注意这里与 pure-admin 原版的差异：原版登录接口一个请求就返回 `{accessToken, roles, permissions}` 全家桶；AIHub 后端把"认证"和"权限"拆成两个接口（`/auth/login` 只管发 token；`/api/platform/system/permission/info` 一次返回 `{routes, permissions}`，见后端 `aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/controller/PermissionController.java#L63-72`）。所以前端在登录成功后**立刻串行拉一次权限**，并把结果组装成模板期望的 `UserResult` 结构：`roles` 给默认值 `["admin"]`（路由可见性实际由后端返回的 routes 决定，`#L90` 注释写明了这个约定），`refreshToken` 暂用 accessToken 复用（`#L93`）。

### 5.3 setToken：双存储设计

`aihub-admin-ui/src/utils/auth.ts#L48-116` 是模板的 token 存储核心，三份数据三处存放：

| 存储 | key | 内容 | 生命周期 |
|---|---|---|---|
| cookie | `authorized-token` | accessToken、expires、refreshToken | 按过期时间设置，到期自动销毁 |
| cookie | `multiple-tabs` | 固定值 "true" | 浏览器关闭即销毁（会话级） |
| localStorage | `user-info` | username、roles、permissions、expires… | 持久化 |

**为什么拆开？** token 相关的短命数据放 cookie 过期自动清理；用户信息相对稳定放 localStorage 减少解析开销。而"是否已登录"的判定用的是 `multipleTabsKey`：`router/index.ts#L150` 的守卫条件是 `Cookies.get(multipleTabsKey) && userInfo`——浏览器完全关闭后这个会话 cookie 消失，重新打开浏览器必须重新登录，即使 localStorage 里的 user-info 还在。这是"多标签页共享登录、关浏览器即失效"的实现基础。

### 5.4 initRouter → getAsyncRoutes → addAsyncRoutes：动态路由三级火箭

**第一级：initRouter**（`aihub-admin-ui/src/router/utils.ts#L200-227`）。它只是个调度器：`platform-config.json#L22` 中 `CachingAsyncRoutes: false`，所以走 else 分支——`getAsyncRoutes().then(({ data }) => handleAsyncRoutes(cloneDeep(data)))`。cloneDeep 很关键：handleAsyncRoutes 会原地修改路由对象（补 meta、redirect），不改副本的话下一次初始化拿到的是被污染的数据。

**第二级：getAsyncRoutes**（`aihub-admin-ui/src/api/routes.ts#L70-88`）：

```ts
// aihub-admin-ui/src/api/routes.ts#L70-88（节选）
export const getAsyncRoutes = async (): Promise<Result> => {
  if (!cachedMenuTree.length) {           // 内存缓存为空（刷新页面后必然为空）
    const { getToken } = await import("@/utils/auth");
    const token = getToken()?.accessToken;
    if (token) {
      const res = await Axios.get("/api/platform/system/permission/info", {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 10000
      });
      cacheRoutes(res.data?.data?.routes ?? []);
    }
  }
  const routes = cachedMenuTree
    .filter(node => node?.path !== "/welcome")   // 静态路由已有首页，去重
    .map(toPureRoute);
  return { success: true, data: routes };
};
```

登录场景下 `cachedMenuTree` 已由 getLogin 写入，零额外请求；**刷新场景**下内存缓存清空，这里用本地 token 兜底重拉同一个接口——刷新后菜单不丢的关键（8.2 节会复盘如果没有这段兜底会发生什么）。`filter(node => node?.path !== "/welcome")` 防止后端菜单里配置了 `/welcome` 时与静态路由 `home.ts` 重复注册。

**第三级：addAsyncRoutes**（`aihub-admin-ui/src/router/utils.ts#L309-335`）负责把"后端 JSON"变成"vue-router 能用的路由"：

```ts
// aihub-admin-ui/src/router/utils.ts#L309-329（节选）
function addAsyncRoutes(arrRoutes) {
  const modulesRoutesKeys = Object.keys(modulesRoutes);
  arrRoutes.forEach(v => {
    v.meta.backstage = true;                       // 打标：后端路由
    if (v?.children && v.children.length && !v.redirect)
      v.redirect = v.children[0].path;             // 目录默认重定向到第一个子路由
    if (v?.children && v.children.length && !v.name)
      v.name = v.children[0].name + "Parent";      // 父子 name 不能重复，否则重定向失效
    const index = v?.component
      ? modulesRoutesKeys.findIndex(ev => ev.includes(v.component))
      : modulesRoutesKeys.findIndex(ev => ev.includes(v.path));
    v.component = modulesRoutes[modulesRoutesKeys[index]];  // 字符串 → 组件加载器
    ...
  });
}
```

`modulesRoutes = import.meta.glob("/src/views/**/*.{vue,tsx}")`（`#L27`）在构建期扫描出所有视图组件的"路径 → 动态 import"映射表。后端菜单里的 `component: "system/user/index"` 就靠 `findIndex(ev => ev.includes(...))` 匹配到 `/src/views/system/user/index.vue`。**这套机制意味着后端存的 component 字符串必须与 views 目录结构严格对应**——菜单管理页的表单提示也写了这一点（`src/views/system/menu/index.vue#L199`："对应 src/views 下文件"）。

这里藏着一个当时排查了很久的坑：`findIndex` 匹配不到时返回 `-1`，`modulesRoutesKeys[-1]` 是 `undefined`，`v.component` 就变成 `undefined`——路由注册不报错，但点击菜单跳转时匹配不到组件，URL 变成 `#/undefined`。它正是第 8 章复盘的第一个 Bug 的一半成因。

**然后是 handleAsyncRoutes**（`aihub-admin-ui/src/router/utils.ts#L158-197`）：把拍平后的路由逐条 `router.options.routes[0].children.push(v)` + `router.addRoute(v)`，并保持 `router.options.routes` 与 vue-router 内部状态一致（`#L177-182`）；最后 `usePermissionStoreHook().handleWholeMenus(routeList)` 触发侧边栏重算。**push 进 options.routes 和 addRoute 缺一不可**：前者让菜单/标签页有数据源，后者才真正让路由可跳转——只做其一会出现"菜单有、点击 404"或"能跳、菜单没有"的诡异现象。

### 5.5 router/index.ts 的顶级叶子路由防御

`aihub-admin-ui/src/router/index.ts#L169-208` 的守卫分支处理"已登录 + 页面刷新"场景：刷新后 Pinia 内存清空，`wholeMenus.length === 0`，于是重新 `initRouter()`，然后根据当前地址把标签页恢复回来。恢复逻辑里有段防御代码，值得逐行讲：

```ts
// aihub-admin-ui/src/router/index.ts#L182-204
// 动态顶级"目录"路由（M 型，有 children）在标签页里展示其第一个子路由；
// 顶级叶子路由（如 /usage，无 children）直接展示自身
if (
  isAllEmpty(route.parentId) &&
  route.meta?.backstage &&
  route.children?.length
) {
  const { path, name, meta } = route.children[0];
  useMultiTagsStoreHook().handleTags("push", { path, name, meta });
} else {
  const { path, name, meta } = route;
  useMultiTagsStoreHook().handleTags("push", { path, name, meta });
}
```

三个条件缺一不可：

- `isAllEmpty(route.parentId)`：这是顶级路由（后端平铺结构里 parentId 为 0/空）；
- `route.meta?.backstage`：确实是后端下发的路由（`addAsyncRoutes#L314` 打的标），不误伤静态路由；
- `route.children?.length`：**它是"目录"才取第一个子路由**。

没有第三个条件的版本会在"顶级叶子路由"（如用量统计 `/usage`，后端配成顶级 C 型菜单、没有 children）上直接崩：`route.children[0]` 对 `undefined` 取下标抛 `TypeError`，`beforeEach` 守卫中断，页面白屏。这是第 8 章复盘的第三个 Bug，修法就是补上 `route.children?.length` 这一个条件，并把两种情况分流。

最后 `#L207` 的 `if (isAllEmpty(to.name)) router.push(to.fullPath)` 是为了应对"动态路由还没注册完守卫就跑完了"的时序：第一次匹配不到路由时再 push 一次，此时路由表已就绪。注释里也提醒：`beforeEach` 在刷新时可能触发两次，依赖 `to.name` 判断可以保证副作用只执行一次。

### 5.6 小结：为什么这套流程值得吃透

动态路由是"RBAC 页面级权限"的前端半边。后端只回答"这个用户能看哪些菜单"，前端负责把 JSON 变成真实可访问的路由和侧边栏。理解了 5.4/5.5 的每一跳，第 8 章的 5 个 Bug 你甚至能在纸上推演出故障现象。

---

## 6. 权限与菜单渲染

### 6.1 菜单从哪来：静态 + 动态合并

侧边栏不是直接渲染路由表，而是 Pinia 的 permission store 维护一份"菜单视图"（`aihub-admin-ui/src/store/modules/permission.ts#L27-34`）：

```ts
// aihub-admin-ui/src/store/modules/permission.ts#L27-34
handleWholeMenus(routes: any[]) {
  this.wholeMenus = filterNoPermissionTree(
    filterTree(ascending(this.constantMenus.concat(routes)))
  );
  this.flatteningRoutes = formatFlatteningRoutes(
    this.constantMenus.concat(routes) as any
  );
}
```

三步流水线：

1. **合并**：`constantMenus`（静态路由原始层级，`router/index.ts#L67-69`）+ 后端下发的动态路由；
2. **ascending 排序**：按 `meta.rank` 排（首页 rank 0 在最上，动态菜单的 rank 来自后端 `sort` 字段）；
3. **过滤**：`filterTree` 剔除 `showLink: false` 的项（如登录页），`filterNoPermissionTree` 再按角色过滤一遍（AIHub 场景下路由可见性已由后端 routes 决定，这层主要管静态路由）。

布局层的侧边栏组件订阅 `wholeMenus`，菜单项的图标是后端 `icon` 字段经 `toIconify`（`api/routes.ts#L31-35`）转换的：后端存 Element Plus 图标名 `HomeFilled`，前端转成 iconify 格式 `ep/home-filled` 与静态路由风格统一。

### 6.2 平铺 vs 嵌套：一次真实的结构取舍

后端权限接口返回的菜单树是**平铺结构**：

```json
{
  "id": 5, "name": "用量统计", "path": "/usage",
  "menuType": "C", "sort": 3, "parentId": 0,
  "children": []
}
```

前端要的 pure-admin 路由格式是**嵌套结构**（`children` 数组表达层级）。`api/routes.ts#L42-63` 的 `toPureRoute` 递归做这个翻译：M（目录）/C（菜单）节点进路由，F（按钮）节点在 `#L57` 被 `filter(c => c?.menuType !== "F")` 剔除——按钮权限不参与路由，走的是另一条路（见 6.3）。

为什么后端选平铺而不是像老版本那样返回 `{menu: {...}, children: [...]}` 的包装结构？三个理由（`aihub/aihub-platform-service/src/main/java/com/aihub/platform/system/vo/MenuVO.java#L10-16` 的类注释是这次变更的第一手记录）：

1. **取值少一层**：`node.name` 优于 `node.menu.name`，前端每一处消费点都少一次空判断；
2. **层级语义已由 children 表达**：再包一层 `menu` 没有信息增量，纯属冗余嵌套；
3. **与存储天然对齐**：数据库 `sys_menu` 表本来就是 `parent_id` 平铺存储，`MenuVO.from(DO)` 逐字段映射（`MenuVO.java#L36-50`），转换代码最短。

代价同样真实：**字段从包装层提升到本层，属于破坏性契约变更**。前端所有按 `node.menu.xxx` 取值的代码点全部失效——这个"全部"有多少个？答案是 5 个，而且散落在 5 个不同层次的文件里。这就是第 8 章要用整章篇幅复盘的事故。

### 6.3 按钮级权限：hasPerms 与 Auth/Perms 组件

页面级权限（能不能看到这个菜单）和按钮级权限（能不能点这个按钮）是两套机制：

- **页面级**：后端 routes 决定路由是否注册 → 菜单是否渲染；
- **按钮级**：`permission/info` 返回的 `permissions: string[]`（如 `["system:user:create"]`）存进 user store（`store/modules/user.ts#L55` 的 `SET_PERMS`），消费入口是 `hasPerms`（`aihub-admin-ui/src/utils/auth.ts#L131-141`）：

```ts
// aihub-admin-ui/src/utils/auth.ts#L131-141
export const hasPerms = (value: string | Array<string>): boolean => {
  if (!value) return false;
  const allPerms = "*:*:*";
  const { permissions } = useUserStoreHook();
  if (!permissions) return false;
  if (permissions.length === 1 && permissions[0] === allPerms) return true;
  const isAuths = isString(value)
    ? permissions.includes(value)
    : isIncludeAllChildren(value, permissions);
  return isAuths ? true : false;
};
```

模板配套了全局组件 `Auth`/`Perms`（`main.ts#L44-47` 注册）和 `v-auth` 指令，包裹按钮即可按权限码显隐。超管约定俗成用通配码 `*:*:*` 一票通过。后端这些权限码来自菜单表 F 型节点的 `permission` 字段（如 `system:user:create`），在菜单管理页维护（`views/system/menu/index.vue#L187-191` 表单里的"权限标识"输入框）。

---

## 7. 核心页面逻辑

管理端页面共 10 个业务视图，全部遵循同一范式：`<script setup>` + `reactive/ref` 状态 + `onMounted` 拉数据 + `http.request` 直连接口 + el-table/el-dialog 渲染。逐个过重点，不逐行复读。

### 7.1 首页 welcome：仪表盘

`aihub-admin-ui/src/views/welcome/index.vue`。数据源只有两个接口（`#L73-90`）：

- `GET /api/platform/usage/overview?days=N`：调用次数、Token 总量、平均耗时、使用模型数，喂给顶部四张渐变指标卡（`#L23-48`）；
- `GET /api/platform/usage/quota`：配额余量表格，使用率超过 90% 的行用 `el-progress` 的 `exception` 红色状态告警（`#L163-167`）。

时间窗口（今天/近 7 天/近 30 天）用 `el-radio-button` 切换，`@change="loadData"` 重新拉数。页面上还有一组快捷入口卡片（`#L51-71`），本质是 `router.push` 到各管理页的导航贴纸。

### 7.2 用量 usage：ECharts 双 Y 轴趋势图

`aihub-admin-ui/src/views/usage/index.vue` 比首页多三件事：

1. **四个接口并行语义、串行代码**（`#L28-54`）：overview、by-model、quota、trend 依次 await。管理端内网环境串行足够；真要优化可 `Promise.all`；
2. **ECharts 按需引入**：`import echarts from "@/plugins/echarts"`——插件文件里只 `use` 了 Line/Bar/Pie 与常用组件，避免整包引入 ECharts（全量约 1MB）；
3. **生命周期纪律**（`#L103-113`）：`onMounted` 里 `addEventListener("resize", onResize)` 让图表自适应窗口；`onBeforeUnmount` 里 `removeEventListener` + `chart?.dispose()`。注释写明了原因："纯 SPA 切路由不销毁 DOM 引用时会造成实例泄漏"。还有两处容易被忽略的渲染时序处理：数据回来后先 `await nextTick()` 再 `renderTrend()`（首次进入时容器还没渲染，`echarts.init` 会拿到 0 宽高，`#L51-53`）；图表容器必须有确定高度 `style="height: 280px"`（`#L141`）。

折线图用了**双 Y 轴**（`#L69-72`）：调用次数是百级、Token 量是百万级，共轴的话小数值曲线会被压成直线。

### 7.3 系统管理四页：RBAC 的操作台

**用户管理**（`views/system/user/index.vue`）：分页查询 `/api/platform/system/user/page`（`#L45-60`）+ 角色下拉 `/api/platform/system/role/list`（`#L62-67`）。编辑时先拉 `/user/{id}/role-ids` 回显已有角色（`#L86-90`）；提交时改昵称与分配角色分两个请求（`#L106-117`）——后端把"用户资料"与"用户-角色绑定"设计成两个资源，前端照实拆分。密码只在新增时传、编辑留空即不改。

**角色管理**（`views/system/role/index.vue`）：分页 + 关键字搜索（`#L34-49`）。核心是**菜单授权弹窗**（`#L85-114`）：

```ts
// aihub-admin-ui/src/views/system/role/index.vue#L85-101（节选）
const openAssign = async (row: RoleRow) => {
  assignTarget.value = row;
  const tree = await http.request("get", "/api/platform/system/menu/tree");
  menuTree.value = flatten(tree);                     // 平铺 MenuNode → el-tree 数据
  const checkedIds = await http.request(
    "get", `/api/platform/system/role/${row.id}/menu-ids`);  // 该角色已授权菜单 ID
  assignVisible.value = true;
  await nextTick();                                   // 等树渲染完再回显
  const leafIds = collectLeafIds(menuTree.value);
  treeRef.value?.setCheckedKeys(checkedIds.filter(id => leafIds.has(id)));
};
```

两个细节见功力：**回显只勾叶子**（`#L97-100`）——如果直接把后端存的全量 ID 塞给 `setCheckedKeys`，el-tree 的父子联动会把所有父节点也勾成全选态，保存时会把"用户从未授权过的父节点"一并提交，悄悄放大权限；只勾 F/C 叶子，父节点靠 `getHalfCheckedKeys` 半选态恢复。**保存时勾选+半选一起提交**（`doAssign`，`#L103-114`）——只提交全选节点的话，父菜单路径不会授权，用户能看到子菜单却因为缺父级路径而路由异常。这对"回显/保存"是一对镜像操作，理解一个另一个自然懂。

**菜单管理**（`views/system/menu/index.vue`）：el-table 树形展示（`row-key="id"` + `:tree-props`，`#L123-130`），数据来自 `flatten`（`#L30-35`）——把后端平铺 MenuNode 展开 `...n` 并附加 `typeLabel`（M/C/F → 目录/菜单/按钮）。新增时根据 `parentId` 智能预设类型：根节点默认 M 目录，子项默认 C 菜单（`#L56`）。编辑时菜单类型锁定不可改（`#L178` 的 `:disabled="editId != null"`），因为改类型会牵动路由/按钮两套语义。

**API Key**（`views/system/apikey/index.vue`）：列表 + 签发两个接口，签发后弹窗展示明文 key——**明文只在签发那一刻展示一次**，列表里只有掩码，符合密钥管理的通用安全约定。

### 7.4 AI 三页：模型、应用、知识库

**模型管理**（`views/ai/model/index.vue`）是三页里最复杂的，一次拉三份数据（`#L55-67`）：模型列表 `/api/ai/model`、供应商字典 `/api/ai/model/provider`（下拉选项）、模型路由 `/api/ai/model/route`（场景 → 主模型/备用模型的映射）。它有一处体现接口设计经验的小交互：编辑模型时 `apiKey` 输入框留空表示"保持原密钥"，提交时 `if (form.apiKey) payload.apiKey = form.apiKey`（`#L106`）——留空不传字段，后端就不覆盖。**密钥类字段永远不能把掩码回显值再提交回去**，这个"留空即不改"的约定值得记住。

**应用管理**（`views/ai/app/index.vue`）：应用的 `systemPrompt`（系统提示词）、`temperature`、`agentStrategy`（none=直接对话，其他值走 Agent 循环）都在这里配置，默认提示词在 `#L43` 给了一个工程化的示例。AI 对话本身不在这个管理端，管理端只管"配置"。

**知识库**（`views/ai/kb/index.vue`）：最简页面，列表 + 新建。页面提示语（`#L45-47`）交代了它在 RAG 链路里的位置：创建后绑定到应用（`/api/ai/kb/bind`），文档上传与检索在用户端发生，管理端只负责建库。

### 7.5 页面范式总结：30 分钟能上线一个新页

所有页面共享同一套约定，新增一个管理页的步骤因此高度可预期：

1. 在 `src/views/<module>/<page>/index.vue` 建文件，`defineOptions({ name: "XxxYyy" })`（name 与 keep-alive 缓存和路由 name 对齐）；
2. `http.request("get", "/api/...")` 拉数据，`ref/reactive` 存状态；
3. el-table 渲染 + el-dialog 编辑 + `message()` 反馈；
4. 在数据库 `sys_menu` 里插一条 C 型菜单，`component` 填 `<module>/<page>/index`；
5. 给角色授权该菜单，重新登录（或刷新）即可见。

**不需要改任何前端路由代码**——这正是动态路由体系的价值：路由注册权交给数据，新页面上线只动数据库。

---

## 8. 实战复盘：5 个前后端结构不匹配 Bug

这一章是本文最有面试价值的部分。背景：后端菜单 VO 从嵌套包装结构重构为平铺结构（`MenuVO.java#L12` 注释记录了变更："替代原先的 MenuNode record 包装（{menu:{...}, children}）——直接把字段提升到 VO 本层"），而前端 5 处消费点仍按旧契约取值，引发连锁故障。5 个 Bug 按"现象 → 定位 → 修复 → 教训"复盘，修复前代码按 MenuVO 注释记录的旧契约（`node.menu.xxx` 包裹取值）还原。

### Bug 1：动态菜单全部变成 `#/undefined`

**现象**：登录成功后跳转 `#/undefined`；即使进了首页，侧边栏里点任何动态菜单，地址栏都变成 `#/undefined`，内容区显示 404。静态路由（首页、错误页）完全正常。

**定位**：打开 `api/routes.ts` 检查 `toPureRoute`。旧代码按旧契约取值：

```ts
// 修复前（按旧契约 {menu:{...}} 包裹取值）
const menu = node.menu;                       // 新结构下 node 上没有 menu 字段 → undefined
const route = {
  path: menu.path ?? `/${menu.id}`,           // undefined ?? "/undefined" → "/undefined"
  ...
};
```

新契约下 `node.menu` 是 `undefined`，`menu.path` 会直接抛 TypeError 吗？不会——旧代码顶部有 `const menu = node?.menu ?? {}` 兜底，于是所有字段都是 `undefined`，再被 `??` 默认值接住：`path` 变成 `"/" + undefined` 即字符串 `"/undefined"`，`meta.title` 是 `undefined`。这批 `"/undefined"` 路由经 `addAsyncRoutes` 注册后，点击菜单自然跳 `#/undefined`。

**修复**（`aihub-admin-ui/src/api/routes.ts#L42-63`）：

```ts
// 修复后（平铺直取，aihub-admin-ui/src/api/routes.ts#L42-63）
const toPureRoute = (node: any): any => {
  const menu = node ?? {};
  const route: any = {
    path: menu.path ?? `/${menu.id}`,
    component: menu.component ?? undefined,
    meta: {
      title: menu.name,
      icon: toIconify(menu.icon),
      rank: menu.sort ?? 99,
      showLink: menu.visible !== 0
    }
  };
  const children = (menu.children ?? [])
    .filter((c: any) => c?.menuType !== "F")   // 按钮不进路由
    .map(toPureRoute);
  if (children.length > 0) route.children = children;
  return route;
};
```

**教训**：`??` 默认值是把双刃剑——它让 `node?.menu ?? {}` 这种"对齐性兜底"静默吞掉契约破坏，把本该当场爆的 TypeError 推迟成更难排查的 `#/undefined`。**对结构性字段不要兜成空对象再取值；宁可让它崩在第一现场**。另外 `/${menu.id}` 这种兜底 path 也是坏味道：id 兜底出来的路径毫无业务含义，不如显式报错。

### Bug 2：F5 刷新后侧边栏菜单整体消失

**现象**：登录后一切正常；按 F5 刷新（或地址栏回车），能进当前页面（动态路由还在），但**侧边栏动态菜单全没了**，只剩静态的"首页"；点浏览器其他标签页按钮也无效。

**定位**：刷新后 Pinia 重置、`wholeMenus` 为空，守卫里 `initRouter()` 重新走 `getAsyncRoutes`。加日志发现这次调用返回的 `data` 是**空数组**——为什么登录时好好的？对照两条路径：

- 登录路径：`getLogin` 拉完权限树后 `cacheRoutes(permData.routes)` 写入内存缓存，`getAsyncRoutes` 直接读缓存返回；
- 刷新路径：内存缓存随页面刷新清空，而旧版 `getAsyncRoutes` **只在登录流程里被喂过数据，自身没有兜底重拉逻辑**（模板原版行为是从 mock 读，AIHub 砍 mock 后这条腿断了），于是返回空数组 → `handleAsyncRoutes([])` → 菜单为空。

**修复**（`aihub-admin-ui/src/api/routes.ts#L70-82`）：给 `getAsyncRoutes` 补上"缓存为空 + 本地有 token"时的兜底重拉：

```ts
// 修复后（aihub-admin-ui/src/api/routes.ts#L70-88）
export const getAsyncRoutes = async (): Promise<Result> => {
  if (!cachedMenuTree.length) {                 // 页面刷新后内存缓存清空，走这里
    const { getToken } = await import("@/utils/auth");
    const token = getToken()?.accessToken;
    if (token) {
      // 走原始 axios（不经 http 拦截器解包），手动取 R.data.routes
      const res = await Axios.get("/api/platform/system/permission/info", {
        headers: { Authorization: `Bearer ${token}` },
        timeout: 10000
      });
      cacheRoutes(res.data?.data?.routes ?? []);
    }
  }
  const routes = cachedMenuTree
    .filter(node => node?.path !== "/welcome")
    .map(toPureRoute);
  return { success: true, data: routes };
};
```

文件头注释（`api/routes.ts#L66-69`）明确写了这段设计的意图："优先用登录流程（api/user.ts）缓存的菜单树；页面刷新后内存缓存清空，此时用当前 token 重新拉取权限树（与登录时同一个接口），保证刷新后菜单不丢。"

**教训**：**内存缓存必须有"冷启动"路径**。凡是在登录流程里"顺手写入"的内存状态，都要问一句：F5 之后它还在吗？不在的话兜底从哪来？判断依据其实一直摆在守卫代码里——`router/index.ts#L169-173` 刷新分支本来就设计成"菜单为空就重新 initRouter"，断掉的只是 getAsyncRoutes 的数据来源。

### Bug 3：刷新到顶级叶子路由白屏（router/index.ts 崩溃）

**现象**：点击"用量统计"（`/usage`，后端配置为**顶级 C 型菜单**，无 children）后 F5 刷新，页面直接白屏，控制台抛 `TypeError: Cannot read properties of undefined (reading '0')`。刷新到其他目录型菜单（如系统管理，有子菜单）则正常。

**定位**：白屏发生在 `router/index.ts` 的刷新恢复逻辑。旧代码假设"后端顶级路由一定是目录"，无条件取第一个子路由塞进标签页：

```ts
// 修复前：顶级路由一律按"目录"处理
const route = findRouteByPath(path, router.options.routes[0].children);
const { path, name, meta } = route.children[0];   // /usage 没有 children → TypeError
useMultiTagsStoreHook().handleTags("push", { path, name, meta });
```

守卫里抛出未捕获异常 → `next()` 永远没被执行 → 路由解析中断 → 白屏。

**修复**（`aihub-admin-ui/src/router/index.ts#L182-204`，完整代码见 5.5 节）：加 `route.children?.length` 条件，目录型取第一个子路由，**叶子型直接展示自身**：

```ts
// 修复后（aihub-admin-ui/src/router/index.ts#L185-203）
if (
  isAllEmpty(route.parentId) &&
  route.meta?.backstage &&
  route.children?.length            // ← 关键防御：没有 children 就是叶子路由
) {
  const { path, name, meta } = route.children[0];   // 目录：标签页展示第一个子路由
  useMultiTagsStoreHook().handleTags("push", { path, name, meta });
} else {
  const { path, name, meta } = route;               // 叶子：直接展示自身
  useMultiTagsStoreHook().handleTags("push", { path, name, meta });
}
```

**教训**：模板代码里的隐含假设（"后端菜单顶层都是目录"）在定制后端数据结构时必须重新审视。**防御性判断要打在"数据形状可以变化"的边界上**——`route.children?.length` 比 `route.children && route.children.length > 0` 更短也更安全（顺带处理了 children 为 undefined 的可能）。同类问题在 `router/utils.ts#L377-387` 的 `handleTopMenu` 里模板反而处理对了（`if (route?.children && route.children.length > 1)`），说明原作者知道这个坑，只是守卫里那处漏了——**同一个假设在多处出现时，只要一处漏防就是事故**。

### Bug 4：菜单管理页表格空白

**现象**：进入"菜单管理"，el-table 渲染出表格框架和表头，但**数据行全部空白**（不是没有行——行数和菜单数一致，行内所有单元格是空的）；展开箭头显示异常，`default-expand-all` 也不生效。

**定位**：`views/system/menu/index.vue` 的 `flatten` 仍按旧契约取值：

```ts
// 修复前：按 {menu:{...}} 包裹取值
const flatten = (nodes: any[]): any[] =>
  (nodes ?? []).map(n => ({
    id: n.menu.id,           // undefined → row-key 全部相同
    name: n.menu.name,       // undefined → 单元格空白
    menuType: n.menu.menuType,
    children: flatten(n.children)   // 注意：递归还在用 n.children（新结构），里层全是坏数据
  }));
```

有意思的是故障表现"行在但空"：外层 `nodes` 数组来自新结构（有 N 个节点），所以行数对；但每行字段从 `n.menu` 上取全是 `undefined`。加上 `row-key="id"` 全部是 `undefined`，el-table 的树形渲染内部按 key 建索引时 key 冲突，展开逻辑随之失效。

**修复**（`aihub-admin-ui/src/views/system/menu/index.vue#L30-48`）：

```ts
// 修复后（aihub-admin-ui/src/views/system/menu/index.vue#L29-35）
/** MenuNode（平铺结构 {id,name,menuType,...,children}）→ el-table 树行 */
const flatten = (nodes: any[]): any[] =>
  (nodes ?? []).map(n => ({
    ...n,                                        // 平铺字段整体展开
    typeLabel: typeLabel[n.menuType] ?? n.menuType,
    children: flatten(n.children)
  }));

const loadData = async () => {
  loading.value = true;
  try {
    const tree: any = await http.request("get", "/api/platform/system/menu/tree");
    treeData.value = flatten(tree);
  } finally {
    loading.value = false;
  }
};
```

修复用 `...n` 整体展开替代逐字段挑选——字段变更时这个函数几乎不用动，只有派生字段（typeLabel）单独算。

**教训**：**el-table/el-tree 这类依赖 row-key/node-key 建内部索引的组件，key 字段损坏的故障表现是"渲染错乱"而不是"报错崩溃"**，非常具有迷惑性。排查这类问题最快的手段是在 `flatten` 返回前 `console.log(JSON.stringify(treeData.value))` 看数据形状，而不是先怀疑组件配置。

### Bug 5：角色授权弹窗崩溃（el-tree 树不渲染）

**现象**：角色管理页点击"菜单授权"，弹窗打开后**授权树空白**，控制台报 `Error: [el-tree] node-key is required or duplicated`（node-key 重复/缺失类错误），保存按钮点了没反应。

**定位**：`views/system/role/index.vue` 的 `flatten` 旧版同样按 `n.menu.id / n.menu.name` 取值。与 Bug 4 同源但故障面更大：

1. 树数据字段全 `undefined` → el-tree 的 `node-key="id"` 全部重复 → 树内部索引构建报错、渲染中断；
2. 更隐蔽的是**授权回显链路**：`openAssign` 拉回的 `checkedIds` 是数字数组（这部分正常），但 `setCheckedKeys` 对着一棵 id 全是 undefined 的树设置勾选，匹配不上任何节点；保存时 `getCheckedKeys()` 返回空 → 用户以为勾选了，提交的 `menuIds` 却是空/残缺数组——**这是静默的数据错误，比崩溃更危险**。

**修复**（`aihub-admin-ui/src/views/system/role/index.vue#L117-130`）：

```ts
// 修复后（aihub-admin-ui/src/views/system/role/index.vue#L116-130）
/** MenuNode 树（平铺结构）→ el-tree 数据 */
const flatten = (nodes: any[]): any[] =>
  (nodes ?? []).map(n => ({
    id: n.id,
    name: `${n.name}（${n.menuType}）`,   // 节点名后缀标注类型，授权时一眼分辨 M/C/F
    children: flatten(n.children)
  }));

const collectLeafIds = (nodes: any[], set = new Set<number>()): Set<number> => {
  for (const n of nodes) {
    if (!n.children || n.children.length === 0) set.add(n.id);
    else collectLeafIds(n.children, set);
  }
  return set;
};
```

配套的回显/提交逻辑（8 章前 7.3 节已详述）：回显只勾叶子（`#L97-100`），保存勾选+半选一起提交（`#L103-114`）。

**教训**：**同一份树数据被路由层和 UI 层两路消费，契约破坏会同时表现为"崩溃"和"静默错误"两种截然不同的故障面**。排查时不要被"另一个页面是好的"迷惑——menu/index.vue 和 role/index.vue 各有一份 flatten 拷贝，修一个不等于修了另一个。这也是为什么 `MenuVO.java#L14-16` 的注释特意加了一句："改了本 VO 的字段结构必须同步调整管理端 system/role、system/menu 两个页面的树数据处理"——**把跨端同步义务写进代码注释，是团队协作里成本最低的契约守护**。

### 8.6 五个 Bug 的合并复盘：契约治理

五个 Bug 一个根因、五种表现，覆盖了前端消费后端结构的全部层次：

| # | 故障点 | 层次 | 故障表现 | 直接根因 |
|---|---|---|---|---|
| 1 | `api/routes.ts` | 动态路由解析 | `#/undefined` | `node.menu` 取值落空被 `??` 兜底 |
| 2 | `api/routes.ts` | 数据冷启动 | 刷新后菜单消失 | 内存缓存无兜底重拉 |
| 3 | `router/index.ts` | 路由守卫 | 顶级叶子路由白屏 | 无条件 `children[0]` |
| 4 | `views/system/menu` | UI 表格 | 行在但空白 | flatten 按 `n.menu` 取值 |
| 5 | `views/system/role` | UI 树+回显 | 树空白+静默丢授权 | flatten 按 `n.menu` 取值 |

三条可复用的治理经验：

1. **把 API 响应结构当显式契约管理**：字段名、嵌套层级、枚举值（M/C/F）都写进类型定义，消费端不写 `node?.menu ?? {}` 这种吞错兜底；变更要么向后兼容，要么盘点全部消费方一次性改齐（本项目 5 处就是"没盘点"的学费）；
2. **UI 组件的"静默故障"优先查数据形状**：表格空行、树不渲染、勾选丢失，八成是数据没到位而不是组件配置错；
3. **内存状态必须有冷启动路径**：登录时写入的缓存，F5 后从哪恢复，设计期就要有答案。

---

## 9. 本章面试三问与参考回答

**Q1：你们的 Vue3 后台是怎么做"页面级权限"的？如果不用后端下发路由，还有什么方案？**

参考回答：我们用"后端下发菜单 + 前端动态注册"方案。登录后前端调权限接口拿到该用户的菜单树（M 目录/C 菜单/F 按钮三类节点），一个递归转换函数把平铺 JSON 翻译成 vue-router 路由对象——component 存的是字符串路径，前端用 `import.meta.glob("/src/views/**/*.vue")` 在构建期生成的"路径 → 动态 import"映射表把它变成真实组件，再逐条 `addRoute` 注册，同时把静态+动态路由合并进 Pinia 驱动侧边栏渲染。F 型按钮节点不进路由，其 permission 码存全局 store，供 `hasPerms` 函数和 v-auth 指令做按钮级显隐。备选方案是前端写死全量路由、登录后按角色过滤——优点是简单，缺点是路由信息泄露（用户能从 devtools 看到全部路由）、加页面要发前端版本；动态方案把"谁能看什么"完全交给数据，新页面上线只插一条菜单记录。追问点通常在"直接改 URL 访问未授权路由怎么办"——答：路由根本没注册，vue-router 会命中 404 兜底（`addPathMatch` 加的 pathMatch 路由）；但这只是体验层防御，真正的安全边界在后端——每个接口都有网关 JWT 校验 + 权限码校验，前端权限只管"不展示"，不管"不信任"。

**Q2：讲讲你实现的 token 无感刷新，并发请求时怎么保证不重复刷新、不丢请求？**

参考回答：在 axios 请求拦截器里做。每次请求前从 cookie 读 token 和过期时间戳，未过期直接注入 `Authorization: Bearer xxx`；发现过期时用两个静态变量协调并发：`isRefreshing` 标志保证只有第一个撞上的请求去调刷新接口，其余请求进入一个 pending 队列（每个元素是包着 resolve 的 Promise）；刷新成功后，先重放触发刷新的那个请求，再遍历队列给每个等待者注入新 token 并 resolve，最后清队列、复位标志。有两个细节容易忽略：一是 `/login` 和 `/refresh-token` 必须放白名单，否则刷新请求自身也带过期 token，死循环；二是刷新接口失败要清登录态踢回登录页，不能让队列无限等待。我们项目当前后端没有独立 refresh 接口（token 有效期 2 小时，过期直接重新登录），但队列机制完整保留，将来后端补接口前端只改一个函数——这就是把"策略"和"机制"分离的好处。

**Q3：一次后端接口结构变更引发了前端 5 处故障，从这件事里你总结出什么工程化改进？**

参考回答：这是真实发生的事故——后端菜单 VO 从 `{menu:{...}, children}` 包装结构改成平铺结构，前端 5 处消费点（动态路由解析、刷新兜底、路由守卫、菜单表格、角色授权树）全按旧契约取值，依次表现为 `#/undefined`、刷新丢菜单、顶级叶子路由白屏、表格空白、授权树崩溃。三点改进：第一，接口响应当显式契约——用 TypeScript 定义响应类型，消费端禁止 `node?.menu ?? {}` 这类吞错兜底，结构不对就让它在第一现场抛 TypeError，而不是被默认值推迟成 `#/undefined` 这种隔山打牛的故障；第二，跨端结构变更必须盘点消费方，我们把"改此 VO 必须同步前端两个页面"写进了后端 VO 的类注释，更进一步的做法是接口契约测试（schema 快照比对）；第三，给"静默故障"建立肌肉记忆——表格行在但空、树不渲染、勾选悄悄丢失，这类 el-table/el-tree 的故障九成是数据形状问题，先打数据再查配置。这次事故还让我养成了复盘时画"故障面矩阵"的习惯：一个根因 × 五个层次，每个层次的表现、根因、修复各自不同，合并复盘才能提炼出普适规则而不是五个孤立 bugfix。

---

## 附：本文引用文件清单

| 文件 | 主要引用内容 |
|---|---|
| `aihub-admin-ui/package.json` | 版本、脚本、engines、preinstall |
| `aihub-admin-ui/.npmrc` | shell-emulator 等 pnpm 配置 |
| `aihub-admin-ui/.env` / `.env.development` / `.env.production` | 端口 8849、hash 路由模式 |
| `aihub-admin-ui/vite.config.ts` | /auth /api 代理、构建产物结构 |
| `aihub-admin-ui/build/plugins.ts` | 插件清单、移除 fake-server 的记录 |
| `aihub-admin-ui/src/main.ts` | 应用启动装配顺序 |
| `aihub-admin-ui/src/config/index.ts` / `public/platform-config.json` | 运行时全局配置、CachingAsyncRoutes |
| `aihub-admin-ui/src/utils/http/index.ts` / `types.d.ts` | PureHttp 封装、拦截器、R 解包 |
| `aihub-admin-ui/src/utils/auth.ts` | token 双存储、hasPerms |
| `aihub-admin-ui/src/api/user.ts` / `src/api/routes.ts` | 登录编排、动态路由获取 |
| `aihub-admin-ui/src/router/index.ts` / `utils.ts` / `modules/*` | 路由实例、动态路由算法、顶级叶子防御 |
| `aihub-admin-ui/src/store/modules/user.ts` / `permission.ts` | 用户态、菜单组装 |
| `aihub-admin-ui/src/views/**` | 登录、首页、用量、系统四页、AI 三页 |
| `aihub-admin-ui/Dockerfile` / `deploy/nginx/default.conf.template` | 容器化与生产代理 |
| `aihub/aihub-platform-service/.../PermissionController.java` / `MenuVO.java` | 权限接口契约、平铺结构变更记录 |
