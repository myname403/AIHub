# 11 - 用户端前端 aihub-app 讲解

> 本文是《AIHub 项目全面讲解》系列第 11 篇，讲解用户端前端 `aihub-app/`。它基于 uni-app + Vue3，**同一份源码同时产出 H5 网页与微信小程序**两个客户端。本文自成一体，不依赖阅读本系列其他文档；所有代码引用均标注真实文件路径与行号。

---

## 1. 定位：uni-app + Vue3 一套代码产出 H5 与微信小程序

### 1.1 它在整个系统中的角色

AIHub 是一个多租户 AI 能力中台，面向三类角色：平台管理员、企业租户管理员、终端用户。

- **管理端**（Vue3 + Element Plus，见系列第 10 篇）服务于前两类角色，是"配置 AI 平台"的地方；
- **用户端**（本文的 aihub-app）服务于**终端用户**，是"使用 AI"的地方：聊天对话、RAG 知识库问答、Agent 任务拆解，同时附带一个轻量管理页，让租户管理员在手机上也能完成模型配置与用量查看。

用户端不经过管理端的任何前端代码，它与后端的交互**全部直连网关 8080**：

```text
用户手指点开的页面
├── H5（浏览器）：npm run dev:h5 → http://localhost:5173
└── 微信小程序：npm run dev:mp-weixin → 微信开发者工具导入 dist/dev/mp-weixin
        │
        │  uni.request / fetch（携带 JWT）
        ▼
网关 aihub-gateway :8080 ──→ /auth/**    → platform-service :8081（登录/注册/验证码/用量）
                        └──→ /api/**     → ai-service :8082（对话/知识库/模型/应用）
```

### 1.2 技术栈与版本（`aihub-app/package.json`）

```json
// aihub-app/package.json#12-24
"dependencies": {
  "@dcloudio/uni-app": "3.0.0-alpha-5020520260829001",
  "@dcloudio/uni-components": "3.0.0-alpha-5020520260829001",
  "@dcloudio/uni-h5": "3.0.0-alpha-5020520260829001",
  "@dcloudio/uni-mp-weixin": "3.0.0-alpha-5020520260829001",
  "vue": "^3.5.42"
},
"devDependencies": {
  "@dcloudio/uni-cli-shared": "3.0.0-alpha-5020520260829001",
  "@dcloudio/uni-stacktracey": "3.0.0-alpha-5020520260829001",
  "@dcloudio/vite-plugin-uni": "3.0.0-alpha-5020520260829001",
  "vite": "^5.4.21"
}
```

三个选型要点，每个都回答"为什么"：

| 选型 | 为什么选它 | 备选是什么 |
| --- | --- | --- |
| uni-app（Vue3 语法） | 终端用户天然分布在"浏览器 + 微信"两个生态，一套代码双端发布可把维护成本砍半；Vue3 语法让会 Vue 的开发者零学习成本 | 各写一套 H5（Vue/React）+ 小程序（原生 WXML）；或用 Taro（React 系） |
| Vite 5（不是 7） | `@dcloudio/vite-plugin-uni` 对 Vite 大版本有强约束，本锁定的 alpha 版配套 Vite 5；管理端敢用 Vite 7 是因为它没有 uni 插件这个枷锁 | 升级到 Vite 7 需等 uni 官方适配 |
| 无 UI 组件库 | 移动端页面少（8 个），手写 rpx 样式反而包体更小、跨端样式更可控（部分组件如 `picker`、`scroll-view` 是 uni 内置组件，双端各有原生实现） | uview-plus / uni-ui |

`package.json` 里还有一条工程约束：

```json
// aihub-app/package.json#25-27
"engines": {
  "node": ">=18"
}
```

管理端（第 10 篇）踩过 Vite 7 + Node 16 报 `crypto.getRandomValues` 的坑，用户端把引擎要求直接声明为 `>=18`，从声明层面挡住低版本 Node。

### 1.3 跨端原理：编译期条件编译 + 运行时平台适配层

"一套代码产出两个端"不是魔法，由两个机制配合完成：

**机制一：编译期条件编译（#ifdef / #ifndef）**

`.vue` 和 `.js` 源文件里可以写特殊注释块，编译 H5 时只保留 `#ifdef H5` 的代码，编译小程序时只保留 `#ifdef MP-WEIXIN` 的代码——**两端各自拿到的是"裁剪后的不同源码"**，所以平台差异代码不会互相污染，也不会把浏览器才有的 `fetch`、`window` 打进小程序包。本项目三处典型用法：

```js
// aihub-app/common/config.js#6-11
// #ifdef H5
baseUrl: 'http://localhost:8080',
// #endif
// #ifndef H5
baseUrl: 'http://192.168.1.100:8080',
// #endif
```

```js
// aihub-app/common/stream.js#57-100（节选：同一函数内，两端各留一份实现）
// #ifdef H5
h5Controller = new AbortController()
fetch(config.baseUrl + '/api/ai/chat/ndjson', { ... })
// #endif

// #ifdef MP-WEIXIN
const task = uni.request({ url: config.baseUrl + '/api/ai/chat/ndjson', enableChunked: true, ... })
// #endif
```

**机制二：运行时平台适配层（uni API 统一外观）**

`uni.xxx` 是 DCloud 定义的统一 API 门面（Facade）。源码里写 `uni.request`、`uni.setStorageSync`、`uni.navigateTo`，编译到 H5 时被映射为 `XMLHttpRequest/fetch`、`localStorage`、history 路由；编译到小程序时映射为 `wx.request`、`wx.setStorageSync`、小程序页面栈。开发者面对的永远是同一套 API 签名。

**机制三：编译产物形态不同**

- H5：产出标准 SPA（入口 `aihub-app/index.html#20` 的 `<script type="module" src="/main.js">`），由 `manifest.json#39-48` 的 `h5` 节点配置 hash 路由与 5173 端口；
- 微信小程序：产出 `dist/dev/mp-weixin` 下的 `app.json/wxml/wxss/js`，页面结构由 Vue 模板经编译器转成 WXML，由微信开发者工具加载运行。

一句话总结跨端本质：**条件编译解决"平台独有能力怎么写"，适配层解决"公共能力怎么统一"，编译产物解决"两端各自要什么格式"**。

### 1.4 面试怎么说

> "用户端我用 uni-app 是因为终端用户同时存在于浏览器和微信两个生态。跨端的关键是三层机制：`#ifdef` 条件编译在编译期裁剪平台差异代码；`uni.*` 适配层在运行时把统一 API 翻译成各端实现；编译器把同一份 Vue 模板分别产出 SPA 与小程序 WXML。这个项目里流式对话是差异最大的点，我们在 stream.js 里用条件编译让 H5 走 fetch + ReadableStream、小程序走 enableChunked 分块接收，两端共享同一套 NDJSON 解析逻辑。"

---

## 2. 目录结构

### 2.1 文件树与职责

```text
aihub-app/
├── package.json            # 依赖与 4 条 npm 命令（全部经由 scripts/uni.js 转发）
├── scripts/
│   └── uni.js              # ★ 自研启动器：在 CLI 启动前设置 UNI_INPUT_DIR（见第 3 章）
├── vite.config.js          # Vite 配置：仅挂载 uni 插件 + UNI_INPUT_DIR 兜底
├── index.html              # H5 SPA 入口 HTML
├── main.js                 # 应用入口：createSSRApp（uni-app 约定写法）
├── App.vue                 # 全局生命周期：启动时校验登录态
├── manifest.json           # 应用清单：appid、H5 devServer、小程序设置
├── pages.json              # 页面注册表：8 个页面的路径与导航栏标题
├── common/
│   ├── config.js           # 全局配置：条件编译的 baseUrl、默认场景
│   ├── request.js          # HTTP 封装：login/logout/request/get/post/put/del + token 存取
│   └── stream.js           # ★ 流式对话封装：NDJSON chunked 双端统一实现
└── pages/
    ├── chat/chat.vue       # 首页：流式对话（对话 / 知识库 / Agent 三场景 + 模型切换）
    ├── login/login.vue     # 登录：租户码 + 用户名 + 密码 + 图形验证码
    ├── register/register.vue # 注册新企业 = 自助开通新租户
    ├── kb/kb.vue           # 知识库管理：建库 / 上传文档 / 轮询入库进度 / 重试 / 绑定应用
    └── admin/
        ├── index.vue       # 管理后台入口（三个卡片导航）
        ├── usage.vue       # 用量看板：调用次数 / Token / 趋势柱状图 / 配额
        ├── model.vue       # 模型管理：模型实例 CRUD + 场景路由
        └── app.vue         # 应用配置：助手 CRUD（提示词 / Agent 策略 / 记忆策略 / 温度）
```

