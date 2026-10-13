# 08 · 用户端前端 uni-app —— 学习文档

> 源码位置：`aihub-app/`（Vue 3 + uni-app，一套代码编译出 H5 网页与微信小程序）
> 配套代码注释已增强，建议对照源码阅读。本文所有 demo 都是可以直接复制到浏览器控制台或最小页面里运行的。

---

## 1. 先搞懂三个词

**uni-app**：DCloud 出品的"一套代码、多端运行"框架。你用 Vue 语法写一遍页面，它能编译成 H5 网页、微信小程序、App。原理很简单：**你写的 `<view>` `<input>` 是抽象标签，编译器在不同端把它们翻译成不同实现**——H5 端翻译成 `<div>` `<input>`，小程序端翻译成小程序自己的 `<view>` 组件。

**Vue 3**：前端三大框架之一。核心思想是**数据驱动**——你只管改数据，页面自动跟着变。对比原生 JS：

```html
<!-- 原生写法：改数据后要手动更新 DOM -->
<input id="name" oninput="document.getElementById('show').innerText = this.value">
<span id="show"></span>

<!-- Vue 写法：数据绑定了，改数据 = 改页面 -->
<input v-model="name">
<span>{{ name }}</span>
```

```js
// Vue 的全部魔法就这一句：name 是"响应式数据"
import { ref } from 'vue'
const name = ref('')   // ref() 把普通变量变成"被监控的变量"
// 任何地方 name.value = '张三'，页面上所有 {{ name }} 自动更新
```

**H5 与小程序的差异**：本项目同一套代码，编译命令不同（`npm run dev:h5` 出网页、`npm run dev:mp-weixin` 出小程序）。差异处用**条件编译**处理（见 2.3）。

---

## 2. 项目结构——每个文件是干什么的

```
aihub-app\
├── main.js            # 应用入口：创建 Vue 应用（固定写法，基本不动）
├── App.vue            # 全局生命周期（本项目基本没用到）
├── pages.json         # ★ 路由表：每个页面必须在这里登记，否则打不开
├── manifest.json      # 应用信息（名称、图标、各端配置）
├── vite.config.js     # 构建配置（UNI_INPUT_DIR 指向根目录）
├── common\
│   ├── config.js      # ★ 后端地址（8080 网关）
│   ├── request.js     # ★ HTTP 封装：登录 + 带 token 的增删改查
│   └── stream.js      # ★★ 流式对话封装（本项目最值得读的文件）
└── pages\
    ├── login\login.vue       # 登录页
    ├── register\register.vue # 注册页
    ├── chat\chat.vue         # ★★ 对话页（流式渲染核心）
    ├── kb\kb.vue             # 知识库管理（上传 + 轮询进度）
    └── admin\*.vue           # 简易管理页
```

**页面三件套**：每个页面是一个 `.vue` 文件 = `<template>`（长什么样）+ `<script>`（数据与逻辑）+ `<style>`（样式）。

---

## 3. 网络请求封装（common/request.js）——从回调到 Promise

### 3.1 为什么需要封装

裸的 `uni.request` 是**回调风格**，连发两个请求会嵌套成"回调地狱"：

```js
// 回调地狱：想先登录再查用量，只能一层套一层
uni.request({ url: '/auth/login', success: (res1) => {
  uni.request({ url: '/usage', header: { token: res1.data.token },
    success: (res2) => { console.log(res2.data) }
  })
}})
```

**Promise 是" callbacks 的规范包装"**——把"将来才有结果"的事变成一个可以 `await` 的对象：

```js
// 最小 Promise demo：把 setTimeout 包装成"3 秒后告诉我"
function wait3s() {
  return new Promise((resolve) => {     // resolve = "事情办好了，把结果交出去"
    setTimeout(() => resolve('办好了'), 3000)
  })
}
// 用法一：then 链
wait3s().then((msg) => console.log(msg))
// 用法二：async/await（推荐，读起来像同步代码）
async function main() {
  const msg = await wait3s()            // "等它办完再往下走"
  console.log(msg)
}
```

### 3.2 项目的封装（request.js 逐段讲）

```js
// 登录：多传验证码两个参数；成功把 token 存进本地存储
export function login({ tenantCode, username, password, captchaId, captchaCode }) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + '/auth/login',
      method: 'POST',
      data: { tenantCode, username, password, captchaId, captchaCode },
      success: (res) => {
        // 双重判断：HTTP 200 只是"通了"，业务成败看 body 里的 code
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          uni.setStorageSync('token', res.data.data.token)   // 存本地（小程序/H5 通用）
          resolve(res.data.data)
        } else {
          reject(new Error(res.data?.message || '登录失败'))  // reject = "失败了"
        }
      }
    })
  })
}
```

