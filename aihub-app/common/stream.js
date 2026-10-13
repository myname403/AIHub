import config from './config.js'
import { getToken, handleUnauthorized } from './request.js'

/**
 * 流式对话（NDJSON Chunked 双端统一封装）。
 *
 * - H5：fetch + ReadableStream（不用 EventSource：无法携带自定义 Header）
 * - 小程序：uni.request({ enableChunked: true }) + onChunkReceived
 *
 * 两端均按 \n 切分事件帧并处理半包，回调 onEvent(事件帧对象)。
 * 事件类型：msg.start / token / reasoning / rag.sources / agent.step / agent.plan /
 * agent.task.done / tool.start / tool.end / artifact / msg.end / error
 *
 * **传输层兜底事件（正确性保障，业务语义由 chat.vue 判断）：**
 * - `__transport.end`：连接正常关闭。若前端此时还没收到 msg.end/error，
 *   说明服务端异常断流（网关超时/进程重启），必须补一个本地终态，
 *   否则 streaming 永远卡在 true、输入框锁死——这是真实发生过的事故形态。
 * - `__transport.timeout`：空闲看门狗触发（IDLE_TIMEOUT 内一帧都没到），
 *   主动断开并上报，防止"思考中"永久转圈。
 * - `__transport.abort`：调用方主动 abort（点停止），前端已自行清理状态，无需再处理。
 */
const IDLE_TIMEOUT = 90000

export function streamChat({ appId = 0, conversationId = '', message, scene, modelCode = '' }, onEvent) {
  // modelCode 可选：前端模型切换器指定时后端优使用该模型，空 = 走管理端场景路由
  const body = JSON.stringify({
    appId,
    conversationId,
    message,
    scene,
    ...(modelCode ? { modelCode } : {})
  })
  const header = {
    'Content-Type': 'application/json',
    Authorization: 'Bearer ' + getToken()
  }
  let buffer = ''
  // H5 侧的中断句柄（AbortController 只能在 H5 用；小程序侧用 task.abort()）
  let h5Controller = null
  let mpTask = null
  // 兜底状态：finished 保证 transport 终态事件只发一次；idleTimer 看门狗句柄
  let finished = false
  let idleTimer = null

  const clearIdle = () => {
    if (idleTimer) {
      clearTimeout(idleTimer)
      idleTimer = null
    }
  }

  /** 看门狗：连续 IDLE_TIMEOUT 没有任何帧 → 断开并通知（服务端卡死/中间层静默吞流） */
  const armIdle = () => {
    clearIdle()
    idleTimer = setTimeout(() => {
      if (finished) return
      finished = true
      abort()
      onEvent({ e: '__transport.timeout', data: {} })
    }, IDLE_TIMEOUT)
  }

  const emitLine = (line) => {
    const trimmed = line.trim()
    if (!trimmed) return
    try {
      onEvent(JSON.parse(trimmed))
    } catch (e) {
      // 忽略无法解析的残缺行
    }
  }

  const handleChunk = (text) => {
    armIdle()
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

  /** 传输层终态：无论服务端是否发过 msg.end，连接关闭这件事本身要告知上层 */
  const finish = () => {
    if (finished) return
    finished = true
    clearIdle()
    flush()
    onEvent({ e: '__transport.end', data: {} })
  }

  // #ifdef H5
  h5Controller = new AbortController()
  fetch(config.baseUrl + '/api/ai/chat/ndjson', {
    method: 'POST',
    headers: header,
    body,
    signal: h5Controller.signal
  }).then(async (res) => {
    if (res.status === 401) {
      // 登录过期：提示并自动跳回登录页
      finished = true
      clearIdle()
      handleUnauthorized()
      onEvent({ e: 'error', data: { message: '登录已过期' } })
      return
    }
    armIdle()
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      handleChunk(decoder.decode(value, { stream: true }))
    }
    finish()
  }).catch((err) => {
    if (finished) return
    // 用户点"停止"触发的中断不是错误，只发 abort 事件供上层对齐状态
    if (err && err.name === 'AbortError') {
      finished = true
      clearIdle()
      onEvent({ e: '__transport.abort', data: {} })
      return
    }
    finished = true
    clearIdle()
    onEvent({ e: 'error', data: { message: '连接失败：' + err.message } })
  })
  // #endif

  // #ifdef MP-WEIXIN
  mpTask = uni.request({
    url: config.baseUrl + '/api/ai/chat/ndjson',
    method: 'POST',
    header,
    data: body,
    enableChunked: true,
    responseType: 'arraybuffer',
    success: (res) => {
      if (res && res.statusCode === 401) {
        finished = true
        clearIdle()
        handleUnauthorized()
        onEvent({ e: 'error', data: { message: '登录已过期' } })
        return
      }
      finish()
    },
    fail: (err) => {
      if (finished) return
      finished = true
      clearIdle()
      // 小程序侧 task.abort() 触发的 fail 不是错误，对齐 H5 的 AbortError 语义：
      // 调用方点"停止"时这里只发 abort 事件，避免把主动中断渲染成"连接失败"
      if (err && err.errMsg && String(err.errMsg).indexOf('abort') >= 0) {
        onEvent({ e: '__transport.abort', data: {} })
        return
      }
      onEvent({ e: 'error', data: { message: '连接失败：' + (err.errMsg || '') } })
    }
  })
  mpTask.onChunkReceived((res) => {
    if (res && res.data) {
      handleChunk(arrayBufferToUtf8(res.data))
    }
  })
  // #endif

  return { abort }

  function abort() {
    // #ifdef H5
    // 真正断开 fetch 连接（旧实现里 H5 分支是空的，只能等服务端跑完）。
    // 注意：客户端断开只是停止接收，服务端仍在执行 —— 所以调用方还要配合
    // POST /api/ai/agent/cancel 让服务端在步骤边界终止（见 pages/chat/chat.vue 的 stop()）。
    if (h5Controller) h5Controller.abort()
    // #endif
    // #ifdef MP-WEIXIN
    mpTask && mpTask.abort()
    // #endif
  }
}

/**
 * 微信小程序没有 TextDecoder，这里手写 UTF-8 解码（含中文多字节）。
 */
function arrayBufferToUtf8(buf) {
  const bytes = new Uint8Array(buf)
  let out = ''
  let i = 0
  while (i < bytes.length) {
    const b = bytes[i]
    if (b < 0x80) {
      out += String.fromCharCode(b)
      i += 1
    } else if (b < 0xe0) {
      out += String.fromCharCode(((b & 0x1f) << 6) | (bytes[i + 1] & 0x3f))
      i += 2
    } else if (b < 0xf0) {
      out += String.fromCharCode(
        ((b & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)
      )
      i += 3
    } else {
      const cp = ((b & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) |
        ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)
      i += 4
      out += String.fromCodePoint(cp)
    }
  }
  return out
}