与 uni-app CLI 默认脚手架最大的不同：**本项目没有 `src/` 目录**，`manifest.json`、`pages.json`、`pages/` 全部在项目根目录——这是 HBuilderX 可视化工具的目录约定。这个选择引发了一次真实启动故障，第 3 章完整复盘。

### 2.2 页面注册表（`aihub-app/pages.json`）

uni-app 没有 vue-router，"路由表"就是 `pages.json`：

```json
// aihub-app/pages.json#2-51（节选首页与前两页）
{
  "pages": [
    {
      "path": "pages/chat/chat",
      "style": { "navigationBarTitleText": "AIHub 对话" }
    },
    {
      "path": "pages/login/login",
      "style": { "navigationBarTitleText": "登录" }
    },
    ...
  ],
  "globalStyle": {
    "navigationBarTextStyle": "black",
    "navigationBarTitleText": "AIHub",
    "navigationBarBackgroundColor": "#FFFFFF",
    "backgroundColor": "#F5F6F8"
  }
}
```

两条规则值得记住：

- **数组第一项是首页**：`pages/chat/chat` 排第一，所以 H5 打开与小程序冷启动都先进入对话页，再由它跳转登录/知识库/管理页；
- **没有 tabBar**：8 个页面全靠 `uni.navigateTo` / `uni.reLaunch` 跳转，导航关系是"对话页为枢纽的星形结构"（chat → kb、chat → admin，login ↔ register）。这与管理端"登录后由后端菜单动态生成路由"的模式完全不同——用户端页面固定，直接静态注册即可。

### 2.3 应用清单（`aihub-app/manifest.json`）

```json
// aihub-app/manifest.json#30-48（节选两平台配置）
"mp-weixin": {
  "appid": "",
  "setting": { "urlCheck": false, "es6": true, "minified": true },
  "usingComponents": true
},
"h5": {
  "title": "AIHub",
  "router": { "mode": "hash" },
  "devServer": { "port": 5173, "disableHostCheck": true }
}
```

- `mp-weixin.appid` 为空：本地用微信开发者工具的"测试号"即可跑通；正式发布需填入注册的小程序 appid；
- `urlCheck: false`：关闭开发者工具的"合法域名校验"，否则 `http://localhost:8080` 与局域网 IP 会被直接拦截（正式上线必须配置 HTTPS 合法域名）；
- H5 `router.mode: "hash"`：刷新不丢路由、部署无需服务端 rewrite 兜底；
- `devServer.port: 5173`：用户端 H5 固定跑在 5173（管理端是 8849，二者同机开发互不冲突）。

### 2.4 入口三件套（main.js / App.vue / index.html）

```js
// aihub-app/main.js#1-7
import { createSSRApp } from 'vue'
import App from './App.vue'

export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
```

为什么是 `createSSRApp` 而不是普通 `createApp`？这是 uni-app 的硬性约定：uni 的编译器与运行时依赖 `createApp` 这个具名导出来识别应用入口，同时保持与 App 端 SSR 渲染机制的兼容。照写即可，不要自作主张改成 `createApp(App).mount('#app')`——挂载动作由 uni 运行时接管。

```html
<!-- aihub-app/index.html#5-15（H5 专属，编译小程序时会被忽略） -->
<script>
  var coverSupport =
    'CSS' in window &&
    typeof CSS.supports === 'function' &&
    (CSS.supports('top: env(a)') || CSS.supports('top: constant(a)'))
  document.write(
    '<meta name="viewport" ...' + (coverSupport ? ',viewport-fit=cover' : '') + '" />'
  )
</script>
```

这段是 uni 模板自带的刘海屏适配：检测浏览器是否支持 `env(safe-area-inset-*)`，支持则加 `viewport-fit=cover`，让页面内容延伸到安全区、配合 CSS 里的 `env(safe-area-inset-bottom)` 避开 iPhone 底部横条（chat.vue#417 的输入坞就用了它）。

```js
// aihub-app/App.vue#3-11
onLaunch() {
  // 启动时校验登录态，未登录跳转登录页
  const token = uni.getStorageSync('token')
  const pages = getCurrentPages()
  const route = pages.length ? pages[pages.length - 1].route : ''
  if (!token && !route.includes('login')) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
}
```

App.vue 没有 `<template>`，只承载全局生命周期与全局样式。`onLaunch` 是**前端版路由守卫**：读本地 token，没有就 `reLaunch`（清空页面栈）到登录页。注意它拿 token 的方式是同步读 storage，不做过期校验——JWT 是否真的有效由网关判定，401 时的处理在各请求层完成（见 4.2）。这是一层"体验性拦截"而非"安全性拦截"。

### 2.5 面试怎么说

> "用户端目录是 HBuilderX 风格的根目录结构，pages.json 充当静态路由表，第一个页面即首页。入口用 createSSRApp 是 uni 的约定；登录态用 App.vue 的 onLaunch 做体验性拦截，真正的鉴权在网关。这个结构后面还引发过一次启动故障——CLI 默认从 src/ 读源码，我们在第 3 章详细讲。"

---

## 3. 启动命令与踩坑实录（本章为本文重点）

### 3.1 四条启动/构建命令

```json
// aihub-app/package.json#6-11
"scripts": {
  "dev:h5": "node scripts/uni.js",
  "dev:mp-weixin": "node scripts/uni.js build -p mp-weixin -w",
  "build:h5": "node scripts/uni.js build",
  "build:mp-weixin": "node scripts/uni.js build -p mp-weixin"
}
```

| 命令 | 平台 | 模式 | 产物/效果 |
| --- | --- | --- | --- |
| `npm run dev:h5` | h5 | dev server | http://localhost:5173 热更新 |
| `npm run dev:mp-weixin` | mp-weixin | watch 构建 | 持续产出 `dist/dev/mp-weixin`，微信开发者工具导入后自动重编译 |
| `npm run build:h5` | h5 | 产物构建 | `dist/build/h5` 静态资源 |
| `npm run build:mp-weixin` | mp-weixin | 产物构建 | `dist/build/mp-weixin`，用于上传微信后台 |

四个命令的参数透传关系：`dev:mp-weixin` 中 `build -p mp-weixin -w` 被原样交给 uni CLI（`scripts/uni.js` 注释里写明"命令行参数原样透传（CAC 解析 process.argv.slice(2))"，对应 CLI 入口 `node_modules/@dcloudio/vite-plugin-uni/dist/cli/index.js#44-54` 注册的 `build` 命令与 `-p`/`-w` 选项）。

**注意所有命令的统一形态都是 `node scripts/uni.js ...`，而不是官方文档的 `uni` / `npx uni`。** 这正是踩坑的产物。

### 3.2 踩坑实录：直接跑 uni CLI 报 ENOENT src/manifest.json

**现象**：按 uni-app CLI 官方文档的习惯，在项目根目录执行 `npx uni`（或把 scripts 写成 `"dev:h5": "uni"`），启动即崩，报错形如：

```text
Error: ENOENT: no such file or directory, open '...\aihub-app\src\manifest.json'
```

一个显而易见的疑点：我们的项目里**根本没有 `src/` 目录**，`manifest.json` 明明在根目录躺着。

**根因**：uni CLI（`@dcloudio/vite-plugin-uni` 提供的 `bin/uni.js`）默认按"CLI 工程结构"从 `src/` 读取源码；只有 HBuilderX 可视化工程才用根目录结构。CLI 判断工程根的方式是环境变量 `UNI_INPUT_DIR`，**没设置时默认指向 `<cwd>/src`**。

**修复**：新增启动器 `aihub-app/scripts/uni.js`，在 require CLI 入口**之前**把 `UNI_INPUT_DIR` 设为项目根，所有 npm 命令都经它转发：

```js
// aihub-app/scripts/uni.js#13-18（全文就这几行，注释从略）
const path = require("path");

// 项目根目录（scripts/ 的上一级）；HBuilderX 运行时也使用根目录结构，保持一致
process.env.UNI_INPUT_DIR = path.resolve(__dirname, "..");

require("@dcloudio/vite-plugin-uni/bin/uni.js");
```