三个要点：
1. **`uni.setStorageSync('token', ...)`**：本地存储，类似浏览器的 localStorage，重启不丢。存进去的 token 是后续所有请求的"身份证"；
2. **`res.data.code === 0` 才算成功**：后端统一响应是 `{code, message, data}`，HTTP 永远 200，业务错误藏在 code 里（见学习文档 01）；
3. **失败不吞掉**：`reject(new Error(提示))` 交给页面去展示，而不是弹个框了事。

### 3.3 通用请求：自动带 token

```js
export function request(method, path, data) {
  return new Promise((resolve, reject) => {
    uni.request({
      url: config.baseUrl + path,
      method,
      header: {
        'Content-Type': 'application/json',
        Authorization: 'Bearer ' + getToken()   // ★ 每个请求自动带上 JWT
      },
      data,
      success: (res) => {
        if (res.statusCode === 200 && res.data && res.data.code === 0) {
          resolve(res.data.data)      // 注意：直接把 data 字段交出去，调用方少解一层
        } else {
          reject(new Error(res.data?.message || '请求失败'))
        }
      }
    })
  })
}
// 页面里用起来就一行
const list = await get('/api/ai/model')   // GET 拿模型列表
await post('/api/ai/agent/cancel', { conversationId })   // POST 取消任务
```

---

## 4. 流式对话（common/stream.js）——本项目前端最难也最值得学

### 4.1 什么是"流式"？为什么必须流式？

大模型生成一句话需要几秒到几十秒。两种交付方式：

```
非流式：用户等 30 秒 → 一次性收到整段话          （像下载完整文件）
流式：  用户等 1 秒 → 之后每个字陆续到达实时上屏   （像看人打字）
```

后端把回答切成一个个**事件帧**（JSON），用"不断开的 HTTP 连接"持续推送。前端要做的事：**发请求 → 不等结束 → 每到一段就解析并上屏**。

### 4.2 H5 端：fetch + ReadableStream

```js
// 最小流式 demo（可直接在浏览器控制台跑，体验"一块块到达"）
fetch('https://httpbin.org/drip?numbytes=50&duration=5')  // 这个网址会 5 秒吐 50 字节
  .then(async (res) => {
    const reader = res.body.getReader()          // 拿到"读取器"
    const decoder = new TextDecoder('utf-8')     // 字节 → 文本
    for (;;) {                                   // 无限循环，直到流结束
      const { done, value } = await reader.read() // 等下一块数据
      if (done) break                            // done=true = 服务器说发完了
      console.log('收到一块:', decoder.decode(value))
    }
  })
```

项目的实现多了三件事：**按 `\n` 切分**（每行一个 JSON 事件帧）、**半包缓冲**（一块数据可能只含半行 JSON）、**abort 中断**：

```js
let buffer = ''
const handleChunk = (text) => {
  buffer += text                       // 新到的字节数据先攒起来
  const lines = buffer.split('\n')     // 按换行切开
  buffer = lines.pop() || ''           // ★ 最后一段可能是"半行"（半包），留到下一块再拼
  for (const line of lines) {
    if (!line.trim()) continue
    onEvent(JSON.parse(line))          // 每行是一个完整 JSON 事件帧 → 交给页面
  }
}
```

**半包是流式解析的头号坑**：网络不保证"一块 = 一行"。一行 200 字节的 JSON 可能被拆成 130 + 70 两块到达。解法就是上面的"攒着 + pop 留半行"，这是所有 TCP 流解析的通用模式。

**中断（停止按钮）**：用 `AbortController`：

```js
const controller = new AbortController()
fetch(url, { signal: controller.signal })   // 把信号量交给 fetch
// 用户点"停止"时：
controller.abort()   // 浏览器立刻断开连接，fetch 那边抛 AbortError
```

小程序端没有 fetch/AbortController，用的是 `uni.request({ enableChunked: true })` + `onChunkReceived` 回调，思路完全一样（见 stream.js 的 `#ifdef MP-WEIXIN` 段，其中还手写了 UTF-8 解码器——小程序没有 TextDecoder）。

### 4.3 事件帧如何变成"打字机"（chat.vue 的 onEvent）

```js
onEvent(assistant, event) {
  const type = event.e || event.event
  switch (type) {
    case 'msg.start':  this.conversationId = event.data.conversationId; break
    case 'token':      assistant.content += event.data.content || ''    // ★ 每来一个字就追加，页面自动滚动更新
                       break
    case 'rag.sources': assistant.sources = event.data.sources; break  // 引用卡片
    case 'msg.end':    this.streaming = false; assistant.model = event.data.model; break
    case 'error':      assistant.content += event.data.message; this.streaming = false; break
  }
}
```

`assistant` 是 `messages` 数组里的一项（响应式对象），`assistant.content += ...` 一执行，那个气泡就自动长高——这就是开头说的"数据驱动"。

---

## 5. 页面讲解（挑关键机制）

### 5.1 登录页 login.vue——表单绑定与验证码

```html
<input v-model="form.username" placeholder="用户名" />
<!-- v-model = 双向绑定：输入框变 → form.username 变；代码改 form.username → 输入框变 -->
```

验证码流程：`onLoad()`（页面加载）时 `loadCaptcha()` 请求 `GET /auth/captcha`，把返回的 base64 图片直接塞进 `<image :src="captchaImg">`，同时记住 `captchaId`；提交时把 `captchaId + 用户输入` 一起传给后端。**登录失败要重新拉一张**（验证码是一次性的，后端校验过就作废）。

### 5.2 对话页 chat.vue——页面状态机

页面本质是一台小状态机：`streaming=true` 时显示"停止"按钮和思考动画，`msg.end/error` 时回到空闲。**发送前如果 `streaming=true` 直接 return**——防止并发两条流互相写内容。停止要做两件事：`abort()` 断开连接 + `POST /agent/cancel` 通知服务端（客户端断开 ≠ 服务端停止，Agent 还会继续跑完当前步）。

### 5.3 知识库页 kb.vue——上传 + 轮询

文档入库是**异步任务**（上传秒回，后台慢慢切片向量化），前端用**轮询**获取进度：

```js
pollIngest(kbId, taskId) {
  this.timers['task-' + taskId] = setInterval(async () => {
    const task = await get('/api/ai/kb/ingest/' + taskId)   // 每两秒问一次进度
    this.tasks[task.docId] = task                           // 更新进度条数据
    if (task.status === 2) {                                // 2 = 完成（终态）
      this.stopTimer('task-' + taskId)                      // ★ 终态必须停表，否则内存泄漏
      this.loadDocs(kbId)
    }
  }, 2000)
}
```

注意 `clearTimers()` 在 `onHide/onUnload` 里也要调——页面关了定时器还跑，是小程序最常见的内存泄漏。

---

## 6. 本模块注解/语法词典

| 语法 | 出处 | 通俗解释 |
|---|---|---|
| `v-model` | 所有表单 | 双向绑定，输入框与变量自动同步 |
| `v-for` + `:key` | 消息列表 | 循环渲染数组；key 帮 Vue 识别哪项变了（别用数组下标当 key） |
| `v-if` / `v-else` | 发送/停止按钮 | 条件渲染，直接增删 DOM |
| `@click` / `@confirm` | 按钮/回车 | 事件绑定（@ 是 v-on 的缩写） |
| `onLoad / onShow / onHide / onUnload` | 各页面 | 页面生命周期：加载/每次显示/隐藏/销毁 |
| `uni.setStorageSync / getStorageSync` | token 存取 | 本地存储（同步版），重启不丢 |
| `#ifdef H5 / #ifdef MP-WEIXIN` | stream.js | 条件编译：这段代码只在指定端生效，其他端直接删除 |
| `?.` 可选链 / `??` 空值合并 | 全项目 | `a?.b` = a 不是 null/undefined 才取 b；防空指针的语法糖 |

---

## 7. 常见坑速查

| 坑 | 现象 | 原因/解法 |
|---|---|---|
| 页面打不开 | 控制台"页面不存在" | pages.json 没登记新页面 |
| 改了代码没变化 | H5 还是旧的 | vite 热更新偶尔失灵，Ctrl+F5 强刷 |
| 流式只收到一两块 | 回答卡住 | 忘了 flush/半包处理；检查 buffer 逻辑 |
| 请求 401 | 突然全部失败 | token 过期（2 小时），重新登录 |
| 验证码总说错误 | 明明输对了 | 验证码一次性 + 2 分钟过期；失败后要重新拉图 |
| 小程序端流式乱码 | 中文变问号 | 小程序没有 TextDecoder，需手写 UTF-8 解码（stream.js 已内置） |
| 定时器越跑越多 | 进度条错乱 | 轮询前先 stopTimer 同 key 的旧表 |

---

## 8. 学习自测

1. uni-app 怎么做到"一套代码多端运行"？条件编译是干什么的？
2. `res.statusCode === 200` 和 `res.data.code === 0` 分别代表什么？为什么两个都要判断？
3. 手写最小 Promise：把 `uni.request` 包装成返回 Promise 的函数（提示：参考 request.js）。
4. 流式解析为什么要"攒 buffer + pop 半行"？如果跳过这步会出现什么现象？
5. AbortController 断开连接后，服务端还会继续执行吗？所以 chat.vue 的 stop() 为什么要同时调两个接口？
6. 轮询进度为什么要在终态和 onHide 里都停掉定时器？
7. v-model 背后发生了什么？（提示：`:value` + `@input` 的语法糖）

答完进入 09 管理端文档。