### 3.3 为什么 vite.config.js 的兜底"不够"——用 node_modules 源码证明

项目启动器修好后，`vite.config.js` 里仍保留了一行兜底：

```js
// aihub-app/vite.config.js#4-10
// 本项目沿用 HBuilderX 目录结构（pages.json 在项目根目录），而 uni CLI 默认从 src/ 读源码。
// npm 命令由 scripts/uni.js 启动器在 CLI 初始化前设置 UNI_INPUT_DIR 指向项目根；
// 这里再兜底设置一次（Vite 配置阶段，保证 vite 自身阶段变量已生效）。
process.env.UNI_INPUT_DIR = process.env.UNI_INPUT_DIR || process.cwd()

export default defineConfig({
  plugins: [uni()]
})
```

面试常见追问来了：**既然 vite.config.js 顶层就能设 `UNI_INPUT_DIR`，为什么不把它当主修复，还要专门写启动器？**

答案是**执行时序**：CLI 在加载 vite 配置文件**之前**，就已经消费 `UNI_INPUT_DIR` 去读 `manifest.json`/`pages.json` 了。下面用本项目 node_modules 里的真实源码把这条时序链钉死。

**证据 1：CLI 进程入口在第一行就碰 UNI_INPUT_DIR，此时 vite.config.js 根本没被加载**

```js
// node_modules/@dcloudio/vite-plugin-uni/bin/uni.js#3-6
// 尽可能提前加载 alias
require('@dcloudio/uni-cli-shared/dist/alias')
// 尽可能提前加载 polyfill
require('@dcloudio/uni-cli-shared/dist/polyfill')
```

```js
// node_modules/@dcloudio/vite-plugin-uni/bin/uni.js#45-56（节选）
// 临时逻辑
if (process.env.UNI_INPUT_DIR) {
  const fs = require('fs')
  const path = require('path')
  const tscFile = path.resolve(process.env.UNI_INPUT_DIR, 'TSC')
  if (fs.existsSync(tscFile)) { ... }
}
```

`bin/uni.js` 是 npm 命令最终执行的进程入口（`scripts/uni.js` 最后一行 require 的就是它），它从加载 alias/polyfill 到检查 `UNI_INPUT_DIR` 下的文件，全部发生在"解析命令行 → 进入 action → 创建 vite server"的流程里，而 vite 配置文件要到 `createServer` 内部才会被加载。

**证据 2：runDev/runBuild 先 initEnv、后建 vite server（vite 配置在 createServer 里才加载）**

```js
// node_modules/@dcloudio/vite-plugin-uni/dist/cli/action.js#42-59（runDev 节选）
async function runDev(options) {
    ...
    (0, utils_1.initEnv)('dev', options);          // ← 第 50 行：先初始化环境
    ...
    if (options.platform === 'h5') {
        const server = await (options.ssr
            ? (0, server_1.createSSRServer)(options)
            : (0, server_1.createServer)(options)); // ← 此处内部才加载 vite.config.js
```

```js
// node_modules/@dcloudio/vite-plugin-uni/dist/cli/action.js#218-226（runBuild 节选）
async function runBuild(options) {
    (0, utils_1.initEnv)('build', options);        // ← 第 219 行：同样先 initEnv
    ...
    await (options.ssr && options.platform === 'h5'
        ? (0, build_1.buildSSR)(options)
        : (0, build_1.build)(options));
```

**证据 3：initEnv 里对 UNI_INPUT_DIR 的默认值就是 `<cwd>/src`**

```js
// node_modules/@dcloudio/vite-plugin-uni/dist/cli/utils.js#124-128
    else {
        process.env.VITE_ROOT_DIR = process.env.VITE_ROOT_DIR || process.cwd();
    }
    process.env.UNI_INPUT_DIR =
        process.env.UNI_INPUT_DIR || path_1.default.resolve(process.cwd(), 'src');
    initVaporScriptLangEnv(process.env.UNI_INPUT_DIR);
```

第 126-127 行就是 ENOENT 的直接来源：你没提前设，它就替你指向 `src/`。

**证据 4：uni-cli-shared 全程以 UNI_INPUT_DIR 为根解析 manifest.json 与页面文件**

```js
// node_modules/@dcloudio/uni-cli-shared/dist/json/manifest.js#13-20
const parseManifestJson = (inputDir) => {
    const manifestFilename = path_1.default.join(inputDir, 'manifest.json');
    if (!fs_1.default.existsSync(manifestFilename)) {
        if (!(0, utils_1.isNormalCompileTarget)()) {
            return {};
        }
    }
    return (0, json_1.parseJson)(fs_1.default.readFileSync(manifestFilename, 'utf8'), false, manifestFilename);
```

`readFileSync(join(inputDir, 'manifest.json'))` —— 当 `inputDir` 是不存在的 `src/` 时，这一行抛出的正是 `ENOENT ... src/manifest.json`。同包的 `utils.js` 里还有大量同类消费点：

```js
// node_modules/@dcloudio/uni-cli-shared/dist/utils.js#52-53
function normalizePagePath(pagePath, platform) {
    const absolutePagePath = path_1.default.resolve(process.env.UNI_INPUT_DIR, pagePath);
```

```js
// node_modules/@dcloudio/uni-cli-shared/dist/utils.js#210-212
function createIdent(platform) {
    if (process.env.UNI_INPUT_DIR) {
        const manifestJson = (0, json_1.parseManifestJsonOnce)(process.env.UNI_INPUT_DIR);
```

**时序结论**（把四条证据串起来）：

```text
node scripts/uni.js
  └─ ① scripts/uni.js#16 设置 UNI_INPUT_DIR = 项目根   ← 主修复在这
       └─ ② bin/uni.js 进程入口（alias/polyfill/临时逻辑直接消费 UNI_INPUT_DIR）
            └─ ③ dist/cli/index.js → cac 解析参数 → runDev/runBuild
                 └─ ④ initEnv：UNI_INPUT_DIR 未设则默认 <cwd>/src（cli/utils.js#126-127）
                      └─ ⑤ createServer/build：此时才加载 vite.config.js
                           └─ ⑥ vite.config.js 顶层代码执行（兜底在这，但 ④ 已定型）
```

vite.config.js 的兜底排在第 ⑥ 步，而 CLI 读 manifest 的动作发生在第 ②~④ 步；一旦 ④ 把变量定成 `src/`，第 ⑤ 步之前的大片 CLI 逻辑就会在读文件时崩溃，**流程根本活不到第 ⑥ 步**。所以启动器是主修复，vite.config.js 的兜底只能防御"某些直接经由 vite 启动、跳过了 CLI 前置阶段的非常规路径"，属于第二道防线。

**常见误用**：
- 以为"在 vite.config.js 里 set 一下环境变量就能解决一切启动问题"——环境变量类配置必须区分"消费方在 vite 配置加载前还是后"；
- 把 `"dev:h5": "uni"` 改回官方写法后忘了还有 `dev:mp-weixin`/`build:*` 三条命令也走同一条路，只修一条等于没修；
- `UNI_INPUT_DIR` 必须是**绝对路径**——`scripts/uni.js` 用 `path.resolve(__dirname, "..")` 而不是相对路径 `".."`，因为 CLI 内部到处 `path.resolve(UNI_INPUT_DIR, ...)`，相对路径会相对进程 cwd 漂移。

**预防方法**：把所有 uni 命令统一收口到启动器（本项目 package.json 正是这么做的），并在 README/文档中明示"不要直接 npx uni"；如果团队里有人遇到 ENOENT，第一反应检查 `UNI_INPUT_DIR` 与源码目录是否一致。

### 3.4 面试怎么说

> "我们用户端是 HBuilderX 根目录结构，直接跑 uni CLI 会报 ENOENT src/manifest.json。我读 node_modules 源码定位到：CLI 进程入口和 initEnv 阶段就会消费 UNI_INPUT_DIR 并默认指向 src/，而这些都发生在 vite.config.js 加载之前，所以在 vite 配置里兜底来不及。解法是写了个 12 行的启动器，在 require CLI 入口前把 UNI_INPUT_DIR 设为项目根，四条 npm 命令统一从它转发。这个案例让我形成了习惯：环境变量修复要先确认消费时机在配置加载前还是后。"

---

## 4. 网络层

### 4.1 baseUrl：条件编译分端配置（`aihub-app/common/config.js`）

```js
// aihub-app/common/config.js#1-13（全文）
/**
 * 全局配置。
 * H5 本地开发走网关 8080；小程序真机需改为局域网 IP 或线上域名（并在微信后台配置合法域名）。
 */
const config = {
  // #ifdef H5
  baseUrl: 'http://localhost:8080',
  // #endif
  // #ifndef H5
  baseUrl: 'http://192.168.1.100:8080',
  // #endif
  scene: 'chat' // chat / rag / agent
}

export default config
```

设计意图有三层：

1. **直连网关 8080**：所有请求（`/auth/**`、`/api/**`）都打到网关，由网关路由到 platform-service/ai-service。前端不感知微服务拆分，网关是唯一域名；
2. **用条件编译而不是 `process.env` 区分端**：小程序构建链路里 `.env` 文件的注入行为与 H5 不完全一致，而 `#ifdef` 由 uni 编译器保证 100% 生效且物理隔离——H5 产物里根本不存在那行局域网 IP；
3. **小程序端写局域网 IP**：`http://192.168.1.100:8080` 是占位值，真机调试前必须改成开发机在局域网中的实际 IP。原因：手机上跑的小程序不在你的电脑里，`localhost` 指向手机自身；而微信小程序又要求请求走配置的地址，开发者工具里可以关校验，真机要靠"同一 WiFi + 可达 IP"。

### 4.2 请求封装（`aihub-app/common/request.js`）

**登录与 token 存储**——token 的唯一存储位置是 `uni.setStorageSync('token', ...)`（uni 的同步本地存储 API，H5 底层是 localStorage，小程序底层是 `wx.setStorageSync`）：

```js
// aihub-app/common/request.js#7-30（节选）
export function login({ tenantCode, username, password, captchaId, captchaCode }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + '/auth/login',
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { tenantCode, username, password, captchaId, captchaCode },
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          uni.setStorageSync('token', res.data.data.token)   // ← token 存这里
          uni.setStorageSync('userId', res.data.data.userId)
          resolve(res.data.data)
        } else {
          reject(new Error((res.data && res.data.message) || '登录失败'))
        }
      },
      ...
```

**通用请求**——注意成功判定是"双条件"，源码注释把这个坑写得很直白：

```js
// aihub-app/common/request.js#32-59（节选）
/**
 * 通用请求（带 JWT）。返回响应体中的 data 部分。
 *
 * 判断成功的条件有两个，缺一不可：
 *   1. HTTP 200；2. 业务码 code === 0。
 * 只判 HTTP 状态会把「网关放行但业务报错」当成成功，前端拿到的数据是 undefined。
 */
export function request(method, path, data) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + path,
      method,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + getToken()
      },
      data,
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          resolve(res.data.data)
        } else {
          reject(new Error((res.data && res.data.message) || '请求失败'))
        }
      },
      ...
```

这套封装是典型的"Promise 化回调 + 双层协议解包"：

- **Promise 化**：`uni.request` 是回调风格，包一层 Promise 后业务代码才能 `await`；
- **双层协议**：HTTP 层（statusCode）+ 业务层（后端统一响应体 `{ code, message, data }`，code===0 为成功）。成功时直接 `resolve(res.data.data)`，业务代码拿到的是"剥掉信封的数据"；
- **token 注入**：每个请求带 `Authorization: Bearer <token>`，由网关全局过滤器校验并透传租户头（网关部分见系列第 4 篇）；
- **错误信息保真**：失败时优先展示后端 `message`（如"验证码错误"），网络层错误拼上 `errMsg`（如 `request:fail timeout`），用户能看到具体原因。

`logout()`（request.js#80-83）做两件事：`uni.removeStorageSync('token')` 清凭证 + `uni.reLaunch` 清页面栈回登录页——**登出必须清页面栈**，否则返回键还能退回带数据的页面。

### 4.3 与 H5 代理方案的对比（为什么管理端用代理、用户端直连）

系列第 10 篇讲过，管理端 aihub-admin-ui 的 Vite devServer 把 `/auth`、`/api` 代理到网关 8080，前端代码只写相对路径。用户端**没有采用代理**，原因值得展开：

| 维度 | H5 devServer 代理（管理端方案） | 直连 baseUrl（用户端方案） |
| --- | --- | --- |
| 开发期跨域 | 代理转发，浏览器看到的是同源请求，无 CORS 问题 | 依赖网关统一 CORS 配置放行 |
| 小程序 | **不可用**——小程序没有 devServer，生产环境也不存在代理 | 唯一可行方案 |
| 真机调试 | 不适用 | 手机改连局域网 IP 即可 |
| 代码一致性 | 开发用代理、生产用域名，环境切换靠 env | H5/小程序同一套直连逻辑，条件编译只差一个地址 |
| 构建产物 | 需要运维配 Nginx 反代 | 前端静态文件扔哪都能跑（配网关 CORS） |

结论：**用户端因小程序的存在，直连是必选项而非优化项**；代理对它只在"纯 H5 开发"时有意义，为一致性干脆两端统一直连。代价是网关必须配好 CORS（这在网关文档第 4 篇的跨域章节覆盖）。

还有一个如实要指出的点：`pages/chat/chat.vue#231` 的 H5 分支打开生成物时用了硬编码地址 `window.open('http://localhost:8080' + a.url, '_blank')`，与 `config.baseUrl` 重复——换环境部署时要记得同步改（小程序分支不受影响，它走剪贴板复制相对链接）。这属于已知的小瑕疵，如实记录。

### 4.4 面试怎么说

> "用户端网络层是 80 行的手写封装：Promise 化 uni.request，双层判定 HTTP 200 加业务 code===0，剥信封后把 data 给业务层，每个请求注入 Bearer token，token 存 uni storage。 baseUrl 用条件编译分端：H5 是 localhost:8080 直连网关，小程序是局域网 IP。没有走管理端那种 devServer 代理，因为小程序没有 devServer、生产环境也没有代理，直连加网关 CORS 是跨端一致性和真机调试的唯一公约数。"

---

## 5. 核心页面逐页讲解

### 5.1 chat：流式对话（系统的心脏页）

对话页是 pages.json 的首页，也是全项目复杂度最高的页面。拆成四块讲。

**（1）状态与场景切换（`aihub-app/pages/chat/chat.vue#100-117`）**

```js
// aihub-app/pages/chat/chat.vue#100-117（data 节选）
return {
  messages: [WELCOME],
  input: '',
  scene: 'chat',            // 当前场景：chat 对话 / rag 知识库 / agent
  conversationId: '',       // 会话 ID：首轮为空，服务端 msg.start 事件下发后回填
  streaming: false,         // 是否正在接收流
  statusText: '',           // 流式过程中的状态条文案
  currentTask: null,        // streamChat 返回的中断句柄
  models: [],
  modelCode: '',            // 空串 = 走管理端配置的场景路由
  scenes: [
    { label: '对话', value: 'chat' },
    { label: '知识库', value: 'rag' },
    { label: 'Agent', value: 'agent' }
  ]
}
```

三个场景按钮（模板 chat.vue#67-76 渲染）只是改 `this.scene`，请求时随消息体发到后端，由 ai-service 按场景路由选择模型与策略（RAG 会检索知识库、Agent 会走工具循环）。

**（2）模型切换器（chat.vue#133-149）**

```js
// aihub-app/pages/chat/chat.vue#134-142
async loadModels() {
  try {
    const list = await get('/api/ai/model')
    this.models = (list || []).filter(m => m.status === 1)
  } catch (e) {
    // 拉取失败不打断聊天：保持默认路由
    this.models = []
  }
}
```

进入页面拉取当前租户启用的模型列表，`picker` 选择器展示；选中后 `modelCode` 随消息体发送，后端"优先使用该模型"；选"默认（场景路由）"则 `modelCode` 置空串，由管理端配置的场景路由决定（模型路由配置在 admin/model 页，见 5.5）。注意失败分支的注释——拉模型失败不该拦住聊天，这是体验优先的取舍。

**（3）发送与流式事件分派（chat.vue#156-228）**

```js
// aihub-app/pages/chat/chat.vue#156-177（send 节选）
send() {
  const text = (this.input || '').trim()
  if (!text || this.streaming) return
  this.input = ''
  this.messages.push({ role: 'user', content: text })
  const assistant = { role: 'assistant', content: '', sources: [], artifacts: [] }
  this.messages.push(assistant)          // ← 先插"空气泡"占位，token 到达后原地追加
  this.streaming = true
  this.statusText = '思考中…'
  this.scrollToBottom()

  this.currentTask = streamChat(
    {
      appId: Number(uni.getStorageSync('appId') || 9001),  // ← 应用 ID：本地存有则用，否则默认示例应用 9001
      conversationId: this.conversationId,
      message: text,
      scene: this.scene,
      modelCode: this.modelCode
    },
    (event) => this.onEvent(assistant, event)
  )
}
```

交互模式是"乐观 UI"：点发送立刻渲染用户气泡和一个空的 AI 气泡，流式 token 持续追加到这个气泡对象上（`assistant.content += ...`），Vue 响应式自动驱动增量渲染——**这就是"打字机效果"的实现本质，没有任何定时器**。

事件分派（chat.vue#189-228）把 stream.js 解析出的每一帧按 `e` 字段路由：

| 事件 | 处理（chat.vue#193-223） | 说明 |
| --- | --- | --- |
| `msg.start` | 记录 `data.conversationId` | 首轮为空，服务端建会话后回填，后续轮次带着它维持上下文 |
| `token` | `assistant.content += data.content` | 打字机主体 |
| `rag.sources` | 存 `assistant.sources`，状态条提示"已检索到 N 条引用" | 气泡底部渲染 `[1] 文档名（85%）` 引用标签（模板 chat.vue#17-22） |
| `agent.step` | 状态条展示 `agent.type + content`；act/observe 追加系统气泡 | Agent 拆解过程可视化 |
| `artifact` | 追加到 `assistant.artifacts` | Agent 生成的图表/表格/网页附件，点击打开（分端逻辑见下） |
| `msg.end` | 结束流式；记录本次回答所用模型 `data.model` | 气泡底部渲染模型小标签（chat.vue#31） |
| `error` | 把错误信息写进气泡，结束流式 | 401 时 stream.js 已转成"登录已过期" |

**（4）停止生成：双重取消（chat.vue#178-188）**

```js
// aihub-app/pages/chat/chat.vue#178-188
stop() {
  // 关键：先通知服务端取消，否则后端 Agent 循环还会把剩余子任务跑完
  if (this.conversationId) {
    post('/api/ai/agent/cancel', { conversationId: this.conversationId }).catch(() => {})
  }
  if (this.currentTask) {
    this.currentTask.abort()
  }
  this.streaming = false
  this.statusText = '已停止'
}
```

这是全页最有含金量的细节：**点"停止"要取消两个执行流**。客户端 `abort()` 只断开接收连接，服务端的 Agent 循环（任务拆解、多次工具调用）还在继续跑，token 照样烧；所以先 POST `/api/ai/agent/cancel` 通知服务端在步骤边界终止，再 abort 本地连接。stream.js#104-114 的注释同样强调了这一点："客户端断开只是停止接收，服务端仍在执行"。

**（5）生成物打开的分端逻辑（chat.vue#229-236）**

```js
// aihub-app/pages/chat/chat.vue#229-236
openArtifact(a) {
  // #ifdef H5
  window.open('http://localhost:8080' + a.url, '_blank')
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: a.url, success: () => uni.showToast({ title: '链接已复制' }) })
  // #endif
}
```

H5 直接开新窗口；小程序没有多窗口概念，退而求其次把链接复制到剪贴板让用户去浏览器打开——条件编译在页面层的典型小应用。

### 5.2 stream.js：NDJSON 流式接收的双端统一封装（本文技术含金量最高的文件）

先澄清一个容易混淆的概念：**本项目用户端并不使用标准 SSE（`text/event-stream` + EventSource）**，而是 **POST + Chunked 传输 + NDJSON 帧**（每行一个 JSON 事件，端点 `/api/ai/chat/ndjson`）。为什么弃用 EventSource？stream.js 文件头注释一语道破：

```js
// aihub-app/common/stream.js#4-12（文件头）
/**
 * 流式对话（NDJSON Chunked 双端统一封装）。
 *
 * - H5：fetch + ReadableStream（不用 EventSource：无法携带自定义 Header）
 * - 小程序：uni.request({ enableChunked: true }) + onChunkReceived
 *
 * 两端均按 \n 切分事件帧并处理半包，回调 onEvent(事件帧对象)。
 * 事件类型：msg.start / token / rag.sources / agent.step / artifact / msg.end / error
 */
```

EventSource 有两个硬伤：只支持 GET 请求、不能设置自定义请求头。而对话接口是 POST + 必须带 `Authorization: Bearer`，EventSource 直接出局。fetch + ReadableStream 在 H5 上是完美替代；小程序端则用 `enableChunked` 分块回调实现等价能力。

**核心解析逻辑：按 `\n` 分帧 + 半包缓冲 + 终帧冲刷**：

```js
// aihub-app/common/stream.js#40-55
const handleChunk = (text) => {
  buffer += text
  const lines = buffer.split('\n')
  buffer = lines.pop() || '' // 最后一段可能是半包，留到下一块
  for (const line of lines) emitLine(line)
}

/**
 * 流结束时冲刷缓冲区：服务端最后一帧可能没有以 \n 结尾，
 * 只靠 handleChunk 会把它当"半包"留在 buffer 里被丢掉（表现为最后一条消息渲染不出来）。
 */
const flush = () => {
  const rest = buffer
  buffer = ''
  emitLine(rest)
}
```

这段代码解决的是 TCP 流式接收的经典问题——**半包（sticky/partial frames）**：网络块边界与消息行边界不对齐，一个 chunk 可能含半行 JSON。解法是"攒进 buffer、按行切、尾巴留buffer"；`flush()` 再兜住最后一行没换行符的终帧（否则 `msg.end` 都到不了，气泡卡在打字中）。

**H5 分支**（stream.js#57-82）：`AbortController` 拿到中断句柄 → `fetch` POST → `res.body.getReader()` 循环 read → `TextDecoder` 解码 → `handleChunk`。401 转成 error 事件；`AbortError` 静默（用户主动停止不算错误）。

**小程序分支**（stream.js#84-100）：

```js
// aihub-app/common/stream.js#85-100
const task = uni.request({
  url: config.baseUrl + '/api/ai/chat/ndjson',
  method: 'POST',
  header,
  data: body,
  enableChunked: true,          // ← 关键开关：请求变成 chunked 分块回调模式
  responseType: 'arraybuffer',
  success: () => flush(),
  fail: (err) => onEvent({ e: 'error', ... })
})
task.onChunkReceived((res) => {
  if (res && res.data) {
    handleChunk(arrayBufferToUtf8(res.data))
  }
})
```

`enableChunked: true` 让微信请求对象支持 `onChunkReceived`，每收到一个网络块回调一次——语义上等价于 H5 的 reader 循环。但拿到的是 `ArrayBuffer`，而**微信小程序环境没有 `TextDecoder`**，所以 stream.js#120-145 手写了一个 UTF-8 解码器 `arrayBufferToUtf8`：按首字节前缀判断 1~4 字节序列（`0x80` 以下单字节、`0xe0` 以下两字节、`0xf0` 以下三字节、否则四字节），正确处理中文的三字节 UTF-8 编码——没有它，小程序端所有中文都会变乱码。

`abort()`（stream.js#104-114）同样条件编译：H5 调 `h5Controller.abort()` 真正断开 fetch 连接；小程序调 `task.abort()`。

**常见误用**：直接用 EventSource 连 `/api/ai/chat/ndjson`（方法不对、头带不上，401 循环）；忽略半包处理（JSON.parse 偶发失败被 catch 吞掉，表现为"对话时不时丢字"）；不写 flush（最后一条消息永远渲染不出来）；小程序端直接 `String(arrayBuffer)` 得到乱码。

### 5.3 login：验证码登录（`aihub-app/pages/login/login.vue`）

登录页三步：拉验证码 → 提交登录 → 跳首页。

```js
// aihub-app/pages/login/login.vue#68-86（拉验证码）
async loadCaptcha() {
  try {
    const res = await new Promise((resolve, reject) => {
      uni.request({
        url: config.baseUrl + '/auth/captcha',
        method: 'GET',
        success: resolve,
        fail: reject
      })
    })
    if (res.data?.code === 0) {
      this.form.captchaId = res.data.data.id
      this.form.captchaCode = ''
      this.captchaImg = res.data.data.imageBase64
    }
  } catch (e) {
    uni.showToast({ title: '验证码加载失败', icon: 'none' })
  }
}
```

验证码是"**一次性 ID + base64 图片**"模式：`GET /auth/captcha` 返回 `id`（随机题号，后端把答案存进 Redis）和 `imageBase64`（直接喂给 `<image>` 标签渲染，不需要静态资源路由）。提交时四要素齐全：租户码 + 用户名 + 密码 + 验证码对（captchaId + captchaCode），后端从 Redis 对答案。这个接口免鉴权（网关白名单），所以没走通用 `request()`，页面里直接裸调 `uni.request`——登录前根本没有 token 可注入。

```js
// aihub-app/pages/login/login.vue#87-100（登录提交）
async doLogin() {
  if (this.loading) return
  this.loading = true
  this.error = ''
  try {
    await login(this.form)
    uni.reLaunch({ url: '/pages/chat/chat' })
  } catch (e) {
    this.error = e.message
    this.loadCaptcha() // 失败后换一张验证码
  } finally {
    this.loading = false
  }
}
```

两个防御细节：`loading` 标志防连点（一次登录流程未结束不接受再次提交）；**登录失败必刷新验证码**——旧验证码即使答对了也可能已被消费或即将过期，不刷新会造成"明明输对了还是报错"的诡异体验。成功后 `reLaunch` 到对话页（清掉登录页，防止返回键回到登录表单）。表单预填 `demo / admin / 123456`（login.vue#57）是开发演示的默认租户与账号，方便一键进入。

多租户的入口就藏在第一行输入框：**租户码 tenantCode 是登录三要素之外的第一要素**——同一个用户名在不同租户下是不同账号，JWT 的 tenantId claim 正是从这一步开始锚定的。

### 5.4 register：注册即注册新企业 = 自助开租户（`aihub-app/pages/register/register.vue`）

这是整个项目"多租户 SaaS 化"最直观的一页：页面上的一句话副标题——"注册即创建独立租户 + 管理员账号"（register.vue#6）——就是自助开租户的产品化表达。**没有管理员审批、没有后台开通流程，填一张表单就在平台里拉起一个新企业**。

```js
// aihub-app/pages/register/register.vue#100-117（提交部分）
const res = await new Promise((resolve, reject) => {
  uni.request({
    url: config.baseUrl + '/auth/register',
    method: 'POST',
    header: { 'Content-Type': 'application/json' },
    data: {
      companyName: f.companyName,
      tenantCode: f.tenantCode,
      username: f.username,
      password: f.password,
      captchaId: f.captchaId,
      captchaCode: f.captchaCode
    },
    success: resolve,
    fail: reject
  })
})
if (res.data?.code === 0) {
  uni.showModal({
    title: '注册成功',
    content: `租户码 ${f.tenantCode}，请返回登录`,
    showCancel: false,
    success: () => uni.navigateBack()
  })
}
```

表单分三段（模板 register.vue#10-39）：企业信息（companyName + tenantCode）、管理员账号（username + password + password2）、安全验证（验证码）。前端校验只做必填与两次密码一致（register.vue#91-98），长度与字符规则（placeholder 里写的"2-32 位字母/数字"等）交给后端权威校验——**前端校验是体验优化，后端校验才是安全边界**，两道都设但职责分明。

一次提交在后端等价于一个事务：创建租户记录 + 创建该租户的管理员账号 + 赋予管理员角色。注册成功弹窗里专门强调"租户码 xxx，请返回登录"——租户码是后续所有登录的必要凭据，弹窗就是要把用户注意力钉在它上面。这里同样复用了 `/auth/captcha` 验证码接口（与登录页同一个），失败后刷新验证码的逻辑也与登录页一致。

### 5.5 admin 四页：简化管理后台

用户端的管理页是管理端 aihub-admin-ui 的"移动精简版"，覆盖租户管理员最高频的三个动作：看用量、配模型、调应用。入口页 `pages/admin/index.vue#22-38` 就是三张静态卡片（用量看板 / 模型管理 / 应用配置），`uni.navigateTo` 跳转，无任何接口调用。

**（1）model.vue：模型实例 + 场景路由**

```js
// aihub-app/pages/admin/model.vue#162-168（三接口并行加载）
async reload() {
  try {
    const [providers, models, routes] = await Promise.all([
      get('/api/ai/model/provider'),
      get('/api/ai/model'),
      get('/api/ai/model/route')
    ])
    ...
```

- **模型实例 CRUD**：供应商下拉（provider 列表）、模型编码、API Key、Base URL、向量维度（仅嵌入模型）、默认模型与启用开关（model.vue#10-50 表单）。其中 API Key 的处理很讲究：

```js
// aihub-app/pages/admin/model.vue#186-197（edit 节选）
edit(m) {
  this.editingId = m.id
  // apiKey 刻意留空：后端拿不回明文，留空即「保持原值」
  this.form = {
    providerCode: m.providerCode,
    modelCode: m.modelCode,
    apiKey: '',
    ...
```

密钥属于敏感数据，列表接口只返回 `hasApiKey` 布尔（模板 model.vue#64 渲染"已配置/未配置"），编辑表单留空表示"不改"。这是前后端配合的密钥保护惯例：**密钥可以写入但永远不可读回**。

- **场景路由**（model.vue#74-104）：四个场景 `SCENES = ['chat', 'rag', 'embed', 'agent-plan']`（model.vue#113），每个场景指定主模型 + 备用模型（备用可空），`PUT /api/ai/model/route` 保存。这正是对话页"默认（场景路由）"选项背后的配置源：chat 场景走哪个模型、embed 场景用哪个嵌入模型（维度必须与建库 vectorDim 一致），都在这里决定。

**（2）app.vue：应用（助手）配置**

```js
// aihub-app/pages/admin/app.vue#65-66
const STRATEGIES = ['none', 'react', 'plan_execute']
const MEMORY = ['window', 'summary']
```

应用的四个核心属性：名称、系统提示词（textarea）、Agent 策略（无 / ReAct 工具循环 / Plan-Execute 任务拆解）、记忆策略（滑动窗口 / 摘要压缩）、温度。温度做了前端预校验：

```js
// aihub-app/pages/admin/app.vue#157-163
const temperature = Number(this.form.temperature)
if (Number.isNaN(temperature) || temperature < 0 || temperature > 2) {
  // 前端先挡一道，避免把明显非法的值发到后端
  this.tip = '温度取值范围 0.00 ~ 2.00'
  ...
```

页面还展示每个应用绑定的知识库列表（app.vue#104-111 逐个调 `/api/ai/app/{id}/knowledge-bases`），绑定动作在 kb 页完成（见下）。删除应用前弹确认框，并提示"将同时解除该应用的知识库绑定"（app.vue#192）——把级联影响告诉用户，是删除类交互的基本素养。

**（3）usage.vue：用量看板**

```js
// aihub-app/pages/admin/usage.vue#129-134（四接口独立加载，互不拖累）
const [overview, trend, byModel, quota] = await Promise.allSettled([
  get('/api/platform/usage/overview?days=' + this.days),
  get('/api/platform/usage/trend?days=' + this.days),
  get('/api/platform/usage/by-model?days=' + this.days),
  get('/api/platform/usage/quota')
])
```

注意这里用的是 `Promise.allSettled` 而不是 `Promise.all`——四个板块（总览卡片 / Token 趋势 / 按模型分布 / 配额进度条）彼此独立，一个接口挂了不该让整个看板白屏，部分失败只提示"部分数据加载失败"（usage.vue#140-144）。而 model.vue 的三个接口用了 `Promise.all`，因为三个数据共同支撑同一个表单页，缺一个都算页面级失败。**并行请求选 all 还是 allSettled，取决于页面板块间的耦合度**——这个对比本身就是很好的面试素材。

趋势图是**纯 CSS 柱状图**（无 ECharts）：`scroll-view scroll-x` 横向滚动（usage.vue#36-43），柱高按窗口内 token 峰值归一化、最低 4rpx 保证"有调用但量少"的日子也可见（usage.vue#150-153）；90 天窗口柱子过密时每 7 根才画一个日期标签（usage.vue#158-160）。配额条 `ratio >= 0.8` 时数字与进度条变红预警（usage.vue#53-62）。数据口径与配额策略由 platform-service 提供（第 5 篇的用量模块）。

**（4）kb.vue：知识库管理（异步任务轮询的范本）**

知识库页完整演示了"创建 → 上传 → 异步入库 → 轮询进度 → 失败重试 → 绑定应用"的全生命周期：

```js
// aihub-app/pages/kb/kb.vue#128-153（上传与拿到 taskId）
uni.uploadFile({
  url: config.baseUrl + '/api/ai/kb/' + kbId + '/documents',
  filePath: file.path,
  name: 'file',
  header: { Authorization: 'Bearer ' + getToken() },
  success(up) {
    // 上传接口是异步提交：立即返回 taskId，需轮询进度
    let taskId = null
    try {
      const body = JSON.parse(up.data)
      taskId = body && body.data ? body.data.taskId : null
    } catch (e) { taskId = null }
    ...
    if (taskId) {
      that.pollIngest(kbId, taskId)
    }
  },
```

文件选择用 `uni.chooseMessageFile`（kb.vue#123）——这个 API 的名字暴露了它的语义：从微信会话中选择文件（小程序端上传文件的常规入口），H5 端则退化为标准文件选择。上传用 `uni.uploadFile`（multipart/form-data，与 `uni.request` 分属两套 API），**同样手动带 Bearer 头**——uploadFile 不会自动复用 request 封装，token 要单独注入，这是常见的遗漏点。

```js
// aihub-app/pages/kb/kb.vue#156-186（轮询节选）
pollIngest(kbId, taskId) {
  const that = this
  this.stopTimer('task-' + taskId)
  this.timers['task-' + taskId] = setInterval(() => {
    uni.request({
      url: config.baseUrl + '/api/ai/kb/ingest/' + taskId,
      ...
      success(res) {
        const task = res.data && res.data.code === 0 ? res.data.data : null
        if (!task) { that.stopTimer('task-' + taskId); return }
        that.tasks[task.docId] = task
        if (task.status === 2) {          // 2 = 完成
          that.tip = '入库完成'
          that.stopTimer('task-' + taskId)
          that.loadDocs(kbId)
        } else if (task.status === 3) {   // 3 = 失败
          that.tip = '入库失败：' + (task.errorMsg || '未知原因')
          that.stopTimer('task-' + taskId)
          that.loadDocs(kbId)
        }
      },
      ...
    })
  }, 1500)
}
```

关键设计：**上传接口异步化**——文档切片 + Embedding + 向量入库是重操作，HTTP 请求只负责受理并返回 `taskId`，切片进度由前端每 1.5 秒轮询一次（状态字典：0 待处理 / 1 处理中 / 2 完成 / 3 失败，kb.vue#100-102），到终态停表并刷新文档列表。定时器句柄收进 `this.timers` 字典按 key 管理，`onHide`/`onUnload` 时统一 `clearTimers()`（kb.vue#55-74）——**页面隐藏就停轮询**，否则小程序里页面栈销毁不彻底时定时器会常驻泄漏。失败文档提供 `POST /api/ai/kb/ingest/{taskId}/retry` 重试（kb.vue#194-213），若原始临时文件已释放则提示重新上传。

绑定应用（kb.vue#214-224）：`POST /api/ai/kb/bind`，appId 取本地 storage 的 `appId || 9001`。绑定后回到对话页切"知识库"场景提问即可体验 RAG 带引用的回答。

### 5.6 面试怎么说

> "对话页的流式实现是打字机效果的本质——先插空气泡占位，token 事件到达就字符串追加，响应式渲染自然增量。停止按钮是双重取消：先 POST agent/cancel 让服务端在步骤边界停 Agent 循环，再 abort 本地连接，因为断开接收不等于停止服务端执行。流式接收没有用 EventSource，因为它不能带 Authorization 头，两端分别是 fetch ReadableStream 和小程序 enableChunked，共享一套按行分帧、半包缓冲、终态冲刷的解析器，小程序没有 TextDecoder 就手写 UTF-8 解码。知识库页是异步任务轮询的范本：上传返回 taskId，1.5 秒轮询到终态，页面隐藏即停表防泄漏。"

---

## 6. H5 与小程序差异清单

本章把散落在前文的平台差异收拢成一张总表 + 四个专题，作为跨端开发的"检查清单"。

### 6.1 总表

| 维度 | H5 | 微信小程序 | 项目中的落点 |
| --- | --- | --- | --- |
| **baseUrl** | `http://localhost:8080` | `http://192.168.1.100:8080`（真机改开发机局域网 IP） | `common/config.js#6-11` 条件编译 |
| **启动命令** | `npm run dev:h5` → 浏览器直接访问 5173 | `npm run dev:mp-weixin` → 微信开发者工具导入 `dist/dev/mp-weixin` | `package.json#7-8` |
| **流式接收** | `fetch` + `ReadableStream` + `TextDecoder` | `uni.request({ enableChunked: true })` + `onChunkReceived` + 手写 UTF-8 解码 | `common/stream.js#57-82` / `#84-100` |
| **流中断** | `AbortController.abort()` | `task.abort()` | `common/stream.js#104-114` |
| **token 存储** | uni storage（底层 localStorage，随浏览器域隔离） | uni storage（底层 wx.setStorageSync，随小程序账号体系隔离） | `common/request.js#16-17` |
| **路由** | hash 路由（`manifest.json#42`），浏览器前进后退可用 | 原生页面栈（navigateTo/reLaunch），无浏览器历史概念 | 全部 `uni.navigateTo/reLaunch` |
| **打开外部链接** | `window.open(url, '_blank')` 新标签页 | 无多窗口 → 复制链接到剪贴板 | `pages/chat/chat.vue#229-236` |
| **文件选择** | 标准文件选择框 | `uni.chooseMessageFile`（从微信会话选文件） | `pages/kb/kb.vue#123-126` |
| **文件上传** | `uni.uploadFile`（内部 XHR FormData） | `wx.uploadFile`，**必须单独带 Authorization 头** | `pages/kb/kb.vue#128-132` |
| **请求域名校验** | 无限制 | 开发工具需 `urlCheck: false`；真机/上线需配置合法域名（HTTPS） | `manifest.json#33` |
| **安全区适配** | `env(safe-area-inset-bottom)`（刘海屏浏览器） | 原生 tabBar/导航栏已处理；自定义底部仍需 env | `index.html#5-15`、`chat.vue#417` |

### 6.2 专题一：存储差异

代码层面两端完全一致（都是 `uni.setStorageSync/getStorageSync/removeStorageSync`），差异在**底层实现与隔离边界**：

- H5：localStorage，按**域名 + 浏览器 Profile** 隔离。换浏览器、清缓存、无痕模式，token 即失联，行为与 Web 应用一致；
- 小程序：`wx.setStorageSync` 落在小程序沙箱，按**微信账号 + 小程序 appid** 隔离，卸载小程序才清空。token 的有效期因此比 H5 长。

统一封装的价值正在于此：`request.js#28-30` 的 `getToken()` 一处定义，两端各自翻译。**要注意的反模式**是绕过 uni API 直接用 `localStorage`/`wx.setStorageSync`——H5 端能跑、小程序端直接报 `localStorage is not defined`。

### 6.3 专题二：网络差异

三点最容易被忽视的差异：

1. **域名校验**：小程序每个请求的域名必须在小程序后台配置为"合法域名"（且要求 HTTPS）。开发期靠 `manifest.json#33` 的 `urlCheck: false` 在开发者工具里豁免；真机调试要在开发者工具勾选"不校验合法域名"；正式上线必须 HTTPS 域名——这意味着**本地 HTTP 网关永远只能用于开发**；
2. **真机 IP 可达性**：手机与开发机必须同一 WiFi，baseUrl 写开发机局域网 IP。IP 变了（DHCP 重新分配很常见）就要同步改 `common/config.js#10`；
3. **并发与超时**：小程序 `wx.request` 有并发数上限（10 个），本项目 usage 页四接口并发 + app 页逐应用查绑定都在安全范围内，但要在心里有这根弦；超时时间两端默认值不同，关键接口建议显式配置。

### 6.4 专题三：流式（SSE 话题）差异

严格地说本项目两端都不用 SSE 协议，而是 **NDJSON over Chunked**（原因见 5.2：EventSource 不能带自定义 Header，对话接口必须 POST + Bearer）。两端的真实差异集中在"**怎么拿到字节流**"：

| 环节 | H5 | 小程序 |
| --- | --- | --- |
| 发起 | `fetch(POST)` 返回 `Response`，`res.body.getReader()` 逐块 read | `uni.request` 加 `enableChunked: true`，返回 RequestTask |
| 收块 | `for(;;) await reader.read()` | `task.onChunkReceived(cb)` 回调 |
| 字节→文本 | 标准 `TextDecoder('utf-8')`，支持流式增量解码 | **无 TextDecoder**，手写 `arrayBufferToUtf8`（stream.js#120-145） |
| 中断 | `AbortController`（`fetch` 的 `signal`） | `task.abort()` |
| 收尾 | 循环自然 done 后 `flush()` | `success` 回调里 `flush()` |

分帧逻辑（`handleChunk`/`flush`）是两端共享的纯函数——这正是这个封装的架构价值：**平台差异被压缩到"字节流获取"这一层，协议解析层完全复用**。

### 6.5 专题四：路由差异

- H5：hash 路由，URL 形如 `http://localhost:5173/#/pages/chat/chat`，**可以直接把链接发给别人直达页面**（鉴权交给 App.vue 的 onLaunch 拦截）；
- 小程序：页面栈模型，`navigateTo` 压栈（最多 10 层）、`reLaunch` 清栈重开。本项目 `logout()` 与 `onLaunch` 都用 `reLaunch` 而非 `navigateTo`/`navigateBack`，就是为了**保证登录态切换后页面栈干净**——H5 的"回退按钮"在小程序里对应物理返回手势，页面栈不清理就会出现"登出了还能滑回数据页"的漏洞。

页面跳转全用 `uni.navigateTo/reLaunch`（编译到 H5 变成 history 操作、到小程序变成页面栈操作），**没有任何一处直接写 `window.location` 或 `wx.navigateTo`**——跨端兼容的纪律线。

### 6.6 面试怎么说

> "H5 与小程序的差异我按四条线管理：存储线统一走 uni storage，封装掉 localStorage 与 wx storage 的差异；网络线最大的坑是小程序域名校验与真机 IP，baseUrl 用条件编译分端；流式线的平台差异被压缩到字节流获取层，协议解析完全复用；路由线纪律是只用 uni 的跳转 API，登录登出用 reLaunch 保证页面栈干净。一句话总结：把平台差异关进条件编译和适配层两个笼子，业务代码尽量零感知。"

---

## 7. 本章面试三问与参考回答

### 问题一：为什么 aihub-app 不用 EventSource 接收流式回答？你们是怎么做的？

**参考回答**：EventSource 有两个硬伤：只支持 GET、不能设置自定义请求头。而对话接口是 POST 请求且必须携带 `Authorization: Bearer`，所以标准 SSE 从协议层面就不适用。我们实际采用 POST + Chunked 传输 + NDJSON 帧（每行一个 JSON 事件）的方案：H5 端用 fetch 拿 ReadableStream 逐块读取、TextDecoder 解码；小程序端用 `uni.request` 的 `enableChunked` 开启 `onChunkReceived` 分块回调，微信环境没有 TextDecoder，我们手写了支持中文多字节的 UTF-8 解码器。两端共享同一套按 `\n` 分帧的解析逻辑：buffer 攒字节、切行、处理半包，结束时 flush 冲刷最后一帧。事件协议包括 msg.start/token/rag.sources/agent.step/artifact/msg.end/error，前端按事件类型增量渲染。**追问预判**：为什么按行分帧（TCP 块边界与消息行边界不对齐，半包问题）；token 到达怎么渲染（空气泡占位 + 字符串追加的响应式打字机）；和后端 SseEmitter 的关系（后端如果用 SSE 是服务端实现选择，前端 NDJSON 方案与之解耦，见系列第 6/8 篇）。

### 问题二：讲讲 uni-app 的跨端原理。这个项目里哪些地方体现了条件编译？

**参考回答**：跨端靠三层机制。编译期条件编译：`#ifdef H5` / `#ifdef MP-WEIXIN` 注释块在编译时裁剪，两端拿到物理上不同的源码，浏览器专属的 fetch/window 不会进小程序包；运行时平台适配层：`uni.request`、`uni.setStorageSync`、`uni.navigateTo` 等统一 API 编译到各端映射为底层实现；编译产物不同：H5 产出 SPA，小程序产出 WXML/WXSS/JS。本项目条件编译用在三处：config.js 的 baseUrl 分端（H5 连 localhost、小程序连局域网 IP）；stream.js 的流式实现分端（fetch 分支与 enableChunked 分支）；chat.vue 的生成物打开分端（H5 window.open、小程序复制到剪贴板）。**追问预判**：条件编译与运行时 `if` 判断的区别（前者产物裁剪、体积小且不会误触不存在 API，后者两端都打包且运行期才分叉）；小程序为什么不能用 localStorage（运行环境没有 window/localStorage 对象）。

### 问题三：你们的 uni-app 启动命令为什么是 `node scripts/uni.js` 而不是官方的 `uni`？当时怎么排查的？

**参考回答**：本项目沿用 HBuilderX 根目录结构（manifest.json、pages.json 在根目录，没有 src/），而 uni CLI 默认从 `src/` 读源码，直接跑 `npx uni` 报 ENOENT：找不到 `src/manifest.json`。我读了 node_modules 源码确认时序：CLI 进程入口 `bin/uni.js` 前几行就消费 `UNI_INPUT_DIR`；`runDev/runBuild` 先调 `initEnv` 再创建 vite server，而 `initEnv` 里有 `process.env.UNI_INPUT_DIR = process.env.UNI_INPUT_DIR || resolve(cwd, 'src')` 的默认值逻辑；uni-cli-shared 的 `parseManifestJson` 就是 `readFileSync(join(inputDir, 'manifest.json'))`。关键结论是：**CLI 读取 manifest 发生在 vite.config.js 加载之前**，所以 vite 配置里的兜底来不及生效。解法是写了个十几行的启动器 scripts/uni.js，在 require CLI 入口之前把 `UNI_INPUT_DIR` 设为项目根绝对路径，四条 npm 命令统一从它转发。这个案例的迁移价值是：环境变量类修复必须先确认消费时机在配置加载之前还是之后。**追问预判**：为什么用 `path.resolve(__dirname, '..')`（保证绝对路径、不受进程 cwd 影响）；vite.config.js 里那行兜底还要不要留（作为防御非常规启动路径的第二道防线，成本为零）。

---

## 附：本文引用文件清单

| 文件路径 | 用途 |
| --- | --- |
| `aihub-app/package.json` | 依赖版本、四条启动命令、engines 约束 |
| `aihub-app/scripts/uni.js` | 启动器：提前设置 UNI_INPUT_DIR（第 3 章主修复） |
| `aihub-app/vite.config.js` | Vite 配置与 UNI_INPUT_DIR 兜底 |
| `aihub-app/manifest.json` | H5/小程序平台配置 |
| `aihub-app/pages.json` | 8 页面注册表 |
| `aihub-app/index.html` | H5 入口与安全区适配 |
| `aihub-app/main.js` | createSSRApp 应用入口 |
| `aihub-app/App.vue` | onLaunch 登录态拦截 |
| `aihub-app/common/config.js` | 条件编译 baseUrl |
| `aihub-app/common/request.js` | HTTP 封装与 token 存储 |
| `aihub-app/common/stream.js` | NDJSON 流式双端封装 |
| `aihub-app/pages/chat/chat.vue` | 流式对话页 |
| `aihub-app/pages/login/login.vue` | 登录页 |
| `aihub-app/pages/register/register.vue` | 注册新企业（自助开租户）页 |
| `aihub-app/pages/kb/kb.vue` | 知识库管理页 |
| `aihub-app/pages/admin/index.vue` | 管理后台入口 |
| `aihub-app/pages/admin/model.vue` | 模型与场景路由页 |
| `aihub-app/pages/admin/app.vue` | 应用配置页 |
| `aihub-app/pages/admin/usage.vue` | 用量看板页 |
| `node_modules/@dcloudio/vite-plugin-uni/bin/uni.js` | CLI 进程入口（第 3 章证据 1） |
| `node_modules/@dcloudio/vite-plugin-uni/dist/cli/index.js` | cac 命令注册 |
| `node_modules/@dcloudio/vite-plugin-uni/dist/cli/action.js` | runDev/runBuild 时序（第 3 章证据 2） |
| `node_modules/@dcloudio/vite-plugin-uni/dist/cli/utils.js` | initEnv 默认 src/（第 3 章证据 3） |
| `node_modules/@dcloudio/uni-cli-shared/dist/json/manifest.js` | parseManifestJson（第 3 章证据 4） |
| `node_modules/@dcloudio/uni-cli-shared/dist/utils.js` | normalizePagePath / createIdent（第 3 章证据 4 补充） |
